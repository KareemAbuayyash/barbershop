package com.barbershop.backend.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import com.barbershop.backend.booking.dto.GuestBookingRequest;
import com.barbershop.backend.service.ServiceEntity;
import com.barbershop.backend.service.ServiceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GuestBookingApiIntegrationTests {

	private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Hebron");
	private static final String TEST_HOURS = """
			{"MONDAY":{"opensAt":"00:00","closesAt":"23:59"},
			 "TUESDAY":{"opensAt":"00:00","closesAt":"23:59"},
			 "WEDNESDAY":{"opensAt":"00:00","closesAt":"23:59"},
			 "THURSDAY":{"opensAt":"00:00","closesAt":"23:59"},
			 "FRIDAY":{"opensAt":"00:00","closesAt":"23:59"},
			 "SATURDAY":{"opensAt":"00:00","closesAt":"23:59"},
			 "SUNDAY":{"opensAt":"00:00","closesAt":"23:59"}}
			""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void enableTestShopHours() {
		jdbcTemplate.update(
				"UPDATE shop_profile SET opening_hours = ?::jsonb WHERE singleton_key = TRUE",
				TEST_HOURS);
	}

	@Test
	@Transactional
	@Rollback
	void createsEachBookingModeWithCorrectSchedulingShape() throws Exception {
		enableAllBookingModes();
		ServiceEntity service = createService();

		MvcResult exact = postBooking(
				"exact-mode-key",
				request(BookingMode.EXACT_APPOINTMENT, service, upcomingQuarterHour(1), null, null, null))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.bookingMode").value("EXACT_APPOINTMENT"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.startsAt").isNotEmpty())
				.andExpect(jsonPath("$.queuePosition").doesNotExist())
				.andReturn();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT appointment_end_at FROM bookings WHERE id = ?",
				Object.class,
				bookingId(exact))).isNull();

		postBooking(
				"confirmation-mode-key",
				request(BookingMode.CONFIRMATION_REQUEST, service, null, null, null, null))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.bookingMode").value("CONFIRMATION_REQUEST"))
				.andExpect(jsonPath("$.startsAt").doesNotExist());

		MvcResult dayQueue = postBooking(
				"day-queue-mode-key",
				request(BookingMode.DAY_QUEUE, service, null, LocalDate.now(SHOP_ZONE), null, null))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.bookingMode").value("DAY_QUEUE"))
				.andExpect(jsonPath("$.startsAt").doesNotExist())
				.andExpect(jsonPath("$.queuePosition").isNumber())
				.andReturn();

		MvcResult sameDayQueue = postBooking(
				"same-day-queue-mode-key",
				request(BookingMode.SAME_DAY_QUEUE, service, null, null, null, null))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.bookingMode").value("SAME_DAY_QUEUE"))
				.andExpect(jsonPath("$.queueDate").value(LocalDate.now(SHOP_ZONE).toString()))
				.andExpect(jsonPath("$.queuePosition").isNumber())
				.andReturn();
		assertThat(jsonValue(sameDayQueue, "queuePosition"))
				.isEqualTo(jsonValue(dayQueue, "queuePosition") + 1);
	}

	@Test
	@Transactional
	@Rollback
	void publicAvailabilityAndGuestBookingsRespectConfiguredOpeningHours() throws Exception {
		enableAllBookingModes();
		ServiceEntity service = createService();
		LocalDate monday = LocalDate.now(SHOP_ZONE).plusDays(1);
		while (monday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) monday = monday.plusDays(1);
		jdbcTemplate.update("""
				UPDATE shop_profile SET opening_hours =
				    '{"MONDAY":{"opensAt":"09:00","closesAt":"12:00"}}'::jsonb
				WHERE singleton_key = TRUE
				""");

		mockMvc.perform(get("/api/bookings/availability")
						.param("date", monday.toString())
						.param("serviceId", service.getId().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.open").value(true))
				.andExpect(jsonPath("$.slots[0].localTime").value("09:00"));
		mockMvc.perform(get("/api/bookings/availability")
						.param("date", monday.plusDays(1).toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.open").value(false))
				.andExpect(jsonPath("$.slots").isEmpty());

		Instant outsideHours = monday.atTime(8, 45).atZone(SHOP_ZONE).toInstant();
		postBooking(
				"outside-hours-" + UUID.randomUUID(),
				request(BookingMode.EXACT_APPOINTMENT, service, outsideHours, null, null, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail")
						.value("The selected time is outside the shop's opening hours or available time slots."));
	}

	private int jsonValue(MvcResult result, String property) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString())
				.get(property).asInt();
	}

	private static Instant upcomingQuarterHour(int hoursAhead) {
		var local = Instant.now().atZone(SHOP_ZONE).plusHours(hoursAhead).withSecond(0).withNano(0);
		int remainder = local.getMinute() % 15;
		return local.plusMinutes(remainder == 0 ? 0 : 15 - remainder).toInstant();
	}

	@Test
	@Transactional
	@Rollback
	void rejectsDisabledBookingsAndDisabledMode() throws Exception {
		ServiceEntity service = createService();
		long bookingsBefore = bookingRepository.count();
		setBookingSettings(false, false, false, false, true);

		postBooking(
				"globally-disabled-key",
				request(BookingMode.EXACT_APPOINTMENT, service, upcomingQuarterHour(1), null, null, null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Bookings unavailable"));

		setBookingSettings(true, false, false, false, false);
		postBooking(
				"mode-disabled-key",
				request(BookingMode.DAY_QUEUE, service, null, LocalDate.now(SHOP_ZONE).plusDays(1), null, null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Bookings unavailable"));
		assertThat(bookingRepository.count()).isEqualTo(bookingsBefore);
	}

	@Test
	void failedCreationRollsBackItsIdempotencyReservation() throws Exception {
		ServiceEntity service = createService();
		Map<String, Object> originalSettings = currentBookingSettings();
		String key = "disabled-rollback-" + UUID.randomUUID();
		try {
			setBookingSettings(false, false, false, false, true);
			postBooking(
					key,
					request(BookingMode.EXACT_APPOINTMENT, service, upcomingQuarterHour(1), null, null, null))
					.andExpect(status().isConflict());
			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(*) FROM booking_idempotency WHERE idempotency_key = ?",
					Integer.class,
					key)).isZero();
		} finally {
			restoreBookingSettings(originalSettings);
			serviceRepository.deleteById(service.getId());
		}
	}

	@Test
	@Transactional
	@Rollback
	void rejectsMissingOrInactiveService() throws Exception {
		enableAllBookingModes();
		ServiceEntity inactive = createService();
		long bookingsBefore = bookingRepository.count();
		inactive.setActive(false);
		serviceRepository.saveAndFlush(inactive);

		postBooking(
				"inactive-service-key",
				request(BookingMode.CONFIRMATION_REQUEST, inactive, null, null, null, null))
				.andExpect(status().isNotFound());
		postBooking(
				"missing-service-key",
				new GuestBookingRequest(
						BookingMode.CONFIRMATION_REQUEST,
						UUID.randomUUID(),
						"Guest",
						null,
						null,
						null,
						null))
				.andExpect(status().isNotFound());
		assertThat(bookingRepository.count()).isEqualTo(bookingsBefore);
	}

	@Test
	@Transactional
	@Rollback
	void requiresAValidIdempotencyKeyAndValidModeFields() throws Exception {
		enableAllBookingModes();
		ServiceEntity service = createService();
		String body = json(request(
				BookingMode.CONFIRMATION_REQUEST, service, null, null, null, null));

		mockMvc.perform(post("/api/bookings")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid Idempotency-Key"));

		postBooking(
				"invalid-mode-fields",
				request(BookingMode.EXACT_APPOINTMENT, service, null, null, null, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid appointment fields"));

		postBooking(
				"invalid-legacy-mode",
				request(BookingMode.LEGACY, service, upcomingQuarterHour(1), null, null, null))
				.andExpect(status().isBadRequest());
		postBooking(
				"invalid-phone-key",
				request(BookingMode.CONFIRMATION_REQUEST, service, null, null, "phone-number", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid phone number"));
	}

	@Test
	@Transactional
	@Rollback
	void createsSeparateGuestRecordsAndPreservesServiceSnapshots() throws Exception {
		enableAllBookingModes();
		ServiceEntity service = createService();

		MvcResult first = postBooking(
				"guest-first-key",
				request(BookingMode.CONFIRMATION_REQUEST, service, null, null, " +1 202 555 0134 ", "First note"))
				.andExpect(status().isCreated())
				.andReturn();
		MvcResult second = postBooking(
				"guest-second-key",
				request(BookingMode.CONFIRMATION_REQUEST, service, null, null, "+1 202 555 0134", "Second note"))
				.andExpect(status().isCreated())
				.andReturn();

		UUID firstId = bookingId(first);
		UUID secondId = bookingId(second);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(DISTINCT customer_id) FROM bookings WHERE id IN (?, ?)",
				Integer.class,
				firstId,
				secondId)).isEqualTo(2);

		service.setName("Revised service");
		service.setPrice(new BigDecimal("34.00"));
		service.setDurationMinutes(60);
		serviceRepository.saveAndFlush(service);
		entityManager.clear();
		Booking stored = bookingRepository.findById(firstId).orElseThrow();
		assertThat(stored.getServiceNameSnapshot()).isEqualTo("Premium service");
		assertThat(stored.getServicePriceSnapshot()).isEqualByComparingTo("27.50");
		assertThat(stored.getServiceDurationSnapshotMinutes()).isEqualTo(45);
	}

	@Test
	@Transactional
	@Rollback
	void sameKeyReplaysResponseAndDifferentPayloadConflicts() throws Exception {
		enableAllBookingModes();
		ServiceEntity service = createService();
		GuestBookingRequest original = request(
				BookingMode.CONFIRMATION_REQUEST, service, null, null, null, "request");

		MvcResult first = postBooking("replay-key", original)
				.andExpect(status().isCreated())
				.andReturn();
		MvcResult replay = postBooking("replay-key", original)
				.andExpect(status().isCreated())
				.andReturn();
		assertThat(bookingId(replay)).isEqualTo(bookingId(first));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM booking_idempotency WHERE idempotency_key = 'replay-key'",
				Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM bookings WHERE id = ?",
				Integer.class,
				bookingId(first))).isEqualTo(1);
		assertThat(replay.getResponse().getContentAsString())
				.isEqualTo(first.getResponse().getContentAsString());
		assertThat(replay.getResponse().getHeader("Location"))
				.isEqualTo(first.getResponse().getHeader("Location"));

		postBooking(
				"replay-key",
				request(BookingMode.CONFIRMATION_REQUEST, service, null, null, null, "different request"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Idempotency key conflict"));

		Map<String, Object> replayBody = objectMapper.readValue(
				first.getResponse().getContentAsString(),
				objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
		assertThat(replayBody).doesNotContainKeys("customerName", "customerPhone", "phoneNumber");
	}

	@Test
	@Transactional
	@Rollback
	void guestBookingRateLimitAppliesToNewRequestsButNotIdempotentReplays() throws Exception {
		enableAllBookingModes();
		ServiceEntity service = createService();
		GuestBookingRequest request = request(
				BookingMode.CONFIRMATION_REQUEST, service, null, null, null, "rate-limited");
		String remoteAddress = "203.0.113.72";

		for (int attempt = 0; attempt < 10; attempt++) {
			postBookingFromIp("rate-limit-key-" + attempt, request, remoteAddress)
					.andExpect(status().isCreated());
		}
		postBookingFromIp("rate-limit-blocked", request, remoteAddress)
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));

		postBookingFromIp("rate-limit-key-0", request, remoteAddress)
				.andExpect(status().isCreated());
	}

	@Test
	void concurrentSameKeyRequestsCreateOneBookingAndReplay() throws Exception {
		ServiceEntity service = createService();
		Map<String, Object> originalSettings = currentBookingSettings();
		enableAllBookingModes();
		String key = "concurrent-idempotency-" + UUID.randomUUID();
		GuestBookingRequest request = request(
				BookingMode.CONFIRMATION_REQUEST, service, null, null, "202 555 0134", null);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor.submit(() -> concurrentPost(key, request, ready, start));
			Future<MvcResult> second = executor.submit(() -> concurrentPost(key, request, ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			MvcResult firstResult = first.get(20, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(20, TimeUnit.SECONDS);
			assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
			assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
			assertThat(bookingId(firstResult)).isEqualTo(bookingId(secondResult));
			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(*) FROM booking_idempotency WHERE idempotency_key = ?",
					Integer.class,
					key)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(*) FROM bookings WHERE id = ?",
					Integer.class,
					bookingId(firstResult))).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(*) FROM customers c JOIN bookings b ON b.customer_id = c.id WHERE b.id = ?",
					Integer.class,
					bookingId(firstResult))).isEqualTo(1);
		} finally {
			start.countDown();
			executor.shutdownNow();
			cleanupIdempotencyKey(key);
			restoreBookingSettings(originalSettings);
			serviceRepository.deleteById(service.getId());
		}
	}

	@Test
	void concurrentQueueRequestsReceiveUniqueSharedPositions() throws Exception {
		ServiceEntity service = createService();
		Map<String, Object> originalSettings = currentBookingSettings();
		enableAllBookingModes();
		LocalDate queueDate = LocalDate.now(SHOP_ZONE);
		String firstKey = "queue-concurrent-a-" + UUID.randomUUID();
		String secondKey = "queue-concurrent-b-" + UUID.randomUUID();
		GuestBookingRequest firstRequest = request(
				BookingMode.DAY_QUEUE, service, null, queueDate, null, null);
		GuestBookingRequest secondRequest = request(
				BookingMode.SAME_DAY_QUEUE, service, null, null, null, null);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> first = executor.submit(() -> concurrentPost(firstKey, firstRequest, ready, start));
			Future<MvcResult> second = executor.submit(() -> concurrentPost(secondKey, secondRequest, ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult firstResult = first.get(20, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(20, TimeUnit.SECONDS);
			assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
			assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);

			UUID firstId = bookingId(firstResult);
			UUID secondId = bookingId(secondResult);
			List<Integer> positions = jdbcTemplate.query(
					"SELECT queue_position FROM bookings WHERE id IN (?, ?) ORDER BY queue_position",
					(result, row) -> result.getInt(1),
					firstId,
					secondId);
			assertThat(positions).hasSize(2);
			assertThat(positions.get(1)).isEqualTo(positions.get(0) + 1);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(DISTINCT queue_date) FROM bookings WHERE id IN (?, ?)",
					Integer.class,
					firstId,
					secondId)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(*) FROM bookings WHERE id IN (?, ?) AND queue_date = ?",
					Integer.class,
					firstId,
					secondId,
					queueDate)).isEqualTo(2);
		} finally {
			start.countDown();
			executor.shutdownNow();
			cleanupIdempotencyKey(firstKey);
			cleanupIdempotencyKey(secondKey);
			restoreBookingSettings(originalSettings);
			serviceRepository.deleteById(service.getId());
		}
	}

	@Test
	@org.springframework.security.test.context.support.WithMockUser
	@Transactional
	@Rollback
	void requiresCsrfAndDoesNotExposeOtherBookingPaths() throws Exception {
		enableAllBookingModes();
		ServiceEntity service = createService();
		MvcResult tokenResult = mockMvc.perform(get("/api/csrf"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andReturn();
		MockHttpSession session = (MockHttpSession) tokenResult.getRequest().getSession(false);
		String token = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
				.get("token").asText();
		String body = json(request(
				BookingMode.CONFIRMATION_REQUEST, service, null, null, null, null));

		mockMvc.perform(post("/api/bookings")
						.session(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/bookings")
						.session(session)
						.header("X-CSRF-TOKEN", token)
						.header("Idempotency-Key", "csrf-valid-key")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/bookings")
						.with(user("owner").roles("OWNER")))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/bookings/" + UUID.randomUUID())
						.with(csrf())
						.header("Idempotency-Key", "other-path-key")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
						.andExpect(status().isForbidden());
	}

	private org.springframework.test.web.servlet.ResultActions postBooking(
			String key,
			GuestBookingRequest request) throws Exception {
		return postBookingFromIp(key, request, "test-" + UUID.randomUUID());
	}

	private org.springframework.test.web.servlet.ResultActions postBookingFromIp(
			String key,
			GuestBookingRequest request,
			String remoteAddress) throws Exception {
		return mockMvc.perform(post("/api/bookings")
				.with(csrf())
				.with(servletRequest -> {
					servletRequest.setRemoteAddr(remoteAddress);
					return servletRequest;
				})
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(request)));
	}

	private MvcResult concurrentPost(
			String key,
			GuestBookingRequest request,
			CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Timed out waiting to start concurrent guest booking requests.");
		}
		return mockMvc.perform(post("/api/bookings")
						.with(csrf())
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(request)))
				.andReturn();
	}

	private GuestBookingRequest request(
			BookingMode mode,
			ServiceEntity service,
			Instant startsAt,
			LocalDate queueDate,
			String phone,
			String notes) {
		return new GuestBookingRequest(mode, service.getId(), "  Guest Customer  ", phone, startsAt, queueDate, notes);
	}

	private ServiceEntity createService() {
		return serviceRepository.saveAndFlush(
				new ServiceEntity("Premium service", "A test service", new BigDecimal("27.50"), 45));
	}

	private void enableAllBookingModes() {
		setBookingSettings(true, true, true, true, false);
	}

	private void setBookingSettings(
			boolean exact,
			boolean confirmation,
			boolean dayQueue,
			boolean sameDayQueue,
			boolean disabled) {
		jdbcTemplate.update("""
				UPDATE booking_settings
				SET exact_appointment_enabled = ?,
				    confirmation_request_enabled = ?,
				    day_queue_enabled = ?,
				    same_day_queue_enabled = ?
				,
				    new_bookings_disabled = ?
				WHERE singleton_key = TRUE
				""",
				exact,
				confirmation,
				dayQueue,
				sameDayQueue,
				disabled);
	}

	private Map<String, Object> currentBookingSettings() {
		return jdbcTemplate.queryForMap("""
				SELECT exact_appointment_enabled, confirmation_request_enabled,
				       day_queue_enabled, same_day_queue_enabled, new_bookings_disabled
				FROM booking_settings WHERE singleton_key = TRUE
				""");
	}

	private void restoreBookingSettings(Map<String, Object> settings) {
		setBookingSettings(
				(Boolean) settings.get("exact_appointment_enabled"),
				(Boolean) settings.get("confirmation_request_enabled"),
				(Boolean) settings.get("day_queue_enabled"),
				(Boolean) settings.get("same_day_queue_enabled"),
				(Boolean) settings.get("new_bookings_disabled"));
	}

	private String json(GuestBookingRequest request) {
		try {
			return objectMapper.writeValueAsString(request);
		} catch (Exception exception) {
			throw new IllegalStateException("Test request could not be serialized.", exception);
		}
	}

	private UUID bookingId(MvcResult result) throws Exception {
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
				.get("bookingId").asText());
	}

	private void cleanupIdempotencyKey(String key) {
		List<UUID> bookingIds = jdbcTemplate.query(
				"SELECT booking_id FROM booking_idempotency WHERE idempotency_key = ? AND booking_id IS NOT NULL",
				(result, row) -> result.getObject(1, UUID.class),
				key);
		jdbcTemplate.update("DELETE FROM booking_idempotency WHERE idempotency_key = ?", key);
		for (UUID bookingId : bookingIds) {
			UUID customerId = jdbcTemplate.query(
					"SELECT customer_id FROM bookings WHERE id = ? AND customer_id IS NOT NULL",
					result -> result.next() ? result.getObject(1, UUID.class) : null,
					bookingId);
			jdbcTemplate.update("DELETE FROM bookings WHERE id = ?", bookingId);
			if (customerId != null) {
				jdbcTemplate.update("DELETE FROM customers WHERE id = ?", customerId);
			}
		}
	}
}
