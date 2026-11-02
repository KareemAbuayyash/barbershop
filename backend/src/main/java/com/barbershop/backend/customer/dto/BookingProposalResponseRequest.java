package com.barbershop.backend.customer.dto;

import jakarta.validation.constraints.NotNull;

public record BookingProposalResponseRequest(@NotNull Boolean accepted) {
}

