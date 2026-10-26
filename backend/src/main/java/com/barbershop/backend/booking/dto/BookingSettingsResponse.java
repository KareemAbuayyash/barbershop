package com.barbershop.backend.booking.dto;

import java.time.Instant;

public record BookingSettingsResponse(
		boolean exactAppointmentEnabled,
		boolean confirmationRequestEnabled,
		boolean dayQueueEnabled,
		boolean sameDayQueueEnabled,
		boolean newBookingsDisabled,
		Instant updatedAt) {
}
