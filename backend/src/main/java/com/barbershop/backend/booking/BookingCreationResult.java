package com.barbershop.backend.booking;

import com.barbershop.backend.booking.dto.GuestBookingResponse;

public record BookingCreationResult(
		int status,
		String location,
		GuestBookingResponse body) {
}
