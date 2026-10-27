package com.barbershop.backend.booking.dto;

import java.util.List;

public record BookingPageResponse(
		List<BookingManagementResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages) {
}
