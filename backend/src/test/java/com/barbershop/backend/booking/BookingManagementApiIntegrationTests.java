package com.barbershop.backend.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BookingManagementApiIntegrationTests {

	private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Hebron");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void managementRoutesRequireAuthenticationAndCsrf() throws Exception {
		UUID bookingId = UUID.randomUUID();
		mockMvc.perform(get("/api/bookings"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/api/bookings/{id}/confirm", bookingId)
						.with(csrf()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/api/bookings/queue/reorder")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"date\":\"2030-01-01\",\"bookingIds\":[]}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/api/bookings/{id}/cancel", bookingId)
						.with(SecurityMockMvcRequestPostProcessors.user("manager")))
				.andExpect(status().isForbidden());
	}

	@Test
	void publicBookingErrorsDoNotEchoGuestPersonalInformation() throws Exception {
		String name = "Private Guest " + UUID.randomUUID();
		String phone = "+1 202 555 0199";
		String body = json(Map.of(
				"bookingMode", "LEGACY",
				"serviceId", UUID.randomUUID(),
				"customerName", name,
				"customerPhone", phone));

		MvcResult result = mockMvc.perform(post("/api/bookings")
						.with(csrf())
						.header("Idempotency-Key", "management-pii-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andReturn();
		assertThat(result.getResponse().getContentAsString())
				.doesNotContain(name)
				.doesNotContain(phone);
	}

	@Test
	@Transactional
	@Rollback
	void listingSupportsDateStatusModeFiltersAndBoundedPagination() throws Exception {
		LocalDate date = LocalDate.of(2031, 4, 10);
		insertBooking(BookingMode.DAY_QUEUE, null, date, 1, BookingStatus.PENDING, "Queue guest", "+1 202 555 0101");
		insertBooking(BookingMode.SAME_DAY_QUEUE, null, date, 2, BookingStatus.PENDING, "Second guest", null);
		insertBooking(
				BookingMode.EXACT_APPOINTMENT,
				date.atTime(9, 30).atZone(SHOP_ZONE).toInstant(),
				null,
				null,
				BookingStatus.CONFIRMED,
				"Appointment guest",
				null);

		mockMvc.perform(get("/api/bookings")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.param("date", date.toString())
						.param("status", "PENDING")
						.param("mode", "DAY_QUEUE")
						.param("page", "0")
						.param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].customerName").value("Queue guest"))
				.andExpect(jsonPath("$.content[0].customerPhone").value("+1 202 555 0101"))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(1));

		mockMvc.perform(get("/api/bookings")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.param("size", "101"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/bookings")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.param("page", "1000")
						.param("size", "100"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/bookings")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.param("page", "1001")
						.param("size", "100"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid pagination"));
	}

	@Test
	@Transactional
	@Rollback
	void listReturnsQueueEntriesInManualPositionOrderAcrossPages() throws Exception {
		LocalDate date = LocalDate.of(2032, 6, 12);
		UUID first = insertBooking(BookingMode.DAY_QUEUE, null, date, 1, BookingStatus.PENDING, "First", null);
		UUID second = insertBooking(BookingMode.SAME_DAY_QUEUE, null, date, 2, BookingStatus.PENDING, "Second", null);
		UUID third = insertBooking(BookingMode.DAY_QUEUE, null, date, 3, BookingStatus.CONFIRMED, "Third", null);

		mockMvc.perform(patch("/api/bookings/queue/reorder")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("date", date, "bookingIds", List.of(third, first, second)))))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/bookings")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.param("date", date.toString())
						.param("page", "0")
						.param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(third.toString()))
				.andExpect(jsonPath("$.content[0].queuePosition").value(4))
				.andExpect(jsonPath("$.content[1].id").value(first.toString()))
				.andExpect(jsonPath("$.content[1].queuePosition").value(5));

		mockMvc.perform(get("/api/bookings")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.param("date", date.toString())
						.param("page", "1")
						.param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(second.toString()))
				.andExpect(jsonPath("$.content[0].queuePosition").value(6));
	}

	@Test
	@Transactional
	@Rollback
	void guestCreationLocationIdentifiesAuthenticatedBookingResourceOnly() throws Exception {
		UUID serviceId = insertService();
		jdbcTemplate.update("""
				UPDATE booking_settings
				SET confirmation_request_enabled = TRUE, new_bookings_disabled = FALSE
				WHERE singleton_key = TRUE
				""");
		String request = json(Map.of(
				"bookingMode", "CONFIRMATION_REQUEST",
				"serviceId", serviceId,
				"customerName", "Location guest"));
		MvcResult created = mockMvc.perform(post("/api/bookings")
						.with(csrf())
						.header("Idempotency-Key", "location-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content(request))
				.andExpect(status().isCreated())
				.andReturn();
		String location = created.getResponse().getHeader("Location");
		UUID bookingId = UUID.fromString(
				objectMapper.readTree(created.getResponse().getContentAsString())
						.get("bookingId").asText());
		assertThat(location).isEqualTo("/api/bookings/" + bookingId);

		mockMvc.perform(get(location)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(bookingId.toString()))
				.andExpect(jsonPath("$.customerName").value("Location guest"));
		mockMvc.perform(get(location))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@Transactional
	@Rollback
	void exactAppointmentCanOnlyBeConfirmedOnceAndSnapshotsRemainUnchanged() throws Exception {
		UUID id = insertBooking(
				BookingMode.EXACT_APPOINTMENT,
				Instant.now().plusSeconds(3600),
				null,
				null,
				BookingStatus.PENDING,
				"Guest",
				"202 555 0101");

		mockMvc.perform(patch("/api/bookings/{id}/confirm", id)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.serviceName").value("Original service"))
				.andExpect(jsonPath("$.servicePrice").value(27.5))
				.andExpect(jsonPath("$.serviceDurationMinutes").value(45))
				.andExpect(jsonPath("$.customerName").value("Guest"));

		mockMvc.perform(patch("/api/bookings/{id}/confirm", id)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isConflict());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT appointment_end_at IS NOT NULL FROM bookings WHERE id = ?",
				Boolean.class,
				id)).isTrue();
	}

	@Test
	@Transactional
	@Rollback
	void confirmationRequestCanUseProposedTimeOrRequireOne() throws Exception {
		UUID withProposal = insertBooking(
				BookingMode.CONFIRMATION_REQUEST,
				Instant.now().plusSeconds(3600),
				null,
				null,
				BookingStatus.PENDING,
				"Proposed guest",
				null);
		UUID withoutProposal = insertBooking(
				BookingMode.CONFIRMATION_REQUEST,
				null,
				null,
				null,
				BookingStatus.PENDING,
				"Unscheduled guest",
				null);

		mockMvc.perform(patch("/api/bookings/{id}/confirm", withProposal)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.startsAt").isNotEmpty());

		mockMvc.perform(patch("/api/bookings/{id}/confirm", withoutProposal)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isBadRequest());
		Instant acceptedTime = Instant.now().plusSeconds(7200);
		mockMvc.perform(patch("/api/bookings/{id}/confirm", withoutProposal)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("startsAt", acceptedTime))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.startsAt").isNotEmpty());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT service_name_snapshot FROM bookings WHERE id = ?",
				String.class,
				withoutProposal)).isEqualTo("Original service");
	}

	@Test
	@Transactional
	@Rollback
	void queueConfirmationDoesNotCreateAnAppointmentTime() throws Exception {
		LocalDate date = LocalDate.of(2031, 4, 10);
		UUID id = insertBooking(
				BookingMode.DAY_QUEUE,
				null,
				date,
				1,
				BookingStatus.PENDING,
				"Queue guest",
				null);

		mockMvc.perform(patch("/api/bookings/{id}/confirm", id)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.startsAt").doesNotExist())
				.andExpect(jsonPath("$.queuePosition").value(1));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT starts_at IS NULL FROM bookings WHERE id = ?",
				Boolean.class,
				id)).isTrue();
	}

	@Test
	@Transactional
	@Rollback
	void invalidLifecycleTransitionsReturnConflictAndMissingBookingsReturnNotFound() throws Exception {
		UUID pending = insertBooking(
				BookingMode.CONFIRMATION_REQUEST,
				null,
				null,
				null,
				BookingStatus.PENDING,
				"Guest",
				null);
		UUID cancelled = insertBooking(
				BookingMode.EXACT_APPOINTMENT,
				Instant.now().plusSeconds(3600),
				null,
				null,
				BookingStatus.CANCELLED,
				"Cancelled guest",
				null);

		mockMvc.perform(patch("/api/bookings/{id}/complete", pending)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isConflict());
		mockMvc.perform(patch("/api/bookings/{id}/confirm", cancelled)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isConflict());
		MvcResult missing = mockMvc.perform(patch("/api/bookings/{id}/confirm", UUID.randomUUID())
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isNotFound())
				.andReturn();
		assertThat(missing.getResponse().getContentAsString()).doesNotContain("SQL");
	}

	@Test
	@Transactional
	@Rollback
	void cancellationAndCompletionReleaseConfirmedAppointmentIntervals() throws Exception {
		Instant startsAt = Instant.now().plusSeconds(3600);
		UUID cancelled = insertBooking(
				BookingMode.EXACT_APPOINTMENT,
				startsAt,
				null,
				null,
				BookingStatus.CONFIRMED,
				"Cancelled appointment",
				null);
		mockMvc.perform(patch("/api/bookings/{id}/cancel", cancelled)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT appointment_end_at IS NULL FROM bookings WHERE id = ?",
				Boolean.class,
				cancelled)).isTrue();
		UUID replacement = insertBooking(
				BookingMode.EXACT_APPOINTMENT,
				startsAt,
				null,
				null,
				BookingStatus.PENDING,
				"Replacement appointment",
				null);
		mockMvc.perform(patch("/api/bookings/{id}/confirm", replacement)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isOk());

		Instant secondStart = Instant.now().minusSeconds(10);
		UUID completed = insertBooking(
				BookingMode.EXACT_APPOINTMENT,
				secondStart,
				null,
				null,
				BookingStatus.CONFIRMED,
				"Completed appointment",
				null);
		mockMvc.perform(patch("/api/bookings/{id}/complete", completed)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT appointment_end_at IS NULL FROM bookings WHERE id = ?",
				Boolean.class,
				completed)).isTrue();
		UUID futureAppointment = insertBooking(
				BookingMode.EXACT_APPOINTMENT,
				startsAt.plusSeconds(7200),
				null,
				null,
				BookingStatus.CONFIRMED,
				"Future appointment",
				null);
		mockMvc.perform(patch("/api/bookings/{id}/complete", futureAppointment)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Booking has not started"));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM bookings WHERE id = ?",
				String.class,
				futureAppointment)).isEqualTo("CONFIRMED");

		UUID confirmedQueue = insertBooking(
				BookingMode.DAY_QUEUE,
				null,
				LocalDate.now(SHOP_ZONE),
				1,
				BookingStatus.CONFIRMED,
				"Queue appointment",
				null);
		mockMvc.perform(patch("/api/bookings/{id}/complete", confirmedQueue)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.startsAt").doesNotExist());
	}

	@Test
	@Transactional
	@Rollback
	void disabledNewSubmissionsDoNotBlockConfirmationOfExistingBookings() throws Exception {
		UUID id = insertBooking(
				BookingMode.CONFIRMATION_REQUEST,
				null,
				null,
				null,
				BookingStatus.PENDING,
				"Existing guest",
				null);
		jdbcTemplate.update("UPDATE booking_settings SET new_bookings_disabled = TRUE WHERE singleton_key = TRUE");

		mockMvc.perform(patch("/api/bookings/{id}/confirm", id)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("startsAt", Instant.now().plusSeconds(3600)))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	void conflictingConcurrentConfirmationsAllowExactlyOneWinner() throws Exception {
		UUID serviceId = insertService();
		Instant startsAt = Instant.now().plusSeconds(3600);
		UUID first = insertBooking(serviceId, BookingMode.EXACT_APPOINTMENT, startsAt, null, null, 45);
		UUID second = insertBooking(serviceId, BookingMode.EXACT_APPOINTMENT, startsAt, null, null, 45);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<MvcResult> firstResult = executor.submit(() -> concurrentConfirm(first, ready, start));
			Future<MvcResult> secondResult = executor.submit(() -> concurrentConfirm(second, ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			MvcResult firstResponse = firstResult.get(20, TimeUnit.SECONDS);
			MvcResult secondResponse = secondResult.get(20, TimeUnit.SECONDS);
			int firstStatus = firstResponse.getResponse().getStatus();
			int secondStatus = secondResponse.getResponse().getStatus();
			assertThat(List.of(firstStatus, secondStatus))
					.containsExactlyInAnyOrder(200, 409);
			String conflictBody = firstStatus == 409
					? firstResponse.getResponse().getContentAsString()
					: secondResponse.getResponse().getContentAsString();
			assertThat(conflictBody)
					.contains("Appointment time unavailable")
					.doesNotContain("ex_bookings_confirmed_appointment_no_overlap");
			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(*) FROM bookings WHERE id IN (?, ?) AND status = 'CONFIRMED'",
					Integer.class,
					first,
					second)).isEqualTo(1);
		} finally {
			start.countDown();
			executor.shutdownNow();
			cleanupBookings(List.of(first, second));
			jdbcTemplate.update("DELETE FROM services WHERE id = ?", serviceId);
		}
	}

	@Test
	@Transactional
	@Rollback
	void queueReorderRequiresTheCompleteActiveQueueAndKeepsPositionsUnique() throws Exception {
		LocalDate date = LocalDate.of(2031, 5, 2);
		UUID first = insertBooking(BookingMode.DAY_QUEUE, null, date, 1, BookingStatus.PENDING, "First", null);
		UUID second = insertBooking(BookingMode.SAME_DAY_QUEUE, null, date, 2, BookingStatus.CONFIRMED, "Second", null);
		UUID inactive = insertBooking(BookingMode.DAY_QUEUE, null, date, 3, BookingStatus.CANCELLED, "Inactive", null);
		UUID completed = insertBooking(BookingMode.DAY_QUEUE, null, date, 4, BookingStatus.COMPLETED, "Completed", null);

		mockMvc.perform(patch("/api/bookings/queue/reorder")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("date", date, "bookingIds", List.of(second, first)))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(second.toString()))
				.andExpect(jsonPath("$[1].id").value(first.toString()));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT queue_position FROM bookings WHERE id = ?",
				Integer.class,
				first)).isEqualTo(6);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT queue_position FROM bookings WHERE id = ?",
				Integer.class,
				second)).isEqualTo(5);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(DISTINCT queue_position) FROM bookings WHERE queue_date = ?",
				Integer.class,
				date)).isEqualTo(4);

		mockMvc.perform(patch("/api/bookings/queue/reorder")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("date", date, "bookingIds", List.of(first, first)))))
				.andExpect(status().isBadRequest());
		mockMvc.perform(patch("/api/bookings/queue/reorder")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("date", date, "bookingIds", List.of(first)))))
				.andExpect(status().isBadRequest());
		UUID foreignDate = insertBooking(
				BookingMode.DAY_QUEUE,
				null,
				date.plusDays(1),
				1,
				BookingStatus.PENDING,
				"Other date guest",
				null);
		mockMvc.perform(patch("/api/bookings/queue/reorder")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("date", date, "bookingIds", List.of(first, second, foreignDate)))))
				.andExpect(status().isBadRequest());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT queue_position FROM bookings WHERE id = ?",
				Integer.class,
				inactive)).isEqualTo(3);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT queue_position FROM bookings WHERE id = ?",
				Integer.class,
				completed)).isEqualTo(4);
	}

	@Test
	void queueReorderSerializesWithNewQueueAllocation() throws Exception {
		Map<String, Object> originalSettings = jdbcTemplate.queryForMap("""
				SELECT exact_appointment_enabled, confirmation_request_enabled,
				       day_queue_enabled, same_day_queue_enabled, new_bookings_disabled
				FROM booking_settings WHERE singleton_key = TRUE
				""");
		UUID serviceId = insertService();
		LocalDate date = LocalDate.now(SHOP_ZONE)
				.plusDays(1825 + ThreadLocalRandom.current().nextInt(10_000));
		Integer previousCounter = jdbcTemplate.query(
				"SELECT last_position FROM booking_queue_counters WHERE queue_date = ?",
				result -> result.next() ? result.getInt(1) : null,
				date);
		UUID first = insertBooking(serviceId, BookingMode.DAY_QUEUE, null, date, 1, 45);
		UUID second = insertBooking(serviceId, BookingMode.SAME_DAY_QUEUE, null, date, 2, 45);
		jdbcTemplate.update("""
				UPDATE booking_settings SET day_queue_enabled = TRUE,
				    same_day_queue_enabled = TRUE, new_bookings_disabled = FALSE
				WHERE singleton_key = TRUE
				""");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		String key = "management-queue-race-" + UUID.randomUUID();
		String nextKey = "management-queue-next-" + UUID.randomUUID();

		try {
			Future<MvcResult> reorder = executor.submit(() -> concurrentReorder(date, List.of(second, first), ready, start));
			Future<MvcResult> allocation = executor.submit(() -> concurrentQueueCreation(
					serviceId, date, key, ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int reorderStatus = reorder.get(20, TimeUnit.SECONDS).getResponse().getStatus();
			MvcResult allocated = allocation.get(20, TimeUnit.SECONDS);
			assertThat(reorderStatus).isIn(200, 400);
			assertThat(allocated.getResponse().getStatus()).isEqualTo(201);

			UUID allocatedId = UUID.fromString(
					objectMapper.readTree(allocated.getResponse().getContentAsString())
							.get("bookingId").asText());
			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(*) FROM (SELECT queue_position FROM bookings WHERE queue_date = ? GROUP BY queue_position HAVING count(*) > 1) duplicates",
					Integer.class,
					date)).isZero();
			Integer maximum = jdbcTemplate.queryForObject(
					"SELECT max(queue_position) FROM bookings WHERE queue_date = ?",
					Integer.class,
					date);
			Integer counter = jdbcTemplate.queryForObject(
					"SELECT last_position FROM booking_queue_counters WHERE queue_date = ?",
					Integer.class,
					date);
			assertThat(counter).isGreaterThanOrEqualTo(maximum);

			MvcResult nextAllocation = concurrentDayQueueCreation(serviceId, date, nextKey);
			assertThat(nextAllocation.getResponse().getStatus()).isEqualTo(201);
			Integer nextPosition = objectMapper.readTree(nextAllocation.getResponse().getContentAsString())
					.get("queuePosition").asInt();
			assertThat(nextPosition).isGreaterThan(maximum);
			UUID nextBookingId = UUID.fromString(
					objectMapper.readTree(nextAllocation.getResponse().getContentAsString())
							.get("bookingId").asText());

			cleanupBookings(List.of(first, second, allocatedId, nextBookingId));
		} finally {
			start.countDown();
			executor.shutdownNow();
			List<UUID> createdIds = jdbcTemplate.query("""
					SELECT booking_id FROM booking_idempotency
					WHERE idempotency_key IN (?, ?) AND booking_id IS NOT NULL
					""",
					(result, row) -> result.getObject(1, UUID.class),
					key,
					nextKey);
			jdbcTemplate.update(
					"DELETE FROM booking_idempotency WHERE idempotency_key IN (?, ?)",
					key,
					nextKey);
			List<UUID> knownIds = new java.util.ArrayList<>(List.of(first, second));
			knownIds.addAll(createdIds);
			cleanupBookings(knownIds);
			if (previousCounter == null) {
				jdbcTemplate.update("""
						DELETE FROM booking_queue_counters
						WHERE queue_date = ?
						  AND NOT EXISTS (SELECT 1 FROM bookings WHERE queue_date = ?)
						""",
						date,
						date);
			} else {
				jdbcTemplate.update(
						"UPDATE booking_queue_counters SET last_position = ? WHERE queue_date = ?",
						previousCounter,
						date);
			}
			jdbcTemplate.update("""
					UPDATE booking_settings
					SET exact_appointment_enabled = ?, confirmation_request_enabled = ?,
					    day_queue_enabled = ?, same_day_queue_enabled = ?, new_bookings_disabled = ?
					WHERE singleton_key = TRUE
					""",
					originalSettings.get("exact_appointment_enabled"),
					originalSettings.get("confirmation_request_enabled"),
					originalSettings.get("day_queue_enabled"),
					originalSettings.get("same_day_queue_enabled"),
					originalSettings.get("new_bookings_disabled"));
			jdbcTemplate.update("DELETE FROM services WHERE id = ?", serviceId);
		}
	}

	private MvcResult concurrentConfirm(UUID bookingId, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Timed out waiting to start concurrent confirmation.");
		}
		return mockMvc.perform(patch("/api/bookings/{id}/confirm", bookingId)
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf()))
				.andReturn();
	}

	private MvcResult concurrentReorder(
			LocalDate date,
			List<UUID> ids,
			CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Timed out waiting to start concurrent queue reorder.");
		}
		return mockMvc.perform(patch("/api/bookings/queue/reorder")
						.with(SecurityMockMvcRequestPostProcessors.user("manager").roles("OWNER"))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("date", date, "bookingIds", ids))))
				.andReturn();
	}

	private MvcResult concurrentQueueCreation(
			UUID serviceId,
			LocalDate date,
			String key,
			CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Timed out waiting to start concurrent queue allocation.");
		}
		String request = json(Map.of(
				"bookingMode", "DAY_QUEUE",
				"serviceId", serviceId,
				"customerName", "Concurrent queue guest",
				"queueDate", date));
		return mockMvc.perform(post("/api/bookings")
						.with(csrf())
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(request))
				.andReturn();
	}

	private MvcResult concurrentDayQueueCreation(UUID serviceId, LocalDate date, String key) throws Exception {
		String request = json(Map.of(
				"bookingMode", "DAY_QUEUE",
				"serviceId", serviceId,
				"customerName", "Future queue guest",
				"queueDate", date));
		return mockMvc.perform(post("/api/bookings")
						.with(csrf())
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(request))
				.andReturn();
	}

	private UUID insertBooking(
			BookingMode mode,
			Instant startsAt,
			LocalDate queueDate,
			Integer queuePosition,
			BookingStatus status,
			String customerName,
			String phone) {
		UUID serviceId = insertService();
		return insertBooking(
				serviceId,
				mode,
				startsAt,
				queueDate,
				queuePosition,
				status,
				customerName,
				phone,
				45);
	}

	private UUID insertBooking(
			UUID serviceId,
			BookingMode mode,
			Instant startsAt,
			LocalDate queueDate,
			Integer queuePosition,
			int duration) {
		return insertBooking(
				serviceId,
				mode,
				startsAt,
				queueDate,
				queuePosition,
				BookingStatus.PENDING,
				"Queue guest",
				null,
				duration);
	}

	private UUID insertBooking(
			UUID serviceId,
			BookingMode mode,
			Instant startsAt,
			LocalDate queueDate,
			Integer queuePosition,
			BookingStatus status,
			String customerName,
			String phone,
			int duration) {
		UUID customerId = UUID.randomUUID();
		UUID bookingId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO customers (id, name, phone_number) VALUES (?, ?, ?)",
				customerId,
				customerName,
				phone);
		jdbcTemplate.update("""
				INSERT INTO bookings (
				    id, customer_id, service_id, booking_mode, starts_at, status,
				    queue_date, queue_position, service_name_snapshot,
				    service_price_snapshot, service_duration_snapshot_minutes
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Original service', 27.50, ?)
				""",
				bookingId,
				customerId,
				serviceId,
				mode.name(),
				startsAt == null ? null : java.sql.Timestamp.from(startsAt),
				status.name(),
				queueDate,
				queuePosition,
				duration);
		if (queueDate != null && queuePosition != null) {
			jdbcTemplate.update("""
					INSERT INTO booking_queue_counters (queue_date, last_position)
					VALUES (?, ?)
					ON CONFLICT (queue_date) DO UPDATE
					SET last_position = GREATEST(booking_queue_counters.last_position, EXCLUDED.last_position)
					""",
					queueDate,
					queuePosition);
		}
		return bookingId;
	}

	private UUID insertService() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO services (id, name, description, price, duration_minutes, active)
				VALUES (?, 'Original service', 'Management test service', 27.50, 45, TRUE)
				""",
				id);
		return id;
	}

	private void cleanupBookings(List<UUID> bookingIds) {
		if (bookingIds.isEmpty()) {
			return;
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(bookingIds.size(), "?"));
		List<UUID> customerIds = jdbcTemplate.query(
				"SELECT customer_id FROM bookings WHERE id IN (" + placeholders + ") AND customer_id IS NOT NULL",
				(result, row) -> result.getObject(1, UUID.class),
				bookingIds.toArray());
		jdbcTemplate.update(
				"DELETE FROM booking_idempotency WHERE booking_id IN (" + placeholders + ")",
				bookingIds.toArray());
		jdbcTemplate.update(
				"DELETE FROM bookings WHERE id IN (" + placeholders + ")",
				bookingIds.toArray());
		if (!customerIds.isEmpty()) {
			String customerPlaceholders = String.join(",", java.util.Collections.nCopies(customerIds.size(), "?"));
			jdbcTemplate.update(
					"DELETE FROM customers WHERE id IN (" + customerPlaceholders + ")",
					customerIds.toArray());
		}
	}

	private UUID insertBooking(
			UUID serviceId,
			BookingMode mode,
			Instant startsAt,
			LocalDate date,
			Integer position,
			BookingStatus status,
			String customerName,
			String phone) {
		return insertBooking(
				serviceId, mode, startsAt, date, position, status, customerName, phone, 45);
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("Test request could not be serialized.", exception);
		}
	}
}
