package com.barbershop.backend.booking.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record QueueReorderRequest(LocalDate date, List<UUID> bookingIds) {
}
