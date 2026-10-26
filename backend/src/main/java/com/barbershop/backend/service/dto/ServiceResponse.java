package com.barbershop.backend.service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
		UUID id,
		String name,
		String description,
		BigDecimal price,
		int durationMinutes,
		boolean active,
		Instant createdAt) {
}
