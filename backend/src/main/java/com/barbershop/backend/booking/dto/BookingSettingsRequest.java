package com.barbershop.backend.booking.dto;

import jakarta.validation.constraints.NotNull;

public record BookingSettingsRequest(
		@NotNull Boolean exactAppointmentEnabled,
		@NotNull Boolean confirmationRequestEnabled,
		@NotNull Boolean dayQueueEnabled,
		@NotNull Boolean sameDayQueueEnabled,
		@NotNull Boolean newBookingsDisabled) {
}
