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

import com.barbershop.backend.admin.NotificationService;
import com.barbershop.backend.booking.dto.BookingManagementResponse;
import com.barbershop.backend.booking.dto.BookingPageResponse;
import com.barbershop.backend.booking.dto.BookingScheduleUpdateRequest;
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
	private final ShopScheduleService shopScheduleService;
	private final NotificationService notificationService;

	public BookingManagementService(
			BookingRepository bookingRepository,
			JdbcTemplate jdbcTemplate,
			ShopScheduleService shopScheduleService,
			NotificationService notificationService) {
		this.bookingRepository = bookingRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.shopScheduleService = shopScheduleService;
		this.notificationService = notificationService;
	}

	public BookingPageResponse listBookings(
			LocalDate date,
			LocalDate fromDate,
			LocalDate toDate,
			BookingStatus status,
			boolean activeOnly,
			BookingMode mode,
			boolean queueOnly,
			String query,
			int page,
			int size) {
		if (page < 0 || size < 1 || size > 100 || (long) page * size > MAX_PAGE_OFFSET) {
			throw badRequest(
					"Invalid pagination",
					"Page must be non-negative, size must be between 1 and 100, and the offset must not exceed 100000.");
		}

		String normalizedQuery = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
		if (normalizedQuery.length() > 150) {
			throw badRequest("Invalid search query", "Search text must not exceed 150 characters.");
		}
		String searchPattern = "%" + escapeLike(normalizedQuery) + "%";
		String phonePattern = "%" + normalizedQuery.replaceAll("[^0-9]", "") + "%";
		LocalDate safeDate = date == null ? LocalDate.of(1, 1, 1) : date;
		LocalDate safeFromDate = fromDate == null ? LocalDate.of(1, 1, 1) : fromDate;
		LocalDate safeToDate = toDate == null ? LocalDate.of(9999, 12, 31) : toDate;
		Page<Booking> result = bookingRepository.findManagementBookings(
				status != null,
				status,
				activeOnly,
				mode != null,
				mode,
				queueOnly,
				List.of(BookingMode.DAY_QUEUE, BookingMode.SAME_DAY_QUEUE),
				!normalizedQuery.isBlank(),
				searchPattern,
				phonePattern,
				date != null,
				safeDate,
				safeDate.atStartOfDay(SHOP_ZONE).toInstant(),
				safeDate.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant(),
				fromDate != null,
				safeFromDate,
				safeFromDate.atStartOfDay(SHOP_ZONE).toInstant(),
				toDate != null,
				safeToDate,
				safeToDate.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant(),
				PageRequest.of(page, size));
		return new BookingPageResponse(
				result.getContent().stream().map(BookingManagementService::toResponse).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
				shopScheduleService.requireOpenDate(booking.getQueueDate());
			}
			case EXACT_APPOINTMENT, LEGACY -> {
				if (booking.getStartsAt() == null) {
					throw conflict("Booking cannot be confirmed", "This booking has no requested appointment time.");
				}
				if (requestedStart != null && !requestedStart.equals(booking.getStartsAt())) {
					throw badRequest("Invalid appointment time", "Exact appointment confirmation must use its requested time.");
				}
				requireFutureStart(booking.getStartsAt());
				shopScheduleService.requireAppointmentTime(
						booking.getStartsAt(), booking.getServiceDurationSnapshotMinutes(), booking.getId());
			}
			case CONFIRMATION_REQUEST -> {
				Instant confirmedStart = requestedStart == null ? booking.getStartsAt() : requestedStart;
				if (confirmedStart == null) {
					throw badRequest(
							"Appointment time required",
							"A confirmation request without a proposed time must be given a start time.");
				}
				requireFutureStart(confirmedStart);
				shopScheduleService.requireAppointmentTime(
						confirmedStart, booking.getServiceDurationSnapshotMinutes(), booking.getId());
				booking.setStartsAt(confirmedStart);
			}
		}

		booking.setStatus(BookingStatus.CONFIRMED);
		Booking saved = bookingRepository.saveAndFlush(booking);
		notificationService.notifyBookingConfirmed(saved.getId());
		return toResponse(saved);
	}

	@Transactional
	public BookingManagementResponse cancel(UUID id) {
		Booking booking = findForLifecycleUpdate(id);
		if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
			throw conflict("Booking cannot be cancelled", "Only pending or confirmed bookings can be cancelled.");
		}
		BookingStatus previousStatus = booking.getStatus();
		LocalDate queueDate = booking.getQueueDate();
		if (queueDate != null) {
			jdbcTemplate.execute("SET CONSTRAINTS uq_bookings_queue_date_position DEFERRED");
			booking.setQueuePosition(null);
		}
		booking.setStatus(BookingStatus.CANCELLED);
		Booking saved = bookingRepository.saveAndFlush(booking);
		if (queueDate != null) compactQueue(queueDate);
		if (previousStatus == BookingStatus.PENDING) {
			notificationService.notifyBookingRejected(saved.getId());
		} else {
			notificationService.notifyBookingCancelled(saved.getId());
		}
		return toResponse(saved);
	}

	@Transactional
	public BookingManagementResponse updateSchedule(UUID id, BookingScheduleUpdateRequest request) {
		Booking booking = findForLifecycleUpdate(id);
		if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
			throw conflict("Booking cannot be edited", "Only pending or confirmed bookings can be edited.");
		}
		Instant previousStartsAt = booking.getStartsAt();
		LocalDate previousPreferredDate = booking.getPreferredDate();
		LocalDate previousQueueDate = booking.getQueueDate();
		Integer previousQueuePosition = booking.getQueuePosition();
		String previousOwnerNote = booking.getOwnerNote();

		switch (booking.getBookingMode()) {
			case EXACT_APPOINTMENT, LEGACY -> {
				if (request.startsAt() == null || request.preferredDate() != null || request.queueDate() != null) {
					throw badRequest("Invalid appointment fields", "An appointment requires a start time and no queue date.");
				}
				requireFutureStart(request.startsAt());
				shopScheduleService.requireAppointmentTime(
						request.startsAt(), booking.getServiceDurationSnapshotMinutes(), booking.getId());
				booking.setStartsAt(request.startsAt());
			}
			case CONFIRMATION_REQUEST -> {
				if (request.queueDate() != null) {
					throw badRequest("Invalid confirmation request", "Confirmation requests must not include a queue date.");
				}
				if (booking.getStatus() == BookingStatus.CONFIRMED && request.startsAt() == null) {
					throw badRequest("Appointment time required", "A confirmed appointment must keep a scheduled start time.");
				}
				if (request.preferredDate() != null
						&& request.preferredDate().isBefore(LocalDate.now(SHOP_ZONE))) {
					throw badRequest("Invalid preferred date", "Preferred date must not be before the current shop-local date.");
				}
				if (request.preferredDate() != null && request.startsAt() == null
						&& !request.preferredDate().equals(previousPreferredDate)) {
					shopScheduleService.requireOpenDate(request.preferredDate());
				}
				if (request.startsAt() != null) {
					requireFutureStart(request.startsAt());
					shopScheduleService.requireAppointmentTime(
							request.startsAt(), booking.getServiceDurationSnapshotMinutes(), booking.getId());
					if (request.preferredDate() != null
							&& !LocalDate.ofInstant(request.startsAt(), SHOP_ZONE).equals(request.preferredDate())) {
						throw badRequest("Invalid preferred time", "Preferred time must fall on the selected preferred date.");
					}
				}
				booking.setStartsAt(request.startsAt());
				booking.setPreferredDate(request.preferredDate());
			}
			case DAY_QUEUE -> {
				if (request.startsAt() != null || request.preferredDate() != null || request.queueDate() == null) {
					throw badRequest("Invalid day queue request", "A day queue booking requires a queue date and no start time.");
				}
				if (request.queueDate().isBefore(LocalDate.now(SHOP_ZONE))) {
					throw badRequest("Invalid queue date", "Queue date must not be before the current shop-local date.");
				}
				shopScheduleService.requireOpenDate(request.queueDate());
				if (!request.queueDate().equals(booking.getQueueDate())) {
					lockQueueCounter(request.queueDate());
					booking.setQueueDate(request.queueDate());
					booking.setQueuePosition(allocateQueuePosition(request.queueDate()));
				}
			}
			case SAME_DAY_QUEUE -> {
				if (request.startsAt() != null || request.preferredDate() != null || request.queueDate() != null) {
					throw badRequest("Invalid same-day queue request", "Same-day queue bookings do not accept a date or time.");
				}
				shopScheduleService.requireOpenDate(booking.getQueueDate());
			}
		}
		if (request.ownerNote() != null) {
			String ownerNote = request.ownerNote().trim();
			booking.setOwnerNote(ownerNote.isBlank() ? null : ownerNote);
		}
		Booking saved = bookingRepository.saveAndFlush(booking);
		if (!Objects.equals(previousOwnerNote, saved.getOwnerNote()) && saved.getOwnerNote() != null) {
			notificationService.notifyBookingNote(saved.getId(), saved.getOwnerNote());
		}
		if (!Objects.equals(previousStartsAt, saved.getStartsAt())
				|| !Objects.equals(previousPreferredDate, saved.getPreferredDate())) {
			notificationService.notifyBookingRescheduled(saved.getId());
		}
		if (!Objects.equals(previousQueueDate, saved.getQueueDate())
				|| !Objects.equals(previousQueuePosition, saved.getQueuePosition())) {
			notificationService.notifyQueueUpdated(saved.getId());
		}
		return toResponse(saved);
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

		jdbcTemplate.execute("SET CONSTRAINTS uq_bookings_queue_date_position DEFERRED");
		Map<UUID, Booking> activeBookingsById = activeBookings.stream()
				.collect(java.util.stream.Collectors.toMap(Booking::getId, booking -> booking));
		List<Booking> ordered = request.bookingIds().stream()
				.map(activeBookingsById::get)
				.toList();
		for (int index = 0; index < ordered.size(); index++) {
			ordered.get(index).setQueuePosition(index + 1);
		}
		bookingRepository.flush();

		if (!ordered.isEmpty()) {
			jdbcTemplate.update("""
					UPDATE booking_queue_counters
					SET last_position = ?,
					    updated_at = CURRENT_TIMESTAMP
					WHERE queue_date = ?
					""",
				ordered.size(),
					request.date());
		}
		ordered.forEach(booking -> notificationService.notifyQueueUpdated(booking.getId()));
		return ordered.stream().map(BookingManagementService::toResponse).toList();
	}

	private void compactQueue(LocalDate date) {
		List<Booking> active = bookingRepository.findQueueBookingsForUpdate(date, ACTIVE_QUEUE_STATUSES);
		for (int index = 0; index < active.size(); index++) {
			active.get(index).setQueuePosition(index + 1);
		}
		bookingRepository.flush();
		jdbcTemplate.update("UPDATE booking_queue_counters SET last_position = ?, updated_at = CURRENT_TIMESTAMP WHERE queue_date = ?",
				active.size(), date);
		active.forEach(booking -> {
			notificationService.notifyQueueUpdated(booking.getId());
			if (booking.getQueuePosition() != null && booking.getQueuePosition() <= 2) {
				notificationService.notifyQueueApproaching(booking.getId());
			}
		});
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

	private Integer allocateQueuePosition(LocalDate date) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO booking_queue_counters (queue_date, last_position)
				VALUES (?, 1)
				ON CONFLICT (queue_date) DO UPDATE
				SET last_position = booking_queue_counters.last_position + 1,
				    updated_at = CURRENT_TIMESTAMP
				RETURNING last_position
				""", Integer.class, date);
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
				booking.getPreferredDate(),
				booking.getQueueDate(),
				booking.getQueuePosition(),
				booking.getNotes(),
				booking.getOwnerNote(),
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
