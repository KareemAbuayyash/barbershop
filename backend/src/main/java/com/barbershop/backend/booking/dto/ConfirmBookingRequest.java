package com.barbershop.backend.booking.dto;

import java.time.Instant;

public record ConfirmBookingRequest(Instant startsAt) {
}
