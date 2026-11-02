package com.barbershop.backend.service.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ServiceRequest(
		@NotBlank
		@Size(min = 2, max = 150)
		String name,

		@Size(max = 10000)
		String description,

		@NotNull
		@DecimalMin("0.00")
		@Digits(integer = 8, fraction = 2)
		BigDecimal price,

		@NotNull
		@Positive
		Integer durationMinutes) {

	public ServiceRequest {
		if (name != null) {
			name = name.trim();
		}
		description = description == null ? "" : description.trim();
	}
}
