package com.barbershop.backend.service;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import com.barbershop.backend.booking.Booking;
import com.barbershop.backend.booking.BookingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ServiceApiIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@Transactional
	@Rollback
	void publicCatalogListsOnlyActiveServices() throws Exception {
		ServiceEntity active = serviceRepository.saveAndFlush(new ServiceEntity(
				"Active cut", "Available service", new BigDecimal("24.50"), 30));
		ServiceEntity inactive = new ServiceEntity(
				"Inactive cut", "Unavailable service", new BigDecimal("18.00"), 20);
		inactive.setActive(false);
		serviceRepository.saveAndFlush(inactive);

		mockMvc.perform(get("/api/services"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", hasItem("Active cut")))
				.andExpect(jsonPath("$[*].name", not(hasItem("Inactive cut"))));

		mockMvc.perform(get("/api/services/{id}", active.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Active cut"))
				.andExpect(jsonPath("$.active").value(true));

		mockMvc.perform(get("/api/services/{id}", inactive.getId()))
				.andExpect(status().isNotFound());
	}

	@Test
	@Transactional
	@Rollback
	@WithMockUser
	void createsServiceAndReturnsItsLocation() throws Exception {
		long servicesBefore = serviceRepository.count();

		mockMvc.perform(post("/api/services")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "  Classic cut  ",
								  "description": "A precise haircut",
								  "price": 25.50,
								  "durationMinutes": 45
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.name").value("Classic cut"))
				.andExpect(jsonPath("$.price").value(25.50))
				.andExpect(jsonPath("$.durationMinutes").value(45))
				.andExpect(jsonPath("$.active").value(true));

		assertThat(serviceRepository.count()).isEqualTo(servicesBefore + 1);
	}

	@Test
	@WithMockUser
	void rejectsInvalidServiceCreationWithConsistentBadRequest() throws Exception {
		mockMvc.perform(post("/api/services")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": " ",
								  "description": "",
								  "price": 100000000,
								  "durationMinutes": 0
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Request validation failed"))
				.andExpect(jsonPath("$.errors.name").exists())
				.andExpect(jsonPath("$.errors.description").exists())
				.andExpect(jsonPath("$.errors.price").exists())
				.andExpect(jsonPath("$.errors.durationMinutes").exists());
	}

	@Test
	@WithMockUser
	void rejectsPricesWithMoreThanTwoDecimalPlaces() throws Exception {
		mockMvc.perform(post("/api/services")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "Classic cut",
								  "description": "A precise haircut",
								  "price": 25.501,
								  "durationMinutes": 45
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.price").exists());
	}

	@Test
	@Transactional
	@Rollback
	@WithMockUser
	void updatesExistingServiceAndReturnsNotFoundForMissingService() throws Exception {
		ServiceEntity existing = serviceRepository.saveAndFlush(new ServiceEntity(
				"Classic cut", "Original description", new BigDecimal("25.50"), 45));
		String updateRequest = """
				{
				  "name": "  Updated cut ",
				  "description": "Updated description",
				  "price": 30.00,
				  "durationMinutes": 60
				}
				""";

		mockMvc.perform(put("/api/services/{id}", existing.getId())
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateRequest))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Updated cut"))
				.andExpect(jsonPath("$.description").value("Updated description"))
				.andExpect(jsonPath("$.price").value(30.00))
				.andExpect(jsonPath("$.durationMinutes").value(60));

		mockMvc.perform(put("/api/services/{id}", UUID.randomUUID())
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateRequest))
				.andExpect(status().isNotFound());
	}

	@Test
	@Transactional
	@Rollback
	@WithMockUser
	void changesServiceActivation() throws Exception {
		ServiceEntity service = serviceRepository.saveAndFlush(new ServiceEntity(
				"Classic cut", "A service", new BigDecimal("25.50"), 45));

		mockMvc.perform(patch("/api/services/{id}/active", service.getId())
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"active": false}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
	}

	@Test
	@Transactional
	@Rollback
	@WithMockUser
	void deactivatingServiceDoesNotChangeBookingSnapshots() throws Exception {
		ServiceEntity service = serviceRepository.saveAndFlush(new ServiceEntity(
				"Classic cut", "A service", new BigDecimal("25.50"), 45));
		Booking booking = bookingRepository.saveAndFlush(
				new Booking(null, service, java.time.Instant.now().plusSeconds(3600), null));

		mockMvc.perform(patch("/api/services/{id}/active", service.getId())
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"active": false}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		entityManager.flush();
		entityManager.clear();
		Booking persistedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
		assertThat(persistedBooking.getServiceNameSnapshot()).isEqualTo("Classic cut");
		assertThat(persistedBooking.getServicePriceSnapshot()).isEqualByComparingTo("25.50");
		assertThat(persistedBooking.getServiceDurationSnapshotMinutes()).isEqualTo(45);
		assertThat(serviceRepository.findById(service.getId()).orElseThrow().isActive()).isFalse();
	}

	@Test
	void publicReadsAreAllowedAndUnauthenticatedMutationsAreRejected() throws Exception {
		mockMvc.perform(get("/api/services"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/services/{id}", UUID.randomUUID()))
				.andExpect(status().isNotFound());

		mockMvc.perform(post("/api/services")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(validServiceRequest()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/services/{id}", UUID.randomUUID())
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(validServiceRequest()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/api/services/{id}/active", UUID.randomUUID())
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"active": true}
								"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@WithMockUser
	void csrfEndpointProvidesTokenForUnsafeRequests() throws Exception {
		MvcResult tokenResult = mockMvc.perform(get("/api/csrf"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andReturn();

		MockHttpSession session = (MockHttpSession) tokenResult.getRequest().getSession(false);
		assertThat(session).isNotNull();

		String token = JsonPath.read(tokenResult.getResponse().getContentAsString(), "$.token");
		MvcResult repeatedTokenResult = mockMvc.perform(get("/api/csrf").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andReturn();
		assertThat(repeatedTokenResult.getRequest().getSession(false).getId()).isEqualTo(session.getId());

		mockMvc.perform(post("/api/services")
						.session(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validServiceRequest()))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/services")
						.session(session)
						.header("X-CSRF-TOKEN", token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validServiceRequest()))
				.andExpect(status().isCreated());
	}

	@Test
	void malformedUuidReturnsControlledBadRequest() throws Exception {
		mockMvc.perform(get("/api/services/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request parameter"))
				.andExpect(jsonPath("$.detail").value("A path or query parameter has an invalid format."));
	}

	@Test
	@WithMockUser
	void malformedJsonReturnsControlledBadRequestWithoutInternalDetails() throws Exception {
		String response = mockMvc.perform(post("/api/services")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request body"))
				.andExpect(jsonPath("$.detail").value("The request body is missing or malformed."))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(response).doesNotContain("Exception", "stackTrace", "password", "SQLException");
	}

	private static String validServiceRequest() {
		return """
				{"name":"Classic cut","description":"A precise haircut","price":25.50,"durationMinutes":45}
				""";
	}
}
