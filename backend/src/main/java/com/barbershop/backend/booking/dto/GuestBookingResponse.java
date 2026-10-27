package com.barbershop.backend.booking.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.barbershop.backend.booking.BookingMode;
import com.barbershop.backend.booking.BookingStatus;

public record GuestBookingResponse(
		UUID bookingId,
		BookingMode bookingMode,
		BookingStatus status,
		Instant startsAt,
		LocalDate queueDate,
		Integer queuePosition,
		Instant createdAt) {
}
