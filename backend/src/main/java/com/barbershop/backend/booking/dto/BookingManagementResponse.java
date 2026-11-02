package com.barbershop.backend.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.barbershop.backend.booking.BookingMode;
import com.barbershop.backend.booking.BookingStatus;

public record BookingManagementResponse(
		UUID id,
		BookingMode bookingMode,
		BookingStatus status,
		Instant startsAt,
		LocalDate preferredDate,
		LocalDate queueDate,
		Integer queuePosition,
		String notes,
		UUID serviceId,
		String serviceName,
		BigDecimal servicePrice,
		int serviceDurationMinutes,
		UUID employeeId,
		String customerName,
		String customerPhone,
		Instant createdAt,
		Instant updatedAt) {
}
