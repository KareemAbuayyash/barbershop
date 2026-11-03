package com.barbershop.backend.admin;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import com.barbershop.backend.service.ServiceEntity;
import com.barbershop.backend.service.ServiceRepository;
import com.barbershop.backend.booking.Booking;
import com.barbershop.backend.booking.BookingMode;
import com.barbershop.backend.customer.CustomerAccount;
import com.barbershop.backend.customer.CustomerAccountRepository;
import com.barbershop.backend.customer.CustomerEntity;
import com.barbershop.backend.customer.CustomerRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminManagementApiIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private CustomerRepository customerRepository;

	@Autowired
	private CustomerAccountRepository customerAccountRepository;

	@Autowired
	private com.barbershop.backend.booking.BookingRepository bookingRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	@Transactional
	@Rollback
	@WithMockUser(roles = "OWNER")
	void ownerCanEditCustomerProfileAndResetOnlyItsLinkedAccountPassword() throws Exception {
		ServiceEntity service = serviceRepository.saveAndFlush(
				new ServiceEntity("Test service", "Test service", java.math.BigDecimal.TEN, 30));
		CustomerEntity customer = customerRepository.saveAndFlush(
				new CustomerEntity("Original Customer", "+970590000001"));
		String oldPassword = "old-customer-password";
		CustomerAccount account = customerAccountRepository.saveAndFlush(new CustomerAccount(
				"customer-test-" + UUID.randomUUID().toString().substring(0, 8),
				passwordEncoder.encode(oldPassword),
				"Original Customer",
				"+970590000001"));
		bookingRepository.saveAndFlush(new Booking(
				customer, account, service, BookingMode.CONFIRMATION_REQUEST, null, null, null, null));

		mockMvc.perform(put("/api/admin/customers/{id}", customer.getId())
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Updated Customer","phoneNumber":"+970590000002"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.customer.name").value("Updated Customer"))
				.andExpect(jsonPath("$.customer.phoneNumber").value("+970590000002"))
				.andExpect(jsonPath("$.accounts[0].displayName").value("Updated Customer"));

		mockMvc.perform(put(
						"/api/admin/customers/{id}/accounts/{accountId}/password",
						customer.getId(), account.getId())
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"newPassword":"new-customer-password"}
								"""))
				.andExpect(status().isNoContent());
		String storedHash = jdbcTemplate.queryForObject(
				"SELECT password_hash FROM customer_accounts WHERE id = ?",
				String.class, account.getId());
		org.assertj.core.api.Assertions.assertThat(storedHash).isNotEqualTo("new-customer-password");
		org.assertj.core.api.Assertions.assertThat(passwordEncoder.matches("new-customer-password", storedHash))
				.isTrue();
		org.assertj.core.api.Assertions.assertThat(passwordEncoder.matches(oldPassword, storedHash)).isFalse();
	}

	@Test
	void anonymousCannotReadManagementApisWhilePublicServicesRemainAvailable() throws Exception {
		mockMvc.perform(get("/api/admin/summary"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/services"))
				.andExpect(status().isOk());
	}

	@Test
	@WithMockUser(roles = "CUSTOMER")
	void authenticatedCustomerCannotReadManagementApis() throws Exception {
		mockMvc.perform(get("/api/admin/summary"))
				.andExpect(status().isForbidden());
	}

	@Test
	@Transactional
	@Rollback
	@WithMockUser(roles = "OWNER")
	void ownerCanTrackInventoryAndNegativeStockIsRejected() throws Exception {
		String product = """
				{
				  "name": "Test pomade",
				  "unit": "piece",
				  "initialQuantity": 5,
				  "lowStockThreshold": 2
				}
				""";
		String productId = mockMvc.perform(post("/api/admin/inventory/products")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(product))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.quantity").value(5))
				.andReturn()
				.getResponse()
				.getContentAsString();
		productId = objectMapper.readTree(productId).get("id").asText();

		mockMvc.perform(post("/api/admin/inventory/products/{id}/movements", productId)
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"movementType":"PURCHASE","quantity":3,"unitCost":4.5,"notes":"stock"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.quantity").value(8));

		mockMvc.perform(post("/api/admin/inventory/products/{id}/movements", productId)
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"movementType":"CONSUMPTION","quantity":9,"unitCost":null,"notes":null}
								"""))
				.andExpect(status().isConflict());
	}

	@Test
	@Transactional
	@Rollback
	@WithMockUser(roles = "OWNER")
	void ownerUpdatesShopAndBookingSettingsInOneRequest() throws Exception {
		mockMvc.perform(put("/api/admin/settings")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "phoneNumber":"+970599000000",
								  "address":"Shop address",
								  "openingHours":"{}",
								  "exactAppointmentEnabled":true,
								  "confirmationRequestEnabled":false,
								  "dayQueueEnabled":true,
								  "sameDayQueueEnabled":false,
								  "newBookingsDisabled":false
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.phoneNumber").value("+970599000000"));

		mockMvc.perform(get("/api/booking-settings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.exactAppointmentEnabled").value(true))
				.andExpect(jsonPath("$.dayQueueEnabled").value(true))
				.andExpect(jsonPath("$.newBookingsDisabled").value(false));
	}

}
