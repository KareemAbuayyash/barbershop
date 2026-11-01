package com.barbershop.backend.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerRegistrationRequest(
		@NotBlank
		@Size(min = 3, max = 64)
		@Pattern(regexp = "[A-Za-z0-9._-]+")
		String username,

		@NotBlank
		@Size(min = 2, max = 150)
		String displayName,

		@NotBlank
		@Size(min = 8, max = 32)
		@Pattern(regexp = "[0-9+()\\s-]+")
		String phoneNumber,

		@NotBlank
		@Size(min = 8, max = 72)
		String password) {
}
