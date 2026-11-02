package com.barbershop.backend.booking;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.barbershop.backend.admin.OwnerPushNotification;
import com.barbershop.backend.booking.dto.GuestBookingRequest;
import com.barbershop.backend.booking.dto.GuestBookingResponse;
import com.barbershop.backend.customer.CustomerEntity;
import com.barbershop.backend.customer.CustomerAccount;
import com.barbershop.backend.customer.CustomerAccountRepository;
import com.barbershop.backend.customer.CustomerRepository;
import com.barbershop.backend.service.ServiceEntity;
import com.barbershop.backend.service.ServiceRepository;
import com.barbershop.backend.security.RateLimitService;

@Service
public class GuestBookingService {

	private static final String OPERATION_SCOPE = "POST:/api/bookings:v1";
	private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Hebron");
	private static final String IDEMPOTENCY_KEY_PATTERN = "^[\\x21-\\x7E]{1,255}$";
	private static final String PHONE_PATTERN = "^\\+?[0-9][0-9(). -]{5,30}$";

	private final BookingRepository bookingRepository;
	private final BookingSettingsRepository bookingSettingsRepository;
	private final CustomerRepository customerRepository;
	private final CustomerAccountRepository customerAccountRepository;
	private final ServiceRepository serviceRepository;
	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;
	private final RateLimitService rateLimitService;
	private final ShopScheduleService shopScheduleService;
	private final ApplicationEventPublisher eventPublisher;

	public GuestBookingService(
			BookingRepository bookingRepository,
			BookingSettingsRepository bookingSettingsRepository,
			CustomerRepository customerRepository,
			CustomerAccountRepository customerAccountRepository,
			ServiceRepository serviceRepository,
			JdbcTemplate jdbcTemplate,
			ObjectMapper objectMapper,
			RateLimitService rateLimitService,
			ShopScheduleService shopScheduleService,
			ApplicationEventPublisher eventPublisher) {
		this.bookingRepository = bookingRepository;
		this.bookingSettingsRepository = bookingSettingsRepository;
		this.customerRepository = customerRepository;
		this.customerAccountRepository = customerAccountRepository;
		this.serviceRepository = serviceRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.rateLimitService = rateLimitService;
		this.shopScheduleService = shopScheduleService;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public BookingCreationResult createBooking(
			String idempotencyKey,
			GuestBookingRequest request,
			String remoteAddress,
			String customerUsername) {
		ValidatedRequest validated = validateAndNormalize(idempotencyKey, request);
		byte[] fingerprint = fingerprint(validated);
		if (!reserveIdempotencyKey(validated.idempotencyKey(), fingerprint)) {
			return replayExisting(validated.idempotencyKey(), fingerprint);
		}
		rateLimitService.consumeBookingAttempt(remoteAddress);

		BookingSettings settings = bookingSettingsRepository.findBySingletonKeyTrue()
				.orElseThrow(() -> new BookingApiException(
						HttpStatus.SERVICE_UNAVAILABLE,
						"Booking settings unavailable",
						"Booking settings are not initialized."));
		if (settings.isNewBookingsDisabled() || !isModeEnabled(settings, validated.mode())) {
			throw new BookingApiException(
					HttpStatus.CONFLICT,
					"Bookings unavailable",
					"New bookings are currently unavailable for the selected mode.");
		}

		ServiceEntity service = serviceRepository.findByIdAndActiveTrue(validated.serviceId())
				.orElseThrow(() -> new BookingApiException(
						HttpStatus.NOT_FOUND,
						"Service not found",
						"The selected service is not available."));
		if (validated.mode() == BookingMode.EXACT_APPOINTMENT) {
			shopScheduleService.requireAppointmentTime(
					validated.startsAt(), service.getDurationMinutes(), null);
		} else if (validated.queueDate() != null) {
			shopScheduleService.requireOpenQueueDate(validated.queueDate());
		}
		CustomerEntity customer = customerRepository.saveAndFlush(
				new CustomerEntity(validated.customerName(), validated.customerPhone()));
		CustomerAccount customerAccount = customerUsername == null
				|| validated.bookForSomeoneElse() ? null
				: customerAccountRepository.findByUsernameNormalized(customerUsername)
						.orElseThrow(() -> new IllegalStateException("Authenticated customer account no longer exists."));

		Integer queuePosition = validated.queueDate() == null
				? null
				: allocateQueuePosition(validated.queueDate());
		Booking booking = bookingRepository.saveAndFlush(new Booking(
				customer,
				customerAccount,
				service,
				validated.mode(),
				validated.startsAt(),
				validated.preferredDate(),
				validated.queueDate(),
				queuePosition,
				validated.notes()));

		GuestBookingResponse response = new GuestBookingResponse(
				booking.getId(),
				booking.getBookingMode(),
				booking.getStatus(),
				booking.getStartsAt(),
				booking.getQueueDate(),
				booking.getQueuePosition(),
				booking.getCreatedAt());
		String location = "/api/bookings/" + booking.getId();
		updateIdempotencyResponse(validated.idempotencyKey(), booking.getId(), response, location);
		eventPublisher.publishEvent(new OwnerPushNotification(
				"طلب حجز جديد",
				validated.customerName() + " · " + service.getName(),
				"/owner/bookings"));
		return new BookingCreationResult(201, location, response);
	}

	private ValidatedRequest validateAndNormalize(String key, GuestBookingRequest request) {
		if (key == null || !key.matches(IDEMPOTENCY_KEY_PATTERN)) {
			throw badRequest("Invalid Idempotency-Key", "Idempotency-Key is required and must contain 1-255 visible ASCII characters.");
		}
		if (request == null || request.bookingMode() == null || request.serviceId() == null) {
			throw badRequest("Invalid booking request", "A booking mode and service ID are required.");
		}
		if (request.bookingMode() == BookingMode.LEGACY) {
			throw badRequest("Invalid booking mode", "LEGACY is reserved for historical bookings.");
		}

		String name = request.customerName() == null ? "" : request.customerName().trim();
		if (name.isEmpty() || name.length() > 150) {
			throw badRequest("Invalid customer name", "Customer name must contain 1-150 characters after trimming.");
		}

		String phone = request.customerPhone() == null ? null : request.customerPhone().trim();
		if (phone != null && phone.isEmpty()) {
			phone = null;
		}
		if (phone != null && (!phone.matches(PHONE_PATTERN)
				|| phone.chars().filter(Character::isDigit).count() < 7
				|| phone.chars().filter(Character::isDigit).count() > 15)) {
			throw badRequest("Invalid phone number", "Phone number must be 7-15 digits and use only common phone punctuation.");
		}
		if (phone != null && phone.length() > 32) {
			throw badRequest("Invalid phone number", "Phone number must not exceed 32 characters.");
		}

		Instant startsAt = request.startsAt();
		LocalDate preferredDate = request.preferredDate();
		LocalDate queueDate = request.queueDate();
		switch (request.bookingMode()) {
			case EXACT_APPOINTMENT -> {
				if (startsAt == null || preferredDate != null || queueDate != null) {
					throw badRequest("Invalid appointment fields", "Exact appointments require startsAt and must not include preferredDate or queueDate.");
				}
				requireFutureStart(startsAt);
			}
			case CONFIRMATION_REQUEST -> {
				if (queueDate != null) {
					throw badRequest("Invalid confirmation request", "Confirmation requests must not include queueDate.");
				}
				if (preferredDate != null && preferredDate.isBefore(LocalDate.now(SHOP_ZONE))) {
					throw badRequest("Invalid preferred date", "Preferred date must not be before the current shop-local date.");
				}
				if (startsAt != null) {
					requireFutureStart(startsAt);
					if (preferredDate != null
							&& !LocalDate.ofInstant(startsAt, SHOP_ZONE).equals(preferredDate)) {
						throw badRequest("Invalid preferred time", "Preferred time must fall on the selected preferred date.");
					}
				}
			}
			case DAY_QUEUE -> {
				if (startsAt != null || preferredDate != null || queueDate == null) {
					throw badRequest("Invalid day queue request", "Day queue requests require queueDate and must not include startsAt.");
				}
				requireValidQueueDate(queueDate);
			}
			case SAME_DAY_QUEUE -> {
				if (startsAt != null || preferredDate != null || queueDate != null) {
					throw badRequest("Invalid same-day queue request", "Same-day queue requests must not include startsAt or queueDate.");
				}
				queueDate = LocalDate.now(SHOP_ZONE);
			}
			case LEGACY -> throw badRequest("Invalid booking mode", "LEGACY is reserved for historical bookings.");
		}

		return new ValidatedRequest(
				key,
				request.bookingMode(),
				request.serviceId(),
				name,
				phone,
				startsAt,
				preferredDate,
				queueDate,
				request.notes(),
				request.bookForSomeoneElse());
	}

	private void requireFutureStart(Instant startsAt) {
		if (!startsAt.isAfter(Instant.now())) {
			throw badRequest("Invalid appointment time", "Appointment time must be in the future.");
		}
	}

	private void requireValidQueueDate(LocalDate queueDate) {
		if (queueDate.isBefore(LocalDate.now(SHOP_ZONE))) {
			throw badRequest("Invalid queue date", "Queue date must not be before the current shop-local date.");
		}
	}

	private boolean reserveIdempotencyKey(String key, byte[] fingerprint) {
		List<Integer> inserted = jdbcTemplate.query("""
				INSERT INTO booking_idempotency (
				    operation_scope, idempotency_key, request_fingerprint,
				    response_status, response_body
				)
				VALUES (?, ?, ?, 201, '{}'::jsonb)
				ON CONFLICT (operation_scope, idempotency_key) DO NOTHING
				RETURNING 1
				""",
				(result, rowNumber) -> result.getInt(1),
				OPERATION_SCOPE,
				key,
				fingerprint);
		return !inserted.isEmpty();
	}

	private BookingCreationResult replayExisting(String key, byte[] fingerprint) {
		Map<String, Object> stored = jdbcTemplate.queryForMap("""
				SELECT request_fingerprint, response_status, response_body::text AS response_body,
				       response_location
				FROM booking_idempotency
				WHERE operation_scope = ? AND idempotency_key = ?
				""", OPERATION_SCOPE, key);
		byte[] existingFingerprint = (byte[]) stored.get("request_fingerprint");
		if (!MessageDigest.isEqual(existingFingerprint, fingerprint)) {
			throw new BookingApiException(
					HttpStatus.CONFLICT,
					"Idempotency key conflict",
					"Idempotency-Key was already used with a different request.");
		}

		try {
			GuestBookingResponse response = objectMapper.readValue(
					(String) stored.get("response_body"),
					GuestBookingResponse.class);
			return new BookingCreationResult(
					((Number) stored.get("response_status")).intValue(),
					(String) stored.get("response_location"),
					response);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Stored booking replay response could not be read.", exception);
		}
	}

	private void updateIdempotencyResponse(
			String key,
			UUID bookingId,
			GuestBookingResponse response,
			String location) {
		try {
			String body = objectMapper.writeValueAsString(response);
			jdbcTemplate.update("""
					UPDATE booking_idempotency
					SET response_body = ?::jsonb,
					    response_location = ?,
					    booking_id = ?,
					    updated_at = CURRENT_TIMESTAMP
					WHERE operation_scope = ? AND idempotency_key = ?
					""",
					body,
					location,
					bookingId,
					OPERATION_SCOPE,
					key);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Booking response could not be serialized for idempotent replay.", exception);
		}
	}

	private Integer allocateQueuePosition(LocalDate queueDate) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO booking_queue_counters (queue_date, last_position)
				VALUES (?, 1)
				ON CONFLICT (queue_date) DO UPDATE
				SET last_position = booking_queue_counters.last_position + 1,
				    updated_at = CURRENT_TIMESTAMP
				RETURNING last_position
				""", Integer.class, queueDate);
	}

	private byte[] fingerprint(ValidatedRequest request) {
		try {
			byte[] canonical = objectMapper.writeValueAsBytes(List.of(
					request.mode().name(),
					request.serviceId().toString(),
					request.customerName(),
					request.customerPhone() == null ? "" : request.customerPhone(),
					request.startsAt() == null ? "" : request.startsAt().toString(),
					request.preferredDate() == null ? "" : request.preferredDate().toString(),
					request.queueDate() == null ? "" : request.queueDate().toString(),
					request.notes() == null ? "" : request.notes(),
					request.bookForSomeoneElse()));
			return MessageDigest.getInstance("SHA-256").digest(canonical);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Validated booking request could not be fingerprinted.", exception);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static boolean isModeEnabled(BookingSettings settings, BookingMode mode) {
		return switch (mode) {
			case EXACT_APPOINTMENT -> settings.isExactAppointmentEnabled();
			case CONFIRMATION_REQUEST -> settings.isConfirmationRequestEnabled();
			case DAY_QUEUE -> settings.isDayQueueEnabled();
			case SAME_DAY_QUEUE -> settings.isSameDayQueueEnabled();
			case LEGACY -> false;
		};
	}

	private static BookingApiException badRequest(String title, String detail) {
		return new BookingApiException(HttpStatus.BAD_REQUEST, title, detail);
	}

	private record ValidatedRequest(
			String idempotencyKey,
			BookingMode mode,
			UUID serviceId,
			String customerName,
			String customerPhone,
			Instant startsAt,
			LocalDate preferredDate,
			LocalDate queueDate,
			String notes,
			boolean bookForSomeoneElse) {
	}
}
