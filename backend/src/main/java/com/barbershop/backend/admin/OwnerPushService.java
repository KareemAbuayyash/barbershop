package com.barbershop.backend.admin;

import java.net.URI;
import java.security.Security;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OwnerPushService {

	private static final Logger LOGGER = LoggerFactory.getLogger(OwnerPushService.class);
	private static final List<String> ALLOWED_PUSH_HOSTS = List.of(
			"fcm.googleapis.com",
			"updates.push.services.mozilla.com",
			"push.services.mozilla.com",
			"web.push.apple.com");

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final String publicKey;
	private final String privateKey;
	private final String subject;

	public OwnerPushService(
			JdbcTemplate jdbc,
			ObjectMapper objectMapper,
			@Value("${barbershop.push.vapid-public-key:}") String publicKey,
			@Value("${barbershop.push.vapid-private-key:}") String privateKey,
			@Value("${barbershop.push.subject:mailto:owner@barberhouse.local}") String subject) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.publicKey = publicKey.trim();
		this.privateKey = privateKey.trim();
		this.subject = subject.trim();
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
	}

	public Map<String, Object> publicConfiguration() {
		boolean enabled = !publicKey.isBlank() && !privateKey.isBlank();
		return Map.of("enabled", enabled, "publicKey", enabled ? publicKey : "");
	}

	@Transactional
	public void saveSubscription(String endpoint, String p256dh, String auth) {
		validateEndpoint(endpoint);
		jdbc.update("""
				INSERT INTO owner_push_subscriptions (endpoint, p256dh_key, auth_key)
				VALUES (?, ?, ?)
				ON CONFLICT (endpoint) DO UPDATE
				SET p256dh_key = EXCLUDED.p256dh_key, auth_key = EXCLUDED.auth_key
				""", endpoint, p256dh, auth);
	}

	@Transactional
	public void removeSubscription(String endpoint) {
		validateEndpoint(endpoint);
		jdbc.update("DELETE FROM owner_push_subscriptions WHERE endpoint = ?", endpoint);
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void sendAfterCommit(OwnerPushNotification event) {
		if (publicKey.isBlank() || privateKey.isBlank()) return;
		List<StoredSubscription> subscriptions = jdbc.query("""
				SELECT endpoint, p256dh_key, auth_key FROM owner_push_subscriptions
				""", (rs, row) -> new StoredSubscription(
				rs.getString("endpoint"), rs.getString("p256dh_key"), rs.getString("auth_key")));
		if (subscriptions.isEmpty()) return;

		final String payload;
		try {
			payload = objectMapper.writeValueAsString(Map.of(
					"title", event.title(),
					"body", event.body(),
					"path", event.path()));
		} catch (JacksonException exception) {
			LOGGER.error("Unable to serialize owner push notification.", exception);
			return;
		}

		for (StoredSubscription subscription : subscriptions) {
			try {
				PushService pushService = new PushService(publicKey, privateKey, subject);
				HttpResponse response = pushService.send(new Notification(
						subscription.endpoint(), subscription.p256dh(), subscription.auth(), payload, 3600));
				int status = response.getStatusLine().getStatusCode();
				if (status == 404 || status == 410) {
					jdbc.update("DELETE FROM owner_push_subscriptions WHERE endpoint = ?", subscription.endpoint());
				} else if (status < 200 || status >= 300) {
					LOGGER.warn("Owner push delivery returned HTTP status {}.", status);
				}
			} catch (Exception exception) {
				LOGGER.warn("Unable to deliver an owner push notification: {}.",
						exception.getClass().getSimpleName());
			}
		}
	}

	private void validateEndpoint(String endpoint) {
		try {
			URI uri = URI.create(endpoint);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			boolean allowedHost = ALLOWED_PUSH_HOSTS.contains(host)
					|| host.endsWith(".notify.windows.com");
			if (!"https".equalsIgnoreCase(uri.getScheme())
					|| !allowedHost
					|| uri.getUserInfo() != null
					|| (uri.getPort() != -1 && uri.getPort() != 443)
					|| uri.getRawPath() == null
					|| uri.getRawPath().isBlank()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid push subscription endpoint.");
			}
		} catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid push subscription endpoint.", exception);
		}
	}

	private record StoredSubscription(String endpoint, String p256dh, String auth) {
	}
}
