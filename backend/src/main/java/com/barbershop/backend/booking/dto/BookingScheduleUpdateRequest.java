package com.barbershop.backend.booking.dto;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.Size;

public record BookingScheduleUpdateRequest(
		Instant startsAt,
		LocalDate preferredDate,
		LocalDate queueDate,
		@Size(max = 1000) String notes) {

	public BookingScheduleUpdateRequest(Instant startsAt, LocalDate queueDate, String notes) {
		this(startsAt, null, queueDate, notes);
	}
}
