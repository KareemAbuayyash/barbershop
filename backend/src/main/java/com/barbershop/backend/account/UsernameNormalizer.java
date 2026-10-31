package com.barbershop.backend.account;

import java.util.Locale;

public final class UsernameNormalizer {

	private UsernameNormalizer() {
	}

	public static String normalize(String username) {
		return username.trim().toLowerCase(Locale.ROOT);
	}
}
