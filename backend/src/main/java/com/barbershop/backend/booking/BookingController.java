package com.barbershop.backend.booking;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.booking.dto.BookingManagementResponse;
import com.barbershop.backend.booking.dto.BookingPageResponse;
import com.barbershop.backend.booking.dto.ConfirmBookingRequest;
import com.barbershop.backend.booking.dto.GuestBookingRequest;
import com.barbershop.backend.booking.dto.QueueReorderRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

	private final GuestBookingService guestBookingService;
	private final BookingManagementService bookingManagementService;

	public BookingController(
			GuestBookingService guestBookingService,
			BookingManagementService bookingManagementService) {
		this.guestBookingService = guestBookingService;
		this.bookingManagementService = bookingManagementService;
	}

	@PostMapping
	public ResponseEntity<?> createGuestBooking(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody GuestBookingRequest request) {
		BookingCreationResult result = guestBookingService.createBooking(idempotencyKey, request);
		ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
		if (result.location() != null) {
			response.header(HttpHeaders.LOCATION, result.location());
		}
		return response.body(result.body());
	}

	@GetMapping
	public BookingPageResponse listBookings(
			@RequestParam(required = false) LocalDate date,
			@RequestParam(required = false) BookingStatus status,
			@RequestParam(required = false) BookingMode mode,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return bookingManagementService.listBookings(date, status, mode, page, size);
	}

	@GetMapping("/{id}")
	public BookingManagementResponse getBooking(@PathVariable UUID id) {
		return bookingManagementService.getBooking(id);
	}

	@PatchMapping("/{id}/confirm")
	public BookingManagementResponse confirm(
			@PathVariable UUID id,
			@RequestBody(required = false) ConfirmBookingRequest request) {
		return bookingManagementService.confirm(id, request);
	}

	@PatchMapping("/{id}/cancel")
	public BookingManagementResponse cancel(@PathVariable UUID id) {
		return bookingManagementService.cancel(id);
	}

	@PatchMapping("/{id}/complete")
	public BookingManagementResponse complete(@PathVariable UUID id) {
		return bookingManagementService.complete(id);
	}

	@PatchMapping("/queue/reorder")
	public List<BookingManagementResponse> reorderQueue(@RequestBody QueueReorderRequest request) {
		return bookingManagementService.reorderQueue(request);
	}
}
