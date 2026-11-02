package com.barbershop.backend.booking.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.barbershop.backend.booking.BookingMode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GuestBookingRequest(
		@NotNull BookingMode bookingMode,
		@NotNull UUID serviceId,
		@NotBlank @Size(max = 150) String customerName,
		@Size(max = 32) String customerPhone,
		Instant startsAt,
		LocalDate preferredDate,
		LocalDate queueDate,
		String notes,
		boolean bookForSomeoneElse) {

	public GuestBookingRequest(
			BookingMode bookingMode,
			UUID serviceId,
			String customerName,
			String customerPhone,
			Instant startsAt,
			LocalDate queueDate,
			String notes,
			boolean bookForSomeoneElse) {
		this(bookingMode, serviceId, customerName, customerPhone, startsAt, null, queueDate, notes, bookForSomeoneElse);
	}

	public GuestBookingRequest(
			BookingMode bookingMode,
			UUID serviceId,
			String customerName,
			String customerPhone,
			Instant startsAt,
			LocalDate queueDate,
			String notes) {
		this(bookingMode, serviceId, customerName, customerPhone, startsAt, null, queueDate, notes, false);
	}
}
