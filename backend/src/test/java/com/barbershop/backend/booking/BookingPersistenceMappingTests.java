package com.barbershop.backend.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.barbershop.backend.service.ServiceEntity;
import com.barbershop.backend.service.ServiceRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BookingPersistenceMappingTests {

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Test
	@Transactional
	@Rollback
	void persistsModesAndNullableSchedulingFields() {
		ServiceEntity service = serviceRepository.saveAndFlush(
				new ServiceEntity("Classic cut", "A service", new BigDecimal("25.50"), 45));

		Booking confirmationRequest = bookingRepository.saveAndFlush(new Booking(
				null,
				service,
				BookingMode.CONFIRMATION_REQUEST,
				null,
				null,
				null,
				null));
		Booking dayQueue = bookingRepository.saveAndFlush(new Booking(
				null,
				service,
				BookingMode.DAY_QUEUE,
				null,
				LocalDate.of(2030, 2, 3),
				1,
				null));
		entityManager.clear();

		Booking loadedRequest = bookingRepository.findById(confirmationRequest.getId()).orElseThrow();
		assertThat(loadedRequest.getBookingMode()).isEqualTo(BookingMode.CONFIRMATION_REQUEST);
		assertThat(loadedRequest.getStartsAt()).isNull();
		assertThat(loadedRequest.getQueueDate()).isNull();
		assertThat(loadedRequest.getQueuePosition()).isNull();

		Booking loadedQueue = bookingRepository.findById(dayQueue.getId()).orElseThrow();
		assertThat(loadedQueue.getBookingMode()).isEqualTo(BookingMode.DAY_QUEUE);
		assertThat(loadedQueue.getStartsAt()).isNull();
		assertThat(loadedQueue.getQueueDate()).isEqualTo(LocalDate.of(2030, 2, 3));
		assertThat(loadedQueue.getQueuePosition()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT booking_mode FROM bookings WHERE id = ?",
				String.class,
				confirmationRequest.getId())).isEqualTo("CONFIRMATION_REQUEST");
	}

	@Test
	@Transactional
	@Rollback
	void loadsLegacyModeAndPreservesServiceSnapshotsAfterServiceChanges() {
		ServiceEntity service = serviceRepository.saveAndFlush(
				new ServiceEntity("Original cut", "A service", new BigDecimal("25.50"), 45));
		UUID bookingId = UUID.randomUUID();
		Instant startsAt = Instant.parse("2030-02-03T08:00:00Z");
		jdbcTemplate.update("""
				INSERT INTO bookings (
				    id, service_id, starts_at, status, service_name_snapshot,
				    service_price_snapshot, service_duration_snapshot_minutes, booking_mode
				)
				VALUES (?, ?, ?, 'PENDING', 'Original cut', 25.50, 45, 'LEGACY')
				""",
				bookingId,
				service.getId(),
				java.sql.Timestamp.from(startsAt));

		entityManager.clear();
		Booking legacyBooking = bookingRepository.findById(bookingId).orElseThrow();
		assertThat(legacyBooking.getBookingMode()).isEqualTo(BookingMode.LEGACY);
		assertThat(legacyBooking.getStartsAt()).isEqualTo(startsAt);

		service = serviceRepository.findById(service.getId()).orElseThrow();
		service.setName("Changed cut");
		service.setPrice(new BigDecimal("30.00"));
		service.setDurationMinutes(60);
		serviceRepository.flush();
		entityManager.clear();

		Booking reloaded = bookingRepository.findById(bookingId).orElseThrow();
		assertThat(reloaded.getServiceNameSnapshot()).isEqualTo("Original cut");
		assertThat(reloaded.getServicePriceSnapshot()).isEqualByComparingTo("25.50");
		assertThat(reloaded.getServiceDurationSnapshotMinutes()).isEqualTo(45);
		assertThat(reloaded.getService().getName()).isEqualTo("Changed cut");
		assertThat(reloaded.getServicePriceSnapshot()).isEqualByComparingTo("25.50");
	}
}
