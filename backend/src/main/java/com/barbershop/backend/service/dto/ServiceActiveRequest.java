package com.barbershop.backend.service.dto;

import jakarta.validation.constraints.NotNull;

public record ServiceActiveRequest(@NotNull Boolean active) {
}
