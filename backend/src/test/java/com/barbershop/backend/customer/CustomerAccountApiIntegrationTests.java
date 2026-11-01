package com.barbershop.backend.customer;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.barbershop.backend.booking.BookingSettingsRepository;
import com.barbershop.backend.service.ServiceEntity;
import com.barbershop.backend.service.ServiceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class CustomerAccountApiIntegrationTests {

	private static final String PASSWORD = "Customer!Password2026";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CustomerAccountRepository customerAccountRepository;

	@Autowired
	private BookingSettingsRepository bookingSettingsRepository;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private String username;

	@BeforeEach
	void selectIsolatedUsername() {
		username = "customer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
	}

	@AfterEach
	void removeTestAccount() {
		if (username != null) {
			customerAccountRepository.deleteByUsernameNormalized(username);
		}
	}

	@Test
	void registrationRequiresCsrfAndCreatesOnlyCustomerAuthorityAfterLogin() throws Exception {
		String registrationBody = registrationBody();
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationBody))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/auth/register")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationBody))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value(username))
				.andExpect(jsonPath("$.role").value("ROLE_CUSTOMER"))
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.doesNotContain(PASSWORD, "passwordHash"));

		MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authorities[0]").value("ROLE_CUSTOMER"))
				.andReturn();
		MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
		assertThat(session).isNotNull();

		mockMvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(username))
				.andExpect(jsonPath("$.authorities[0]").value("ROLE_CUSTOMER"));
		mockMvc.perform(get("/api/customer/bookings").session(session))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/bookings").session(session))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/services/manage").session(session))
				.andExpect(status().isForbidden());
	}

	@Test
	void duplicateUsernameIsRejectedWithoutReplacingTheAccount() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationBody()))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/auth/register")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationBody()))
				.andExpect(status().isConflict());
	}

	@Test
	void registrationRejectsWeakPasswords() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "username": "%s",
								  "displayName": "Test Customer",
								  "phoneNumber": "+970 599 123456",
								  "password": "short"
								}
								""".formatted(username)))
				.andExpect(status().isBadRequest());
		assertThat(customerAccountRepository.existsByUsernameNormalized(username)).isFalse();
	}

	@Test
	void registrationAcceptsAnEightCharacterPassword() throws Exception {
		String body = registrationBody().replace(PASSWORD, "eight888");

		mockMvc.perform(post("/api/auth/register")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/auth/login")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"eight888\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authorities[0]").value("ROLE_CUSTOMER"));
	}

	@Test
	void registrationIsRateLimitedByRemoteAddress() throws Exception {
		String remoteAddress = "198.51.100." + (10 + Math.abs(username.hashCode() % 200));
		for (int attempt = 0; attempt < 5; attempt++) {
			mockMvc.perform(post("/api/auth/register")
							.with(csrf())
							.with(request -> {
								request.setRemoteAddr(remoteAddress);
								return request;
							})
							.contentType(MediaType.APPLICATION_JSON)
							.content(registrationBody()))
					.andExpect(status().is(attempt == 0 ? 201 : 409));
		}

		mockMvc.perform(post("/api/auth/register")
						.with(csrf())
						.with(request -> {
							request.setRemoteAddr(remoteAddress);
							return request;
						})
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationBody()))
				.andExpect(status().isTooManyRequests())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
						.exists("Retry-After"));
	}

	@Test
	void registeredCustomerBookingIsLinkedAndVisibleOnlyInTheirAccount() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationBody()))
				.andExpect(status().isCreated());

		MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
		assertThat(session).isNotNull();

		var settings = bookingSettingsRepository.findBySingletonKeyTrue().orElseThrow();
		settings.setSameDayQueueEnabled(true);
		settings.setNewBookingsDisabled(false);
		bookingSettingsRepository.saveAndFlush(settings);
		ServiceEntity service = serviceRepository.saveAndFlush(
				new ServiceEntity("Account booking test", "Test service", new BigDecimal("25.00"), 30));

		MvcResult bookingResult = mockMvc.perform(post("/api/bookings")
						.session(session)
						.with(csrf())
						.header("Idempotency-Key", "customer-account-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "bookingMode": "SAME_DAY_QUEUE",
								  "serviceId": "%s",
								  "customerName": "Test Customer",
								  "customerPhone": "+970 599 123456"
								}
								""".formatted(service.getId())))
				.andExpect(status().isCreated())
				.andReturn();
		UUID bookingId = UUID.fromString(
				com.jayway.jsonpath.JsonPath.read(bookingResult.getResponse().getContentAsString(), "$.bookingId"));

		UUID linkedAccountId = jdbcTemplate.queryForObject(
				"SELECT customer_account_id FROM bookings WHERE id = ?",
				UUID.class,
				bookingId);
		UUID ownAccountId = customerAccountRepository.findByUsernameNormalized(username).orElseThrow().getId();
		assertThat(linkedAccountId).isEqualTo(ownAccountId);

		mockMvc.perform(get("/api/customer/bookings").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(bookingId.toString()))
				.andExpect(jsonPath("$[0].serviceName").value("Account booking test"));
	}

	private String registrationBody() {
		return """
				{
				  "username": "%s",
				  "displayName": "Test Customer",
				  "phoneNumber": "+970 599 123456",
				  "password": "%s"
				}
				""".formatted(username, PASSWORD);
	}
}
