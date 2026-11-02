package com.barbershop.backend.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.barbershop.backend.booking.BookingMode;
import com.barbershop.backend.booking.BookingStatus;

public record CustomerBookingResponse(
		UUID id,
		BookingMode bookingMode,
		BookingStatus status,
		Instant startsAt,
		LocalDate queueDate,
		Integer queuePosition,
	String notes,
	String ownerNote,
		String serviceName,
		BigDecimal servicePrice,
		int serviceDurationMinutes,
		Instant createdAt) {
}
