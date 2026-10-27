package com.barbershop.backend.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.barbershop.backend.booking.dto.BookingManagementResponse;
import com.barbershop.backend.booking.dto.BookingPageResponse;
import com.barbershop.backend.booking.dto.ConfirmBookingRequest;
import com.barbershop.backend.booking.dto.QueueReorderRequest;

@Service
@Transactional(readOnly = true)
public class BookingManagementService {

	private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Hebron");
	private static final int MAX_PAGE_OFFSET = 100_000;
	private static final List<BookingStatus> ACTIVE_QUEUE_STATUSES =
			List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);

	private final BookingRepository bookingRepository;
	private final JdbcTemplate jdbcTemplate;

	public BookingManagementService(BookingRepository bookingRepository, JdbcTemplate jdbcTemplate) {
		this.bookingRepository = bookingRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	public BookingPageResponse listBookings(
			LocalDate date,
			BookingStatus status,
			BookingMode mode,
			int page,
			int size) {
		if (page < 0 || size < 1 || size > 100 || (long) page * size > MAX_PAGE_OFFSET) {
			throw badRequest(
					"Invalid pagination",
					"Page must be non-negative, size must be between 1 and 100, and the offset must not exceed 100000.");
		}

		Instant dayStart = date == null ? null : date.atStartOfDay(SHOP_ZONE).toInstant();
		Instant nextDayStart = date == null ? null : date.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
		Page<Booking> result = bookingRepository.findManagementBookings(
				status != null,
				status,
				mode != null,
				mode,
				date != null,
				date,
				dayStart,
				nextDayStart,
				PageRequest.of(page, size));
		return new BookingPageResponse(
				result.getContent().stream().map(BookingManagementService::toResponse).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	public BookingManagementResponse getBooking(UUID id) {
		return bookingRepository.findById(id)
				.map(BookingManagementService::toResponse)
				.orElseThrow(() -> notFound(id));
	}

	@Transactional
	public BookingManagementResponse confirm(UUID id, ConfirmBookingRequest request) {
		Booking initial = bookingRepository.findById(id)
				.orElseThrow(() -> notFound(id));
		lockQueueDateIfNeeded(initial);
		Booking booking = bookingRepository.findByIdForUpdate(id)
				.orElseThrow(() -> notFound(id));
		requireStatus(booking, BookingStatus.PENDING, "Only pending bookings can be confirmed.");

		Instant requestedStart = request == null ? null : request.startsAt();
		switch (booking.getBookingMode()) {
			case DAY_QUEUE, SAME_DAY_QUEUE -> {
				if (requestedStart != null) {
					throw badRequest("Invalid queue confirmation", "Queue bookings cannot be assigned an appointment time.");
				}
			}
			case EXACT_APPOINTMENT, LEGACY -> {
				if (booking.getStartsAt() == null) {
					throw conflict("Booking cannot be confirmed", "This booking has no requested appointment time.");
				}
				if (requestedStart != null && !requestedStart.equals(booking.getStartsAt())) {
					throw badRequest("Invalid appointment time", "Exact appointment confirmation must use its requested time.");
				}
				requireFutureStart(booking.getStartsAt());
			}
			case CONFIRMATION_REQUEST -> {
				Instant confirmedStart = requestedStart == null ? booking.getStartsAt() : requestedStart;
				if (confirmedStart == null) {
					throw badRequest(
							"Appointment time required",
							"A confirmation request without a proposed time must be given a start time.");
				}
				requireFutureStart(confirmedStart);
				booking.setStartsAt(confirmedStart);
			}
		}

		booking.setStatus(BookingStatus.CONFIRMED);
		return toResponse(bookingRepository.saveAndFlush(booking));
	}

	@Transactional
	public BookingManagementResponse cancel(UUID id) {
		Booking booking = findForLifecycleUpdate(id);
		if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
			throw conflict("Booking cannot be cancelled", "Only pending or confirmed bookings can be cancelled.");
		}
		booking.setStatus(BookingStatus.CANCELLED);
		return toResponse(bookingRepository.saveAndFlush(booking));
	}

	@Transactional
	public BookingManagementResponse complete(UUID id) {
		Booking booking = findForLifecycleUpdate(id);
		requireStatus(booking, BookingStatus.CONFIRMED, "Only confirmed bookings can be completed.");
		if (booking.getStartsAt() != null && booking.getStartsAt().isAfter(Instant.now())) {
			throw conflict(
					"Booking has not started",
					"A timed appointment cannot be completed before its scheduled start.");
		}
		booking.setStatus(BookingStatus.COMPLETED);
		return toResponse(bookingRepository.saveAndFlush(booking));
	}

	@Transactional
	public List<BookingManagementResponse> reorderQueue(QueueReorderRequest request) {
		if (request == null || request.date() == null || request.bookingIds() == null) {
			throw badRequest("Invalid queue reorder", "A queue date and ordered booking ID list are required.");
		}
		if (request.bookingIds().stream().anyMatch(Objects::isNull)) {
			throw badRequest("Invalid queue reorder", "Booking IDs must not contain null values.");
		}
		Set<UUID> requestedIds = new HashSet<>(request.bookingIds());
		if (requestedIds.size() != request.bookingIds().size()) {
			throw badRequest("Invalid queue reorder", "Booking IDs must not contain duplicates.");
		}

		lockQueueCounter(request.date());
		List<Booking> activeBookings =
				bookingRepository.findQueueBookingsForUpdate(request.date(), ACTIVE_QUEUE_STATUSES);
		Set<UUID> activeIds = new HashSet<>();
		for (Booking booking : activeBookings) {
			activeIds.add(booking.getId());
		}
		if (!activeIds.equals(requestedIds)) {
			throw badRequest(
					"Invalid queue reorder",
					"The ordered IDs must include every active booking for the selected date, and no other bookings.");
		}

		Integer maximumPosition = bookingRepository.findMaximumQueuePosition(request.date());
		long nextPosition = maximumPosition == null ? 1L : (long) maximumPosition + 1L;
		if (nextPosition + request.bookingIds().size() - 1L > Integer.MAX_VALUE) {
			throw conflict("Queue cannot be reordered", "The queue position limit has been reached.");
		}

		jdbcTemplate.execute("SET CONSTRAINTS uq_bookings_queue_date_position DEFERRED");
		Map<UUID, Booking> activeBookingsById = activeBookings.stream()
				.collect(java.util.stream.Collectors.toMap(Booking::getId, booking -> booking));
		List<Booking> ordered = request.bookingIds().stream()
				.map(activeBookingsById::get)
				.toList();
		for (int index = 0; index < ordered.size(); index++) {
			ordered.get(index).setQueuePosition(Math.toIntExact(nextPosition + index));
		}
		bookingRepository.flush();

		if (!ordered.isEmpty()) {
			jdbcTemplate.update("""
					UPDATE booking_queue_counters
					SET last_position = GREATEST(last_position, ?),
					    updated_at = CURRENT_TIMESTAMP
					WHERE queue_date = ?
					""",
					Math.toIntExact(nextPosition + ordered.size() - 1L),
					request.date());
		}
		return ordered.stream().map(BookingManagementService::toResponse).toList();
	}

	private Booking findForLifecycleUpdate(UUID id) {
		Booking initial = bookingRepository.findById(id)
				.orElseThrow(() -> notFound(id));
		lockQueueDateIfNeeded(initial);
		return bookingRepository.findByIdForUpdate(id)
				.orElseThrow(() -> notFound(id));
	}

	private void lockQueueDateIfNeeded(Booking booking) {
		if (booking.getQueueDate() != null) {
			lockQueueCounter(booking.getQueueDate());
		}
	}

	private void lockQueueCounter(LocalDate date) {
		jdbcTemplate.update("""
				INSERT INTO booking_queue_counters (queue_date, last_position)
				SELECT ?, COALESCE(MAX(queue_position), 0)
				FROM bookings
				WHERE queue_date = ?
				ON CONFLICT (queue_date) DO UPDATE
				SET last_position = GREATEST(
				        booking_queue_counters.last_position,
				        EXCLUDED.last_position
				    ),
				    updated_at = CURRENT_TIMESTAMP
				""",
				date,
				date);
		jdbcTemplate.queryForObject(
				"SELECT last_position FROM booking_queue_counters WHERE queue_date = ? FOR UPDATE",
				Integer.class,
				date);
	}

	private static void requireFutureStart(Instant startsAt) {
		if (!startsAt.isAfter(Instant.now())) {
			throw badRequest("Invalid appointment time", "Appointment time must be in the future.");
		}
	}

	private static void requireStatus(Booking booking, BookingStatus expected, String detail) {
		if (booking.getStatus() != expected) {
			throw conflict("Invalid booking state", detail);
		}
	}

	private static BookingManagementResponse toResponse(Booking booking) {
		return new BookingManagementResponse(
				booking.getId(),
				booking.getBookingMode(),
				booking.getStatus(),
				booking.getStartsAt(),
				booking.getQueueDate(),
				booking.getQueuePosition(),
				booking.getNotes(),
				booking.getService().getId(),
				booking.getServiceNameSnapshot(),
				booking.getServicePriceSnapshot(),
				booking.getServiceDurationSnapshotMinutes(),
				booking.getCustomer() == null ? null : booking.getCustomer().getName(),
				booking.getCustomer() == null ? null : booking.getCustomer().getPhoneNumber(),
				booking.getCreatedAt(),
				booking.getUpdatedAt());
	}

	private static BookingApiException notFound(UUID id) {
		return new BookingApiException(
				HttpStatus.NOT_FOUND,
				"Booking not found",
				"No booking exists with ID " + id + ".");
	}

	private static BookingApiException badRequest(String title, String detail) {
		return new BookingApiException(HttpStatus.BAD_REQUEST, title, detail);
	}

	private static BookingApiException conflict(String title, String detail) {
		return new BookingApiException(HttpStatus.CONFLICT, title, detail);
	}
}
