package com.barbershop.backend.security;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

	private final RateLimitProperties properties;
	private final Map<String, Bucket> buckets = new LinkedHashMap<>();

	public RateLimitService(RateLimitProperties properties) {
		this.properties = properties;
	}

	public synchronized void assertLoginAllowed(String remoteAddress) {
		assertAllowed("login", remoteAddress, properties.getLoginMaxFailures());
	}

	public synchronized void recordLoginFailure(String remoteAddress) {
		record("login", remoteAddress, properties.getLoginMaxFailures(), properties.getLoginWindow());
	}

	public synchronized void clearLoginFailures(String remoteAddress) {
		buckets.remove(key("login", remoteAddress));
	}

	public synchronized void consumeRegistrationAttempt(String remoteAddress) {
		consume(
				"registration",
				remoteAddress,
				properties.getRegistrationMaxAttempts(),
				properties.getRegistrationWindow());
	}

	public synchronized void consumeBookingAttempt(String remoteAddress) {
		consume("booking", remoteAddress, properties.getBookingMaxAttempts(), properties.getBookingWindow());
	}

	private void assertAllowed(String scope, String address, int limit) {
		long now = System.currentTimeMillis();
		cleanupExpired(now);
		Bucket bucket = buckets.get(key(scope, address));
		if (bucket != null && bucket.count >= limit) {
			throw limited(bucket.expiresAtMillis, now);
		}
	}

	private void record(String scope, String address, int limit, Duration window) {
		long now = System.currentTimeMillis();
		cleanupExpired(now);
		String key = key(scope, address);
		Bucket bucket = buckets.get(key);
		if (bucket == null) {
			bucket = new Bucket(now + window.toMillis());
			putBounded(key, bucket);
		}
		bucket.count = Math.min(bucket.count + 1, limit);
	}

	private void consume(String scope, String address, int limit, Duration window) {
		long now = System.currentTimeMillis();
		cleanupExpired(now);
		String key = key(scope, address);
		Bucket bucket = buckets.get(key);
		if (bucket != null && bucket.count >= limit) {
			throw limited(bucket.expiresAtMillis, now);
		}
		if (bucket == null) {
			bucket = new Bucket(now + window.toMillis());
			putBounded(key, bucket);
		}
		bucket.count++;
	}

	private void putBounded(String key, Bucket bucket) {
		int maximum = Math.max(1, properties.getMaxTrackedAddresses());
		if (buckets.size() >= maximum) {
			Iterator<String> oldest = buckets.keySet().iterator();
			if (oldest.hasNext()) {
				buckets.remove(oldest.next());
			}
		}
		buckets.put(key, bucket);
	}

	private void cleanupExpired(long now) {
		buckets.values().removeIf(bucket -> bucket.expiresAtMillis <= now);
	}

	private static RateLimitExceededException limited(long expiresAtMillis, long now) {
		long retrySeconds = Math.max(1, (expiresAtMillis - now + 999) / 1000);
		return new RateLimitExceededException(retrySeconds);
	}

	private static String key(String scope, String address) {
		return scope + ":" + (address == null || address.isBlank() ? "unknown" : address);
	}

	private static final class Bucket {
		private final long expiresAtMillis;
		private int count;

		private Bucket(long expiresAtMillis) {
			this.expiresAtMillis = expiresAtMillis;
		}
	}
}
