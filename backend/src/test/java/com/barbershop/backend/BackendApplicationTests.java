package com.barbershop.backend;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import org.flywaydb.core.Flyway;

import com.barbershop.backend.booking.Booking;
import com.barbershop.backend.booking.BookingRepository;
import com.barbershop.backend.service.ServiceEntity;
import com.barbershop.backend.service.ServiceRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BackendApplicationTests {

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void migrationCreatesTheInitialDatabaseFoundation() {
		assertThat(flyway.info().current()).isNotNull();
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");

		Integer createdTables = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.tables
				WHERE table_schema = current_schema()
				  AND table_name IN ('services', 'customers', 'bookings', 'booking_settings')
				""", Integer.class);
		assertThat(createdTables).isEqualTo(4);

		Integer defaultSettings = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM booking_settings WHERE new_bookings_disabled = TRUE",
				Integer.class);
		assertThat(defaultSettings).isEqualTo(1);

		Integer settingsCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM booking_settings",
				Integer.class);
		assertThat(settingsCount).isEqualTo(1);

		Integer singletonConstraintCount = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.table_constraints
				WHERE table_schema = current_schema()
				  AND table_name = 'booking_settings'
				  AND constraint_name = 'uq_booking_settings_singleton_key'
				""", Integer.class);
		assertThat(singletonConstraintCount).isEqualTo(1);
	}

	@Test
	@Transactional
	@Rollback
	void bookingKeepsServiceSnapshotsWhenServiceChanges() {
		ServiceEntity service = serviceRepository.saveAndFlush(
				new ServiceEntity("Classic cut", "A sample service", new BigDecimal("25.50"), 45));
		Booking booking = bookingRepository.saveAndFlush(
				new Booking(null, service, Instant.now().plusSeconds(3600), null));

		service.setName("Updated cut");
		service.setPrice(new BigDecimal("30.00"));
		service.setDurationMinutes(60);
		serviceRepository.flush();
		entityManager.clear();

		Booking reloadedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
		assertThat(reloadedBooking.getServiceNameSnapshot()).isEqualTo("Classic cut");
		assertThat(reloadedBooking.getServicePriceSnapshot()).isEqualByComparingTo("25.50");
		assertThat(reloadedBooking.getServiceDurationSnapshotMinutes()).isEqualTo(45);
		assertThat(reloadedBooking.getService().getName()).isEqualTo("Updated cut");
	}

}
