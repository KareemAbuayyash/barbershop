package com.barbershop.backend.booking;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BookingSettingsApiIntegrationTests {

	private static final UUID SEED_SETTINGS_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final String SETTINGS_JSON = """
			{
			  "exactAppointmentEnabled": true,
			  "confirmationRequestEnabled": true,
			  "dayQueueEnabled": false,
			  "sameDayQueueEnabled": true,
			  "newBookingsDisabled": false
			}
			""";

	@BeforeEach
	void resetBookingSettingsSeed() {
		jdbcTemplate.update("""
				INSERT INTO booking_settings (
				    id,
				    exact_appointment_enabled,
				    confirmation_request_enabled,
				    day_queue_enabled,
				    same_day_queue_enabled,
				    new_bookings_disabled,
				    singleton_key
				) VALUES (?, false, false, false, false, true, true)
				ON CONFLICT (id) DO UPDATE SET
				    exact_appointment_enabled = EXCLUDED.exact_appointment_enabled,
				    confirmation_request_enabled = EXCLUDED.confirmation_request_enabled,
				    day_queue_enabled = EXCLUDED.day_queue_enabled,
				    same_day_queue_enabled = EXCLUDED.same_day_queue_enabled,
				    new_bookings_disabled = EXCLUDED.new_bookings_disabled,
				    updated_at = CURRENT_TIMESTAMP
				""",
			SEED_SETTINGS_ID);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BookingSettingsRepository bookingSettingsRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void publicGetReturnsTheExistingV1SeedSettings() throws Exception {
		mockMvc.perform(get("/api/booking-settings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.exactAppointmentEnabled").value(false))
				.andExpect(jsonPath("$.confirmationRequestEnabled").value(false))
				.andExpect(jsonPath("$.dayQueueEnabled").value(false))
				.andExpect(jsonPath("$.sameDayQueueEnabled").value(false))
				.andExpect(jsonPath("$.newBookingsDisabled").value(true))
				.andExpect(jsonPath("$.updatedAt").isNotEmpty());

		assertThat(bookingSettingsRepository.count()).isEqualTo(1);
		assertThat(bookingSettingsRepository.findBySingletonKeyTrue()).isPresent();
	}

	@Test
	@Transactional
	@Rollback
	@WithMockUser
	void authenticatedUpdateWithValidCsrfUpdatesTheExistingSettingsRow() throws Exception {
		UUID seededId = bookingSettingsRepository.findBySingletonKeyTrue().orElseThrow().getId();

		mockMvc.perform(put("/api/booking-settings")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(SETTINGS_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.exactAppointmentEnabled").value(true))
				.andExpect(jsonPath("$.confirmationRequestEnabled").value(true))
				.andExpect(jsonPath("$.dayQueueEnabled").value(false))
				.andExpect(jsonPath("$.sameDayQueueEnabled").value(true))
				.andExpect(jsonPath("$.newBookingsDisabled").value(false));

		assertThat(bookingSettingsRepository.count()).isEqualTo(1);
		assertThat(bookingSettingsRepository.findBySingletonKeyTrue().orElseThrow().getId())
				.isEqualTo(seededId);
	}

	@Test
	void unauthenticatedUpdateIsRejected() throws Exception {
		mockMvc.perform(put("/api/booking-settings")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(SETTINGS_JSON))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@WithMockUser
	void updateRejectsMissingAndInvalidCsrfTokens() throws Exception {
		mockMvc.perform(put("/api/booking-settings")
						.contentType(MediaType.APPLICATION_JSON)
						.content(SETTINGS_JSON))
				.andExpect(status().isForbidden());

		mockMvc.perform(put("/api/booking-settings")
						.header("X-CSRF-TOKEN", "invalid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(SETTINGS_JSON))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser
	void invalidSettingsRequestReturnsProblemDetails() throws Exception {
		mockMvc.perform(put("/api/booking-settings")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "exactAppointmentEnabled": true,
								  "confirmationRequestEnabled": null,
								  "dayQueueEnabled": false,
								  "sameDayQueueEnabled": true
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Request validation failed"))
				.andExpect(jsonPath("$.errors.confirmationRequestEnabled").exists())
				.andExpect(jsonPath("$.errors.newBookingsDisabled").exists());
	}

	@Test
	void migrationPreventsASecondSingletonSettingsRow() {
		assertThat(bookingSettingsRepository.count()).isEqualTo(1);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO booking_settings (id) VALUES (?)",
				UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(bookingSettingsRepository.count()).isEqualTo(1);
	}

	@Test
	@Transactional
	@Rollback
	@WithMockUser
	void missingSettingsReturnServiceUnavailableWithoutCreatingARow() throws Exception {
		bookingSettingsRepository.deleteAll();
		bookingSettingsRepository.flush();

		mockMvc.perform(get("/api/booking-settings"))
				.andExpect(status().isServiceUnavailable());
		mockMvc.perform(put("/api/booking-settings")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(SETTINGS_JSON))
				.andExpect(status().isServiceUnavailable());

		assertThat(bookingSettingsRepository.count()).isZero();
	}
}
