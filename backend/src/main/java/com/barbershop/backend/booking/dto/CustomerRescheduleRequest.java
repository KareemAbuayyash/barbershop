package com.barbershop.backend.booking.dto;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CustomerRescheduleRequest(
		@NotNull LocalDate preferredDate,
		Instant startsAt,
		@Size(max = 500) String reason) {
}
