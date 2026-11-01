package com.barbershop.backend.account;

import org.springframework.jdbc.core.JdbcTemplate;

public final class AccountWriteLock {

	private static final long LOCK_ID = 704_112_903_221L;

	private AccountWriteLock() {
	}

	public static void acquire(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + LOCK_ID + ")");
	}
}
