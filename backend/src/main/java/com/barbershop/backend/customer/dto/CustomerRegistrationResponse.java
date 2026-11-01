package com.barbershop.backend.customer.dto;

import java.util.UUID;

public record CustomerRegistrationResponse(UUID id, String username, String role) {
}
