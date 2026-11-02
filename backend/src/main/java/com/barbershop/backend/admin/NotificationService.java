package com.barbershop.backend.admin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.barbershop.backend.admin.NotificationDtos.NotificationCenter;
import com.barbershop.backend.admin.NotificationDtos.NotificationItem;
import com.barbershop.backend.admin.NotificationDtos.NotificationPreferences;

@Service
public class NotificationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);
	private static final List<String> EVENT_TYPES = List.of(
			"BOOKING",
			"BOOKING_UPDATE",
			"BOOKING_REMINDER",
			"QUEUE",
			"INVENTORY",
			"SALES",
			"PAYMENT",
			"FINANCE",
			"SERVICE",
			"SETTINGS",
			"DAILY_SUMMARY");
	private static final List<String> ALLOWED_PUSH_HOSTS = List.of(
			"fcm.googleapis.com",
			"updates.push.services.mozilla.com",
			"push.services.mozilla.com",
			"web.push.apple.com");
	private static final int MAX_DELIVERY_ATTEMPTS = 8;

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final String publicKey;
	private final String privateKey;
	private final String subject;

	public NotificationService(
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
	public void notifyOwner(String eventType, String eventKey, String title, String body, String path) {
		List<UUID> accountIds = jdbc.query("""
				SELECT id FROM management_accounts WHERE role = 'ROLE_OWNER' AND enabled = TRUE
				""", (rs, row) -> rs.getObject("id", UUID.class));
		for (UUID accountId : accountIds) {
			createNotification(new Recipient("OWNER", accountId), eventType, eventKey, title, body, path);
		}
	}

	@Transactional
	public void notifyCustomer(UUID accountId, String eventType, String eventKey, String title, String body, String path) {
		if (accountId == null) return;
		createNotification(new Recipient("CUSTOMER", accountId), eventType, eventKey, title, body, path);
	}

	@Transactional
	public void notifyBookingCustomer(
			UUID bookingId,
			String eventType,
			String eventKey,
			String title,
			String body,
			String path) {
		List<UUID> accounts = jdbc.query("""
				SELECT customer_account_id FROM bookings
				WHERE id = ? AND customer_account_id IS NOT NULL
				""", (rs, row) -> rs.getObject("customer_account_id", UUID.class), bookingId);
		for (UUID accountId : accounts) {
			notifyCustomer(accountId, eventType, eventKey, title, body, path);
		}
	}

	private void createNotification(
			Recipient recipient,
			String eventType,
			String eventKey,
			String title,
			String body,
			String path) {
		if (!EVENT_TYPES.contains(eventType)) {
			throw new IllegalArgumentException("Unsupported notification event type.");
		}
		if (eventKey == null || eventKey.isBlank() || eventKey.length() > 180) {
			throw new IllegalArgumentException("Notification event key must contain 1-180 characters.");
		}
		String safeTitle = limited(title, 150);
		String safeBody = limited(body, 500);
		String safePath = normalizePath(path);
		UUID notificationId = UUID.randomUUID();
		int inserted = jdbc.update("""
				INSERT INTO app_notifications (
				    id, recipient_type, owner_account_id, customer_account_id,
				    event_type, event_key, title, body, target_path
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT DO NOTHING
				""", notificationId, recipient.type(),
				recipient.type().equals("OWNER") ? recipient.id() : null,
				recipient.type().equals("CUSTOMER") ? recipient.id() : null,
				eventType, eventKey, safeTitle, safeBody, safePath);
		if (inserted == 0) return;

		jdbc.update("""
				INSERT INTO notification_push_deliveries (id, notification_id, subscription_id)
				SELECT gen_random_uuid(), ?, subscription.id
				FROM notification_subscriptions subscription
				LEFT JOIN notification_preferences preference
				  ON preference.event_type = ?
				 AND ((? = 'OWNER' AND preference.owner_account_id = ?)
				      OR (? = 'CUSTOMER' AND preference.customer_account_id = ?))
				WHERE subscription.enabled = TRUE
				  AND ((? = 'OWNER' AND subscription.owner_account_id = ?)
				       OR (? = 'CUSTOMER' AND subscription.customer_account_id = ?))
				  AND coalesce(preference.push_enabled, TRUE) = TRUE
				ON CONFLICT (notification_id, subscription_id) DO NOTHING
				""", notificationId, eventType, recipient.type(), recipient.id(), recipient.type(), recipient.id(),
				recipient.type(), recipient.id(), recipient.type(), recipient.id());
	}

	@Transactional(readOnly = true)
	public NotificationCenter listNotifications(String username, boolean owner, int page, int requestedSize) {
		int safePage = Math.max(page, 0);
		int size = Math.max(1, Math.min(requestedSize, 50));
		Recipient recipient = resolveRecipient(username, owner);
		String predicate = owner ? "owner_account_id = ?" : "customer_account_id = ?";
		long total = jdbc.queryForObject(
				"SELECT count(*) FROM app_notifications WHERE " + predicate,
				Long.class, recipient.id());
		long unread = jdbc.queryForObject(
				"SELECT count(*) FROM app_notifications WHERE " + predicate + " AND read_at IS NULL",
				Long.class, recipient.id());
		List<NotificationItem> content = jdbc.query("""
				SELECT id, event_type, title, body, target_path, created_at, read_at
				FROM app_notifications WHERE %s
				ORDER BY created_at DESC, id DESC
				OFFSET ? LIMIT ?
				""".formatted(predicate), (rs, row) -> new NotificationItem(
				rs.getObject("id", UUID.class),
				rs.getString("event_type"),
				rs.getString("title"),
				rs.getString("body"),
				rs.getString("target_path"),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()),
				recipient.id(), (long) safePage * size, size);
		return new NotificationCenter(content, unread, safePage, size, total,
				(int) Math.ceil((double) total / size));
	}

	@Transactional
	public void markRead(String username, boolean owner, UUID notificationId) {
		Recipient recipient = resolveRecipient(username, owner);
		String predicate = owner ? "owner_account_id = ?" : "customer_account_id = ?";
		int updated = jdbc.update("""
				UPDATE app_notifications SET read_at = coalesce(read_at, CURRENT_TIMESTAMP)
				WHERE id = ? AND %s
				""".formatted(predicate), notificationId, recipient.id());
		if (updated == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found.");
		}
	}

	@Transactional
	public void markAllRead(String username, boolean owner) {
		Recipient recipient = resolveRecipient(username, owner);
		String predicate = owner ? "owner_account_id = ?" : "customer_account_id = ?";
		jdbc.update("""
				UPDATE app_notifications SET read_at = CURRENT_TIMESTAMP
				WHERE %s AND read_at IS NULL
				""".formatted(predicate), recipient.id());
	}

	@Transactional(readOnly = true)
	public NotificationPreferences preferences(String username, boolean owner) {
		Recipient recipient = resolveRecipient(username, owner);
		Map<String, Boolean> preferences = new LinkedHashMap<>();
		EVENT_TYPES.forEach(type -> preferences.put(type, Boolean.TRUE));
		String predicate = owner ? "owner_account_id = ?" : "customer_account_id = ?";
		jdbc.query("""
				SELECT event_type, push_enabled FROM notification_preferences WHERE %s
				""".formatted(predicate),
				(rs, row) -> Map.entry(rs.getString("event_type"), rs.getBoolean("push_enabled")),
				recipient.id())
				.forEach(entry -> preferences.put(entry.getKey(), entry.getValue()));
		return new NotificationPreferences(preferences);
	}

	@Transactional
	public NotificationPreferences updatePreferences(
			String username,
			boolean owner,
			NotificationPreferences request) {
		if (request == null || request.preferences() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notification preferences are required.");
		}
		Recipient recipient = resolveRecipient(username, owner);
		for (Map.Entry<String, Boolean> preference : request.preferences().entrySet()) {
			if (!EVENT_TYPES.contains(preference.getKey()) || preference.getValue() == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification preference.");
			}
			if (owner) {
				jdbc.update("""
						INSERT INTO notification_preferences (owner_account_id, event_type, push_enabled)
						VALUES (?, ?, ?)
						ON CONFLICT (owner_account_id, event_type) WHERE owner_account_id IS NOT NULL
						DO UPDATE SET push_enabled = EXCLUDED.push_enabled, updated_at = CURRENT_TIMESTAMP
						""", recipient.id(), preference.getKey(), preference.getValue());
			} else {
				jdbc.update("""
						INSERT INTO notification_preferences (customer_account_id, event_type, push_enabled)
						VALUES (?, ?, ?)
						ON CONFLICT (customer_account_id, event_type) WHERE customer_account_id IS NOT NULL
						DO UPDATE SET push_enabled = EXCLUDED.push_enabled, updated_at = CURRENT_TIMESTAMP
						""", recipient.id(), preference.getKey(), preference.getValue());
			}
		}
		return preferences(username, owner);
	}

	@Transactional
	public void saveSubscription(String username, boolean owner, String endpoint, String p256dh, String auth) {
		validateEndpointAndKeys(endpoint, p256dh, auth);
		Recipient recipient = resolveRecipient(username, owner);
		UUID subscriptionId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO notification_subscriptions (
				    id, endpoint, p256dh_key, auth_key, owner_account_id, customer_account_id, enabled
				)
				VALUES (?, ?, ?, ?, ?, ?, TRUE)
				ON CONFLICT (endpoint) DO NOTHING
				""", subscriptionId, endpoint, p256dh, auth,
				owner ? recipient.id() : null, owner ? null : recipient.id());
		int updated = jdbc.update("""
				UPDATE notification_subscriptions
				SET p256dh_key = ?, auth_key = ?, enabled = TRUE, updated_at = CURRENT_TIMESTAMP
				WHERE endpoint = ?
				  AND ((? = TRUE AND owner_account_id = ?)
				       OR (? = FALSE AND customer_account_id = ?))
				""", p256dh, auth, endpoint, owner, recipient.id(), owner, recipient.id());
		if (updated == 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This push subscription belongs to another account.");
		}
	}

	@Transactional
	public void removeSubscription(String username, boolean owner, String endpoint) {
		validateEndpoint(endpoint);
		Recipient recipient = resolveRecipient(username, owner);
		int updated = jdbc.update("""
				DELETE FROM notification_subscriptions
				WHERE endpoint = ?
				  AND ((? = TRUE AND owner_account_id = ?)
				       OR (? = FALSE AND customer_account_id = ?))
				""", endpoint, owner, recipient.id(), owner, recipient.id());
		if (updated == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Push subscription not found for this account.");
		}
	}

	@Transactional
	public void setSubscriptionEnabled(String username, boolean owner, String endpoint, boolean enabled) {
		validateEndpoint(endpoint);
		Recipient recipient = resolveRecipient(username, owner);
		int updated = jdbc.update("""
				UPDATE notification_subscriptions
				SET enabled = ?, updated_at = CURRENT_TIMESTAMP
				WHERE endpoint = ?
				  AND ((? = TRUE AND owner_account_id = ?)
				       OR (? = FALSE AND customer_account_id = ?))
				""", enabled, endpoint, owner, recipient.id(), owner, recipient.id());
		if (updated == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Push subscription not found for this account.");
		}
		if (!enabled) {
			jdbc.update("""
					UPDATE notification_push_deliveries delivery
					SET delivered_at = CURRENT_TIMESTAMP, claimed_until = NULL
					FROM notification_subscriptions subscription
					WHERE delivery.subscription_id = subscription.id
					  AND subscription.endpoint = ?
					  AND delivery.delivered_at IS NULL
					""", endpoint);
		}
	}

	@Transactional(readOnly = true)
	public boolean subscriptionEnabled(String username, boolean owner, String endpoint) {
		validateEndpoint(endpoint);
		Recipient recipient = resolveRecipient(username, owner);
		Boolean enabled = jdbc.query("""
				SELECT enabled FROM notification_subscriptions
				WHERE endpoint = ?
				  AND ((? = TRUE AND owner_account_id = ?)
				       OR (? = FALSE AND customer_account_id = ?))
				""", (rs, row) -> rs.getBoolean("enabled"), endpoint, owner, recipient.id(), owner, recipient.id())
				.stream()
				.findFirst()
				.orElse(false);
		return enabled;
	}

	@Scheduled(cron = "0 0 * * * *", zone = "Asia/Hebron")
	@Transactional
	public void notifyUpcomingBookings() {
		List<Reminder> reminders = jdbc.query("""
				SELECT booking.id, booking.customer_name, booking.service_name_snapshot,
				       booking.starts_at
				FROM bookings booking
				JOIN customers customer ON customer.id = booking.customer_id
				WHERE booking.status = 'CONFIRMED'
				  AND booking.customer_account_id IS NOT NULL
				  AND booking.starts_at >= CURRENT_TIMESTAMP
				  AND booking.starts_at < CURRENT_TIMESTAMP + INTERVAL '24 hours'
				ORDER BY booking.starts_at
				""", (rs, row) -> new Reminder(
				rs.getObject("id", UUID.class),
				rs.getString("customer_name"),
				rs.getString("service_name_snapshot"),
				rs.getTimestamp("starts_at").toInstant()));
		for (Reminder reminder : reminders) {
			String eventKey = "booking:" + reminder.id() + ":reminder-24h";
			String appointmentTime = java.time.format.DateTimeFormatter
					.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("ar"))
					.format(reminder.startsAt().atZone(java.time.ZoneId.of("Asia/Hebron")));
			String body = reminder.serviceName() + " · " + appointmentTime;
			notifyOwner("BOOKING_REMINDER", eventKey, "تذكير بموعد قادم",
					reminder.customerName() + " · " + body, "/owner/bookings");
			notifyBookingCustomer(reminder.id(), "BOOKING_REMINDER", eventKey,
					"تذكير بموعدك", body, "/my-bookings");
		}
	}

	@Scheduled(cron = "0 5 0 * * *", zone = "Asia/Hebron")
	@Transactional
	public void sendDailyOwnerSummary() {
		java.time.LocalDate yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Hebron")).minusDays(1);
		java.time.Instant start = yesterday.atStartOfDay(java.time.ZoneId.of("Asia/Hebron")).toInstant();
		java.time.Instant end = yesterday.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Hebron")).toInstant();
		Long bookings = jdbc.queryForObject("""
				SELECT count(*) FROM bookings
				WHERE created_at >= ? AND created_at < ?
				""", Long.class, start, end);
		java.math.BigDecimal collected = jdbc.queryForObject("""
				SELECT coalesce(sum(amount), 0) FROM booking_payments
				WHERE paid_at >= ? AND paid_at < ?
				""", java.math.BigDecimal.class, start, end);
		notifyOwner("DAILY_SUMMARY", "daily-summary:" + yesterday,
				"ملخص المحل · " + yesterday,
				"الحجوزات " + bookings + " · التحصيل " + collected.toPlainString() + " ₪",
				"/owner/reports");
	}

	@Scheduled(fixedDelay = 30_000)
	public void deliverPendingPushNotifications() {
		if (publicKey.isBlank() || privateKey.isBlank()) return;
		for (Delivery delivery : claimDueDeliveries()) {
			deliver(delivery);
		}
	}

	private List<Delivery> claimDueDeliveries() {
		List<UUID> ids = jdbc.query("""
				SELECT id FROM notification_push_deliveries
				WHERE delivered_at IS NULL
				  AND attempts < ?
				  AND next_attempt_at <= CURRENT_TIMESTAMP
				  AND (claimed_until IS NULL OR claimed_until < CURRENT_TIMESTAMP)
				ORDER BY next_attempt_at, created_at
				LIMIT 20
				""", (rs, row) -> rs.getObject("id", UUID.class), MAX_DELIVERY_ATTEMPTS);
		java.util.ArrayList<Delivery> claimed = new java.util.ArrayList<>();
		for (UUID id : ids) {
			int updated = jdbc.update("""
					UPDATE notification_push_deliveries
					SET claimed_until = CURRENT_TIMESTAMP + INTERVAL '2 minutes',
					    attempts = attempts + 1
					WHERE id = ? AND delivered_at IS NULL
					  AND (claimed_until IS NULL OR claimed_until < CURRENT_TIMESTAMP)
					""", id);
			if (updated == 0) continue;
			List<Delivery> rows = jdbc.query("""
					SELECT delivery.id, delivery.attempts, notification.title, notification.body,
					       notification.target_path, subscription.endpoint,
					       subscription.p256dh_key, subscription.auth_key
					FROM notification_push_deliveries delivery
					JOIN app_notifications notification ON notification.id = delivery.notification_id
					JOIN notification_subscriptions subscription ON subscription.id = delivery.subscription_id
					WHERE delivery.id = ? AND subscription.enabled = TRUE
					""", (rs, row) -> new Delivery(
					rs.getObject("id", UUID.class),
					rs.getInt("attempts"),
					rs.getString("title"),
					rs.getString("body"),
					rs.getString("target_path"),
					rs.getString("endpoint"),
					rs.getString("p256dh_key"),
					rs.getString("auth_key")), id);
			if (rows.isEmpty()) {
				jdbc.update("UPDATE notification_push_deliveries SET delivered_at = CURRENT_TIMESTAMP WHERE id = ?", id);
			} else {
				claimed.add(rows.get(0));
			}
		}
		return claimed;
	}

	private void deliver(Delivery delivery) {
		try {
			String payload = objectMapper.writeValueAsString(Map.of(
					"title", delivery.title(),
					"body", delivery.body(),
					"path", delivery.targetPath()));
			PushService pushService = new PushService(publicKey, privateKey, subject);
			HttpResponse response = pushService.send(new Notification(
					delivery.endpoint(), delivery.p256dh(), delivery.auth(),
					payload.getBytes(StandardCharsets.UTF_8), 3600));
			int status = response.getStatusLine().getStatusCode();
			if (status >= 200 && status < 300) {
				jdbc.update("""
						UPDATE notification_push_deliveries
						SET delivered_at = CURRENT_TIMESTAMP, claimed_until = NULL, last_error = NULL
						WHERE id = ?
						""", delivery.id());
			} else if (status == 404 || status == 410) {
				jdbc.update("DELETE FROM notification_subscriptions WHERE endpoint = ?", delivery.endpoint());
			} else {
				retry(delivery, "HTTP_" + status);
			}
		} catch (Exception exception) {
			String error = exception instanceof JacksonException
					? "PAYLOAD_SERIALIZATION"
					: exception.getClass().getSimpleName();
			retry(delivery, error);
			LOGGER.warn("Push delivery {} failed: {}.", delivery.id(), error);
		}
	}

	private void retry(Delivery delivery, String error) {
		if (delivery.attempts() >= MAX_DELIVERY_ATTEMPTS) {
			jdbc.update("""
					UPDATE notification_push_deliveries
					SET claimed_until = NULL, last_error = ?
					WHERE id = ?
					""", error, delivery.id());
			return;
		}
		long delaySeconds = Math.min(3600, 30L << Math.min(delivery.attempts() - 1, 7));
		jdbc.update("""
				UPDATE notification_push_deliveries
				SET claimed_until = NULL,
				    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
				    last_error = ?
				WHERE id = ?
				""", delaySeconds, error, delivery.id());
	}

	private Recipient resolveRecipient(String username, boolean owner) {
		String table = owner ? "management_accounts" : "customer_accounts";
		String roleClause = owner ? " AND role = 'ROLE_OWNER'" : "";
		List<UUID> ids = jdbc.query(
				"SELECT id FROM " + table + " WHERE username_normalized = ? AND enabled = TRUE" + roleClause,
				(rs, row) -> rs.getObject("id", UUID.class), username);
		if (ids.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Notification account is unavailable.");
		}
		return new Recipient(owner ? "OWNER" : "CUSTOMER", ids.get(0));
	}

	private void validateEndpointAndKeys(String endpoint, String p256dh, String auth) {
		validateEndpoint(endpoint);
		try {
			byte[] publicKeyBytes = Base64.getUrlDecoder().decode(p256dh);
			byte[] authBytes = Base64.getUrlDecoder().decode(auth);
			if (publicKeyBytes.length != 65 || authBytes.length != 16) {
				throw new IllegalArgumentException("Unexpected push key length.");
			}
		} catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid push subscription keys.", exception);
		}
	}

	private void validateEndpoint(String endpoint) {
		try {
			URI uri = URI.create(endpoint);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			boolean allowedHost = ALLOWED_PUSH_HOSTS.contains(host) || host.endsWith(".notify.windows.com");
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

	private String normalizePath(String path) {
		if (path == null || !path.startsWith("/") || path.startsWith("//")
				|| path.contains("\\") || path.length() > 300) {
			throw new IllegalArgumentException("Notification target must be an internal path.");
		}
		return path;
	}

	private String limited(String value, int maxLength) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Notification text is required.");
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

	private record Recipient(String type, UUID id) {
	}

	private record Delivery(
			UUID id,
			int attempts,
			String title,
			String body,
			String targetPath,
			String endpoint,
			String p256dh,
			String auth) {
	}

	private record Reminder(UUID id, String customerName, String serviceName, java.time.Instant startsAt) {
	}
}

