package com.barbershop.backend.booking;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BookingMigrationV3IntegrationTests {

	private static final UUID SERVICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
	private static final UUID LEGACY_BOOKING_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
	private static final Instant LEGACY_START = Instant.parse("2030-02-03T08:00:00Z");
	private static final Instant LEGACY_CREATED = Instant.parse("2025-01-02T03:04:05Z");
	private static final Instant LEGACY_UPDATED = Instant.parse("2025-02-03T04:05:06Z");

	@Autowired
	private DataSource dataSource;

	@Test
	void migratesPopulatedV2SchemaAndEnforcesV3Constraints() throws Exception {
		String schema = createIsolatedSchema();
		try {
			migrateToVersion(schema, "2");
			insertV2Fixtures(schema, false);
			migrateToLatest(schema);

			try (Connection connection = connectToSchema(schema)) {
				assertLegacyBookingPreserved(connection);
				assertThat(scalarInt(connection, """
						SELECT count(*) FROM booking_settings
						WHERE id = '00000000-0000-0000-0000-000000000001'
						  AND singleton_key = TRUE
						""")).isEqualTo(1);

				assertBookingModeConstraints(connection);
				assertQueueConstraintsAndReordering(connection);
				assertConcurrentQueueAllocation(schema);
				assertIdempotencyConstraints(connection);
			}
		} finally {
			dropSchema(schema);
		}
	}

	@Test
	void refusesOverlappingLegacyAppointmentsWithoutChangingV2Rows() throws Exception {
		String schema = createIsolatedSchema();
		try {
			migrateToVersion(schema, "2");
			insertV2Fixtures(schema, true);

			assertThatThrownBy(() -> migrateToLatest(schema))
					.satisfies(exception -> assertThat(rootCause(exception).getMessage())
							.contains("V3 cannot enforce confirmed appointment exclusivity because existing bookings overlap."));

			try (Connection connection = connectToSchema(schema)) {
				assertThat(scalarInt(connection, "SELECT count(*) FROM bookings")).isEqualTo(2);
				assertThat(scalarInt(connection, """
						SELECT count(*) FROM information_schema.columns
						WHERE table_schema = current_schema()
						  AND table_name = 'bookings'
						  AND column_name = 'booking_mode'
						""")).isZero();
				assertThat(scalarInt(connection, """
						SELECT count(*) FROM bookings
						WHERE status = 'CONFIRMED'
						  AND starts_at IS NOT NULL
						  AND service_name_snapshot = 'Original cut'
						  AND service_price_snapshot = 25.50
						  AND service_duration_snapshot_minutes = 45
						""")).isEqualTo(2);
			}
		} finally {
			dropSchema(schema);
		}
	}

	private String createIsolatedSchema() throws SQLException {
		String schema = "migration_v3_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA " + schema);
		}
		return schema;
	}

	private void dropSchema(String schema) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	private void migrateToVersion(String schema, String version) {
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.target(MigrationVersion.fromVersion(version))
				.load()
				.migrate();
	}

	private void migrateToLatest(String schema) {
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.load()
				.migrate();
	}

	private Connection connectToSchema(String schema) throws SQLException {
		Connection connection = dataSource.getConnection();
		try (Statement statement = connection.createStatement()) {
			statement.execute("SET search_path TO " + schema + ", public");
		}
		return connection;
	}

	private void insertV2Fixtures(String schema, boolean overlapping) throws SQLException {
		try (Connection connection = connectToSchema(schema)) {
			try (PreparedStatement service = connection.prepareStatement("""
					INSERT INTO services (id, name, description, price, duration_minutes)
					VALUES (?, 'Original cut', 'Original service', 25.50, 45)
					""")) {
				service.setObject(1, SERVICE_ID);
				service.executeUpdate();
			}
			try (PreparedStatement customer = connection.prepareStatement("""
					INSERT INTO customers (id, name, phone_number, created_at)
					VALUES (?, 'Guest customer', NULL, ?)
					""")) {
				customer.setObject(1, CUSTOMER_ID);
				customer.setTimestamp(2, Timestamp.from(LEGACY_CREATED));
				customer.executeUpdate();
			}
			insertV2Booking(connection, LEGACY_BOOKING_ID, LEGACY_START, "CONFIRMED");
			if (overlapping) {
				insertV2Booking(
						connection,
						UUID.fromString("00000000-0000-0000-0000-000000000104"),
						LEGACY_START.plusSeconds(30 * 60L),
						"CONFIRMED");
			}
		}
	}

	private void insertV2Booking(Connection connection, UUID id, Instant startsAt, String status)
			throws SQLException {
		try (PreparedStatement booking = connection.prepareStatement("""
				INSERT INTO bookings (
				    id, customer_id, service_id, starts_at, status, notes,
				    service_name_snapshot, service_price_snapshot,
				    service_duration_snapshot_minutes, created_at, updated_at
				)
				VALUES (?, ?, ?, ?, ?, 'historical note', 'Original cut', 25.50, 45, ?, ?)
				""")) {
			booking.setObject(1, id);
			booking.setObject(2, CUSTOMER_ID);
			booking.setObject(3, SERVICE_ID);
			booking.setTimestamp(4, Timestamp.from(startsAt));
			booking.setString(5, status);
			booking.setTimestamp(6, Timestamp.from(LEGACY_CREATED));
			booking.setTimestamp(7, Timestamp.from(LEGACY_UPDATED));
			booking.executeUpdate();
		}
	}

	private void assertLegacyBookingPreserved(Connection connection) throws SQLException {
		try (PreparedStatement query = connection.prepareStatement("""
				SELECT id, customer_id, service_id, starts_at, status, notes,
				       service_name_snapshot, service_price_snapshot,
				       service_duration_snapshot_minutes, created_at, updated_at,
				       booking_mode, queue_date, queue_position, appointment_end_at
				FROM bookings WHERE id = ?
				""")) {
			query.setObject(1, LEGACY_BOOKING_ID);
			try (ResultSet result = query.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getObject("id", UUID.class)).isEqualTo(LEGACY_BOOKING_ID);
				assertThat(result.getObject("customer_id", UUID.class)).isEqualTo(CUSTOMER_ID);
				assertThat(result.getObject("service_id", UUID.class)).isEqualTo(SERVICE_ID);
				assertThat(result.getTimestamp("starts_at").toInstant()).isEqualTo(LEGACY_START);
				assertThat(result.getString("status")).isEqualTo("CONFIRMED");
				assertThat(result.getString("notes")).isEqualTo("historical note");
				assertThat(result.getString("service_name_snapshot")).isEqualTo("Original cut");
				assertThat(result.getBigDecimal("service_price_snapshot"))
						.isEqualByComparingTo(new BigDecimal("25.50"));
				assertThat(result.getInt("service_duration_snapshot_minutes")).isEqualTo(45);
				assertThat(result.getTimestamp("created_at").toInstant()).isEqualTo(LEGACY_CREATED);
				assertThat(result.getTimestamp("updated_at").toInstant()).isEqualTo(LEGACY_UPDATED);
				assertThat(result.getString("booking_mode")).isEqualTo("LEGACY");
				assertThat(result.getDate("queue_date")).isNull();
				assertThat(result.getObject("queue_position")).isNull();
				assertThat(result.getTimestamp("appointment_end_at").toInstant())
						.isEqualTo(LEGACY_START.plusSeconds(45 * 60L));
				assertThat(result.next()).isFalse();
			}
		}
	}

	private void assertBookingModeConstraints(Connection connection) throws SQLException {
		UUID pendingExact = UUID.fromString("00000000-0000-0000-0000-000000000201");
		insertV3Booking(connection, pendingExact, "PENDING", "EXACT_APPOINTMENT",
				LEGACY_START, null, null, 20);
		assertThat(readAppointmentEnd(connection, pendingExact)).isNull();

		UUID noTimeRequest = UUID.fromString("00000000-0000-0000-0000-000000000202");
		insertV3Booking(connection, noTimeRequest, "PENDING", "CONFIRMATION_REQUEST",
				null, null, null, 20);
		assertThat(readAppointmentEnd(connection, noTimeRequest)).isNull();

		UUID cancelledConfirmation = UUID.fromString("00000000-0000-0000-0000-000000000203");
		insertV3Booking(connection, cancelledConfirmation, "CANCELLED", "CONFIRMATION_REQUEST",
				null, null, null, 20);
		assertThat(readAppointmentEnd(connection, cancelledConfirmation)).isNull();

		assertSqlState("23514", () -> insertV3Booking(connection,
				UUID.fromString("00000000-0000-0000-0000-000000000204"),
				"PENDING", "EXACT_APPOINTMENT", null, null, null, 20));
		assertSqlState("23514", () -> insertV3Booking(connection,
				UUID.fromString("00000000-0000-0000-0000-000000000205"),
				"CONFIRMED", "CONFIRMATION_REQUEST", null, null, null, 20));

		UUID overlappingPending = UUID.fromString("00000000-0000-0000-0000-000000000206");
		insertV3Booking(connection, overlappingPending, "PENDING", "EXACT_APPOINTMENT",
				LEGACY_START, null, null, 45);
		assertThat(readAppointmentEnd(connection, overlappingPending)).isNull();

		UUID adjacentConfirmed = UUID.fromString("00000000-0000-0000-0000-000000000207");
		insertV3Booking(connection, adjacentConfirmed, "CONFIRMED", "EXACT_APPOINTMENT",
				LEGACY_START.plusSeconds(45 * 60L), null, null, 15);
		assertThat(readAppointmentEnd(connection, adjacentConfirmed))
				.isEqualTo(LEGACY_START.plusSeconds(60 * 60L));

		assertSqlState("23P01", () -> insertV3Booking(connection,
				UUID.fromString("00000000-0000-0000-0000-000000000208"),
				"CONFIRMED", "EXACT_APPOINTMENT",
				LEGACY_START.plusSeconds(15 * 60L), null, null, 30));

		try (PreparedStatement cancel = connection.prepareStatement(
				"UPDATE bookings SET status = 'CANCELLED' WHERE id = ?")) {
			cancel.setObject(1, adjacentConfirmed);
			cancel.executeUpdate();
		}
		assertThat(readAppointmentEnd(connection, adjacentConfirmed)).isNull();
	}

	private void assertQueueConstraintsAndReordering(Connection connection) throws SQLException {
		Date queueDate = Date.valueOf("2030-02-03");
		UUID dayQueue = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID sameDayQueue = UUID.fromString("00000000-0000-0000-0000-000000000302");
		insertV3Booking(connection, dayQueue, "PENDING", "DAY_QUEUE",
				null, queueDate, 1, 20);
		insertV3Booking(connection, sameDayQueue, "PENDING", "SAME_DAY_QUEUE",
				null, queueDate, 2, 20);

		assertSqlState("23505", () -> insertV3Booking(connection,
				UUID.fromString("00000000-0000-0000-0000-000000000303"),
				"PENDING", "SAME_DAY_QUEUE", null, queueDate, 1, 20));
		assertSqlState("23514", () -> insertV3Booking(connection,
				UUID.fromString("00000000-0000-0000-0000-000000000304"),
				"PENDING", "DAY_QUEUE", null, queueDate, 0, 20));

		assertThat(scalarInt(connection, """
				SELECT count(*) FROM pg_constraint
				WHERE conname = 'uq_bookings_queue_date_position'
				  AND connamespace = current_schema()::regnamespace
				  AND condeferrable
				""")).isEqualTo(1);

		connection.setAutoCommit(false);
		try (Statement statement = connection.createStatement()) {
			statement.execute("SET CONSTRAINTS uq_bookings_queue_date_position DEFERRED");
		}
		try (PreparedStatement swapFirst = connection.prepareStatement(
				"UPDATE bookings SET queue_position = 2 WHERE id = ?");
				PreparedStatement swapSecond = connection.prepareStatement(
						"UPDATE bookings SET queue_position = 1 WHERE id = ?")) {
			swapFirst.setObject(1, dayQueue);
			swapFirst.executeUpdate();
			swapSecond.setObject(1, sameDayQueue);
			swapSecond.executeUpdate();
		}
		connection.commit();
		connection.setAutoCommit(true);
	}

	private void assertIdempotencyConstraints(Connection connection) throws SQLException {
		UUID id = UUID.fromString("00000000-0000-0000-0000-000000000401");
		try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO booking_idempotency (
				    id, operation_scope, idempotency_key, request_fingerprint,
				    response_status, response_body, response_location, booking_id
				)
				VALUES (?, 'POST:/api/bookings:v1', 'test-key', ?, 201,
				        '{"bookingId":"public-id","status":"PENDING"}'::jsonb, '/api/bookings/public-id', ?)
				""")) {
			insert.setObject(1, id);
			insert.setBytes(2, new byte[32]);
			insert.setObject(3, LEGACY_BOOKING_ID);
			insert.executeUpdate();
		}

		assertSqlState("23505", () -> {
			try (PreparedStatement duplicate = connection.prepareStatement("""
					INSERT INTO booking_idempotency (
					    id, operation_scope, idempotency_key, request_fingerprint,
					    response_status, response_body
					)
					VALUES (?, 'POST:/api/bookings:v1', 'test-key', ?, 201, '{}'::jsonb)
					""")) {
				duplicate.setObject(1, UUID.fromString("00000000-0000-0000-0000-000000000402"));
				duplicate.setBytes(2, new byte[32]);
				duplicate.executeUpdate();
			}
		});

		assertSqlState("23514", () -> {
			try (PreparedStatement piiResponse = connection.prepareStatement("""
					INSERT INTO booking_idempotency (
					    id, operation_scope, idempotency_key, request_fingerprint,
					    response_status, response_body
					)
					VALUES (?, 'POST:/api/bookings:v1', 'pii-key', ?, 201,
					        '{"customerPhone":"not-allowed"}'::jsonb)
					""")) {
				piiResponse.setObject(1, UUID.fromString("00000000-0000-0000-0000-000000000403"));
				piiResponse.setBytes(2, new byte[32]);
				piiResponse.executeUpdate();
			}
		});
	}

	private void assertConcurrentQueueAllocation(String schema) throws Exception {
		Date queueDate = Date.valueOf("2030-02-04");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<Integer> first = executor.submit(() -> allocateQueuePosition(
					schema,
					queueDate,
					UUID.fromString("00000000-0000-0000-0000-000000000501"),
					ready,
					start));
			Future<Integer> second = executor.submit(() -> allocateQueuePosition(
					schema,
					queueDate,
					UUID.fromString("00000000-0000-0000-0000-000000000502"),
					ready,
					start));

			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(1, 2);
			try (Connection connection = connectToSchema(schema)) {
				assertThat(scalarInt(connection, """
						SELECT count(*) FROM booking_queue_counters
						WHERE queue_date = DATE '2030-02-04' AND last_position = 2
						""")).isEqualTo(1);
			}
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}

	private int allocateQueuePosition(
			String schema,
			Date queueDate,
			UUID bookingId,
			CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Timed out waiting to start concurrent queue allocation.");
		}

		try (Connection connection = connectToSchema(schema)) {
			connection.setAutoCommit(false);
			int position;
			try (PreparedStatement allocate = connection.prepareStatement("""
					INSERT INTO booking_queue_counters (queue_date, last_position)
					VALUES (?, 1)
					ON CONFLICT (queue_date) DO UPDATE
					SET last_position = booking_queue_counters.last_position + 1,
					    updated_at = CURRENT_TIMESTAMP
					RETURNING last_position
					""")) {
				allocate.setDate(1, queueDate);
				try (ResultSet result = allocate.executeQuery()) {
					assertThat(result.next()).isTrue();
					position = result.getInt(1);
				}
			}

			insertV3Booking(connection, bookingId, "PENDING", "DAY_QUEUE",
					null, queueDate, position, 20);
			connection.commit();
			return position;
		}
	}

	private void insertV3Booking(
			Connection connection,
			UUID id,
			String status,
			String mode,
			Instant startsAt,
			Date queueDate,
			Integer queuePosition,
			int durationMinutes) throws SQLException {
		try (PreparedStatement booking = connection.prepareStatement("""
				INSERT INTO bookings (
				    id, service_id, starts_at, status, service_name_snapshot,
				    service_price_snapshot, service_duration_snapshot_minutes,
				    booking_mode, queue_date, queue_position
				)
				VALUES (?, ?, ?, ?, 'Snapshot', 12.50, ?, ?, ?, ?)
				""")) {
			booking.setObject(1, id);
			booking.setObject(2, SERVICE_ID);
			booking.setTimestamp(3, startsAt == null ? null : Timestamp.from(startsAt));
			booking.setString(4, status);
			booking.setInt(5, durationMinutes);
			booking.setString(6, mode);
			booking.setDate(7, queueDate);
			if (queuePosition == null) {
				booking.setObject(8, null);
			} else {
				booking.setInt(8, queuePosition);
			}
			booking.executeUpdate();
		}
	}

	private Instant readAppointmentEnd(Connection connection, UUID bookingId) throws SQLException {
		try (PreparedStatement query = connection.prepareStatement(
				"SELECT appointment_end_at FROM bookings WHERE id = ?")) {
			query.setObject(1, bookingId);
			try (ResultSet result = query.executeQuery()) {
				assertThat(result.next()).isTrue();
				Timestamp end = result.getTimestamp(1);
				return end == null ? null : end.toInstant();
			}
		}
	}

	private int scalarInt(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getInt(1);
		}
	}

	private void assertSqlState(String sqlState, SqlOperation operation) {
		assertThatThrownBy(operation::run)
				.isInstanceOfSatisfying(SQLException.class,
						exception -> assertThat(exception.getSQLState()).isEqualTo(sqlState));
	}

	private static Throwable rootCause(Throwable exception) {
		Throwable cause = exception;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	@FunctionalInterface
	private interface SqlOperation {
		void run() throws SQLException;
	}
}
