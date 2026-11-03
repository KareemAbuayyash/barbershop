package com.barbershop.backend.booking;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ShopScheduleService {

	private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Hebron");
	private static final int SLOT_INTERVAL_MINUTES = 15;
	private static final TypeReference<Map<String, DayHours>> SCHEDULE_TYPE = new TypeReference<>() {
	};

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	public ShopScheduleService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	public void validateSchedule(String json) {
		if (parseSchedule(json).values().stream().noneMatch(hours -> hours != null)) {
			throw invalidSchedule("Invalid opening hours", "At least one working day must be configured.");
		}
	}

	public Availability availability(LocalDate date, UUID serviceId) {
		return availability(date, serviceId, null);
	}

	public Availability availabilityForBooking(LocalDate date, UUID serviceId, UUID bookingId) {
		return availability(date, serviceId, bookingId);
	}

	private Availability availability(LocalDate date, UUID serviceId, UUID excludedBookingId) {
		DayHours hours = openingHours(date);
		if (hours == null) {
			return new Availability(false, List.of());
		}
		if (serviceId == null) {
			return new Availability(true, List.of());
		}

		String durationQuery = excludedBookingId == null
				? "SELECT duration_minutes FROM services WHERE id = ? AND active = TRUE"
				: """
				  SELECT s.duration_minutes FROM services s
				  WHERE s.id = ? AND (s.active = TRUE OR EXISTS (
				      SELECT 1 FROM bookings b WHERE b.id = ? AND b.service_id = s.id))
				  """;
		Integer durationMinutes = excludedBookingId == null
				? jdbc.query(durationQuery, rs -> rs.next() ? rs.getInt(1) : null, serviceId)
				: jdbc.query(durationQuery, rs -> rs.next() ? rs.getInt(1) : null, serviceId, excludedBookingId);
		if (durationMinutes == null) {
			throw new BookingApiException(
					HttpStatus.NOT_FOUND, "Service not found", "The selected service is not available.");
		}

		LocalTime openTime = LocalTime.parse(hours.opensAt());
		LocalTime closeTime = LocalTime.parse(hours.closesAt());
		LocalDateTime opensAt = LocalDateTime.of(date, openTime);
		LocalDateTime closesAt = closingDateTime(date, openTime, closeTime);
		List<AppointmentWindow> existing = confirmedAppointments(
				opensAt.atZone(SHOP_ZONE).toInstant(),
				closesAt.atZone(SHOP_ZONE).toInstant(),
				excludedBookingId);
		List<AppointmentSlot> slots = new ArrayList<>();
		for (LocalDateTime slot = opensAt;
				!slot.plusMinutes(durationMinutes).isAfter(closesAt);
				slot = slot.plusMinutes(SLOT_INTERVAL_MINUTES)) {
			Instant start = slot.atZone(SHOP_ZONE).toInstant();
			Instant end = start.plus(Duration.ofMinutes(durationMinutes));
			if (start.isAfter(Instant.now()) && existing.stream().noneMatch(window ->
					start.isBefore(window.endsAt()) && end.isAfter(window.startsAt()))) {
				slots.add(new AppointmentSlot(start, slot.toLocalTime().toString()));
			}
		}
		return new Availability(true, slots);
	}

	public void requireOpenDate(LocalDate date) {
		if (openingHours(date) == null) {
			throw invalidSchedule("The shop is closed on the selected date.");
		}
	}

	public void requireAppointmentTime(Instant startsAt, int durationMinutes, UUID excludedBookingId) {
		LocalDateTime localStart = LocalDateTime.ofInstant(startsAt, SHOP_ZONE);
		DayHours hours = openingHours(localStart.toLocalDate());
		if (hours == null) {
			throw invalidSchedule("The shop is closed on the selected date.");
		}

		LocalDateTime localEnd = localStart.plusMinutes(durationMinutes);
		LocalTime openTime = LocalTime.parse(hours.opensAt());
		LocalTime closeTime = LocalTime.parse(hours.closesAt());
		LocalDateTime closingDateTime = closingDateTime(localStart.toLocalDate(), openTime, closeTime);
		int minuteOffset = Math.toIntExact(Duration.between(
				LocalDateTime.of(localStart.toLocalDate(), openTime), localStart).toMinutes());
		if (localStart.toLocalTime().isBefore(openTime)
				|| localEnd.isAfter(closingDateTime)
				|| minuteOffset < 0
				|| minuteOffset % SLOT_INTERVAL_MINUTES != 0
				|| localStart.getSecond() != 0
				|| localStart.getNano() != 0) {
			throw invalidSchedule("The selected time is outside the shop's opening hours or available time slots.");
		}

		Instant endsAt = startsAt.plus(Duration.ofMinutes(durationMinutes));
		if (confirmedAppointments(startsAt, endsAt, excludedBookingId).stream().anyMatch(window ->
				startsAt.isBefore(window.endsAt()) && endsAt.isAfter(window.startsAt()))) {
			throw new BookingApiException(
					HttpStatus.CONFLICT, "Appointment time unavailable", "The selected time is no longer available.");
		}
	}

	private List<AppointmentWindow> confirmedAppointments(
			Instant from,
			Instant to,
			UUID excludedBookingId) {
		String sql = """
				SELECT starts_at, appointment_end_at
				FROM bookings
				WHERE status = 'CONFIRMED'
				  AND starts_at < ?
				  AND appointment_end_at > ?
				""" + (excludedBookingId == null ? "" : " AND id <> ?");
		return excludedBookingId == null
				? jdbc.query(sql, (rs, row) -> new AppointmentWindow(
						rs.getTimestamp("starts_at").toInstant(),
						rs.getTimestamp("appointment_end_at").toInstant()),
						Timestamp.from(to), Timestamp.from(from))
				: jdbc.query(sql, (rs, row) -> new AppointmentWindow(
						rs.getTimestamp("starts_at").toInstant(),
						rs.getTimestamp("appointment_end_at").toInstant()),
						Timestamp.from(to), Timestamp.from(from), excludedBookingId);
	}

	private DayHours openingHours(LocalDate date) {
		String json = jdbc.queryForObject(
				"SELECT opening_hours::text FROM shop_profile WHERE singleton_key = TRUE",
				String.class);
		return parseSchedule(json).get(date.getDayOfWeek().name());
	}

	private Map<String, DayHours> parseSchedule(String json) {
		Map<String, DayHours> values;
		try {
			values = objectMapper.readValue(json, SCHEDULE_TYPE);
		} catch (JacksonException | IllegalArgumentException exception) {
			throw invalidSchedule(
					"Invalid opening hours",
					"Opening hours must be a weekly schedule with 24-hour open and close times.");
		}
		if (values == null) {
			throw invalidSchedule("Invalid opening hours", "Opening hours must be a JSON object.");
		}
		Map<String, DayHours> normalized = new HashMap<>();
		values.forEach((day, hours) -> {
			String key = day.toUpperCase(Locale.ROOT);
			try {
				java.time.DayOfWeek.valueOf(key);
			} catch (IllegalArgumentException exception) {
				throw invalidSchedule("Invalid opening hours", "Opening hours contain an invalid weekday.");
			}
			if (hours != null) {
				LocalTime open = parseTime(hours.opensAt());
				LocalTime close = parseTime(hours.closesAt());
				if (open.equals(close)
						|| close.isBefore(open) && !close.equals(LocalTime.MIDNIGHT)) {
					throw invalidSchedule(
							"Invalid opening hours",
							"Closing time must be after opening time, or midnight at the end of the day.");
				}
				normalized.put(key, new DayHours(open.toString(), close.toString()));
			}
		});
		return normalized;
	}

	private static LocalTime parseTime(String value) {
		try {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("Time is required.");
			}
			return LocalTime.parse(value);
		} catch (RuntimeException exception) {
			throw invalidSchedule(
					"Invalid opening hours", "Opening hours must use valid 24-hour times such as 09:00.");
		}
	}

	private static LocalDateTime closingDateTime(LocalDate date, LocalTime opensAt, LocalTime closesAt) {
		LocalDate closingDate = closesAt.equals(LocalTime.MIDNIGHT) && opensAt.isAfter(closesAt)
				? date.plusDays(1)
				: date;
		return LocalDateTime.of(closingDate, closesAt);
	}

	private static BookingApiException invalidSchedule(String message) {
		return invalidSchedule("Outside opening hours", message);
	}

	private static BookingApiException invalidSchedule(String title, String message) {
		return new BookingApiException(HttpStatus.BAD_REQUEST, title, message);
	}

	public record DayHours(String opensAt, String closesAt) {
	}

	public record AppointmentSlot(Instant startsAt, String localTime) {
	}

	public record Availability(boolean open, List<AppointmentSlot> slots) {
	}

	private record AppointmentWindow(Instant startsAt, Instant endsAt) {
	}
}
