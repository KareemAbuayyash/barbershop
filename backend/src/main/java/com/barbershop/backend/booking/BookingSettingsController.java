package com.barbershop.backend.booking;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.booking.dto.BookingSettingsRequest;
import com.barbershop.backend.booking.dto.BookingSettingsResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/booking-settings")
public class BookingSettingsController {

	private final BookingSettingsService bookingSettingsService;

	public BookingSettingsController(BookingSettingsService bookingSettingsService) {
		this.bookingSettingsService = bookingSettingsService;
	}

	@GetMapping
	public BookingSettingsResponse getCurrentSettings() {
		return bookingSettingsService.getCurrentSettings();
	}

	@PutMapping
	public BookingSettingsResponse updateSettings(
			@Valid @RequestBody BookingSettingsRequest request) {
		return bookingSettingsService.updateSettings(request);
	}
}
