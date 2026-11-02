package com.barbershop.backend.booking;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.booking.dto.BookingManagementResponse;
import com.barbershop.backend.booking.dto.BookingPageResponse;
import com.barbershop.backend.booking.dto.BookingScheduleUpdateRequest;
import com.barbershop.backend.booking.dto.ConfirmBookingRequest;
import com.barbershop.backend.booking.dto.GuestBookingRequest;
import com.barbershop.backend.booking.dto.QueueReorderRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

	private final GuestBookingService guestBookingService;
	private final BookingManagementService bookingManagementService;
	private final ShopScheduleService shopScheduleService;

	public BookingController(
			GuestBookingService guestBookingService,
			BookingManagementService bookingManagementService,
			ShopScheduleService shopScheduleService) {
		this.guestBookingService = guestBookingService;
		this.bookingManagementService = bookingManagementService;
		this.shopScheduleService = shopScheduleService;
	}

	@GetMapping("/availability")
	public ShopScheduleService.Availability availability(
			@RequestParam LocalDate date,
			@RequestParam(required = false) UUID serviceId) {
		return shopScheduleService.availability(date, serviceId);
	}

	@GetMapping("/availability/{bookingId}")
	public ShopScheduleService.Availability bookingAvailability(
			@PathVariable UUID bookingId,
			@RequestParam LocalDate date,
			@RequestParam UUID serviceId) {
		return shopScheduleService.availabilityForBooking(date, serviceId, bookingId);
	}

	@PostMapping
	public ResponseEntity<?> createGuestBooking(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody GuestBookingRequest request,
			HttpServletRequest servletRequest,
			Authentication authentication) {
		String customerUsername = authentication != null && authentication.getAuthorities().stream()
				.anyMatch(authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))
						? authentication.getName()
						: null;
		BookingCreationResult result = guestBookingService.createBooking(
				idempotencyKey, request, servletRequest.getRemoteAddr(), customerUsername);
		ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
		if (result.location() != null) {
			response.header(HttpHeaders.LOCATION, result.location());
		}
		return response.body(result.body());
	}

	@GetMapping
	public BookingPageResponse listBookings(
			@RequestParam(required = false) LocalDate date,
			@RequestParam(required = false) LocalDate fromDate,
			@RequestParam(required = false) LocalDate toDate,
			@RequestParam(required = false) BookingStatus status,
			@RequestParam(required = false) BookingMode mode,
			@RequestParam(defaultValue = "false") boolean queueOnly,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		LocalDate effectiveFrom = fromDate == null ? date : fromDate;
		LocalDate effectiveTo = toDate == null ? date : toDate;
		if (date != null && (fromDate != null || toDate != null)
				|| effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
			throw new org.springframework.web.server.ResponseStatusException(
					org.springframework.http.HttpStatus.BAD_REQUEST,
					"Provide either a single date or a valid date range.");
		}
		return bookingManagementService.listBookings(date, effectiveFrom, effectiveTo, status, mode, queueOnly, q, page, size);
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

	@PutMapping("/{id}/schedule")
	public BookingManagementResponse updateSchedule(
			@PathVariable UUID id,
			@Valid @RequestBody BookingScheduleUpdateRequest request) {
		return bookingManagementService.updateSchedule(id, request);
	}

	@PatchMapping("/queue/reorder")
	public List<BookingManagementResponse> reorderQueue(@RequestBody QueueReorderRequest request) {
		return bookingManagementService.reorderQueue(request);
	}
}
