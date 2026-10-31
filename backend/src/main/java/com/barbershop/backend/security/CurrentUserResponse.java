package com.barbershop.backend.security;

import java.util.List;

public record CurrentUserResponse(String username, List<String> authorities) {
}
