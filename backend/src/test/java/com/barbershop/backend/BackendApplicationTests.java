package com.barbershop.backend;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import org.flywaydb.core.Flyway;

import com.barbershop.backend.booking.Booking;
import com.barbershop.backend.booking.BookingRepository;
import com.barbershop.backend.service.ServiceEntity;
import com.barbershop.backend.service.ServiceRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
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
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");

		Integer createdTables = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.tables
				WHERE table_schema = current_schema()
				  AND table_name IN (
				        'services', 'customers', 'bookings', 'booking_settings',
				        'management_accounts', 'customer_accounts', 'booking_payments',
				        'shop_expenses', 'inventory_products', 'inventory_movements',
				        'shop_employees', 'employee_commissions', 'employee_payments', 'shop_profile'
				  )
				  """, Integer.class);
		assertThat(createdTables).isEqualTo(14);

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

		Integer ownerRoleConstraints = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.table_constraints
				WHERE table_schema = current_schema()
				  AND table_name = 'management_accounts'
				  AND constraint_name IN (
				      'chk_management_accounts_role', 'uq_management_accounts_role'
				  )
				""", Integer.class);
		assertThat(ownerRoleConstraints).isEqualTo(2);

		Integer customerAccountUsernameConstraint = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.table_constraints
				WHERE table_schema = current_schema()
				  AND table_name = 'customer_accounts'
				  AND constraint_name = 'uq_customer_accounts_username'
				""", Integer.class);
		assertThat(customerAccountUsernameConstraint).isEqualTo(1);

		Integer customerBookingColumn = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.columns
				WHERE table_schema = current_schema()
				  AND table_name = 'bookings'
				  AND column_name = 'customer_account_id'
				""", Integer.class);
		assertThat(customerBookingColumn).isEqualTo(1);

		Integer bookingEmployeeColumn = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.columns
				WHERE table_schema = current_schema()
				  AND table_name = 'bookings'
				  AND column_name = 'employee_id'
				""", Integer.class);
		assertThat(bookingEmployeeColumn).isEqualTo(1);
	}

	@Test
	@Transactional
	@Rollback
	void bookingKeepsServiceSnapshotsWhenServiceChanges() {
		ServiceEntity service = serviceRepository.saveAndFlush(
				new ServiceEntity("Classic cut", "A sample service", new BigDecimal("25.50"), 45));
		java.util.UUID bookingId = java.util.UUID.randomUUID();
		Instant startsAt = Instant.now().plusSeconds(3600);
		jdbcTemplate.update("""
				INSERT INTO bookings (
				    id, service_id, starts_at, status, service_name_snapshot,
				    service_price_snapshot, service_duration_snapshot_minutes, booking_mode
				)
				VALUES (?, ?, ?, 'PENDING', ?, ?, ?, 'LEGACY')
				""",
				bookingId,
				service.getId(),
				java.sql.Timestamp.from(startsAt),
				service.getName(),
				service.getPrice(),
				service.getDurationMinutes());

		service.setName("Updated cut");
		service.setPrice(new BigDecimal("30.00"));
		service.setDurationMinutes(60);
		serviceRepository.flush();
		entityManager.clear();

		Booking reloadedBooking = bookingRepository.findById(bookingId).orElseThrow();
		assertThat(reloadedBooking.getServiceNameSnapshot()).isEqualTo("Classic cut");
		assertThat(reloadedBooking.getServicePriceSnapshot()).isEqualByComparingTo("25.50");
		assertThat(reloadedBooking.getServiceDurationSnapshotMinutes()).isEqualTo(45);
		assertThat(reloadedBooking.getService().getName()).isEqualTo("Updated cut");
	}

}
