package com.barbershop.backend.booking;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.barbershop.backend.booking.dto.BookingSettingsRequest;
import com.barbershop.backend.booking.dto.BookingSettingsResponse;

@Service
@Transactional(readOnly = true)
public class BookingSettingsService {

	private final BookingSettingsRepository bookingSettingsRepository;

	public BookingSettingsService(BookingSettingsRepository bookingSettingsRepository) {
		this.bookingSettingsRepository = bookingSettingsRepository;
	}

	public BookingSettingsResponse getCurrentSettings() {
		return toResponse(requireCurrentSettings());
	}

	@Transactional
	public BookingSettingsResponse updateSettings(BookingSettingsRequest request) {
		BookingSettings settings = requireCurrentSettings();
		settings.setExactAppointmentEnabled(request.exactAppointmentEnabled());
		settings.setConfirmationRequestEnabled(request.confirmationRequestEnabled());
		settings.setDayQueueEnabled(request.dayQueueEnabled());
		settings.setSameDayQueueEnabled(request.sameDayQueueEnabled());
		settings.setNewBookingsDisabled(request.newBookingsDisabled());
		return toResponse(bookingSettingsRepository.saveAndFlush(settings));
	}

	private BookingSettings requireCurrentSettings() {
		return bookingSettingsRepository.findBySingletonKeyTrue()
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.SERVICE_UNAVAILABLE,
						"Booking settings are not initialized."));
	}

	private static BookingSettingsResponse toResponse(BookingSettings settings) {
		return new BookingSettingsResponse(
				settings.isExactAppointmentEnabled(),
				settings.isConfirmationRequestEnabled(),
				settings.isDayQueueEnabled(),
				settings.isSameDayQueueEnabled(),
				settings.isNewBookingsDisabled(),
				settings.getUpdatedAt());
	}
}
