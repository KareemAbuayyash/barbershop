package com.barbershop.backend.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.barbershop.backend.admin.NotificationService;

import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthApiIntegrationTests {

	private static final String PASSWORD = "test-owner-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@MockitoBean
	private ManagementUserDetailsService managementUserDetailsService;

	@MockitoBean
	private NotificationService notificationService;

	@BeforeEach
	void configureTestOwner() {
		when(managementUserDetailsService.loadUserByUsername("owner"))
				.thenReturn(User.withUsername("owner")
						.password(passwordEncoder.encode(PASSWORD))
						.authorities("ROLE_OWNER")
						.build());
	}

	@Test
	void csrfProtectedSessionLoginPersistsOwnerAndRotatesSessionId() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/csrf"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
				.andReturn();
		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
		assertThat(session).isNotNull();
		String originalSessionId = session.getId();
		String preLoginToken = JsonPathSupport.token(csrfResult);

		MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
						.session(session)
						.with(request -> {
							request.setRequestedSessionIdValid(true);
							return request;
						})
						.header("X-CSRF-TOKEN", preLoginToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\" OWNER \",\"password\":\"" + PASSWORD + "\",\"rememberMe\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("owner"))
				.andExpect(jsonPath("$.authorities[0]").value("ROLE_OWNER"))
				.andReturn();
		MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
		assertThat(authenticatedSession).isNotNull();
		assertThat(authenticatedSession.getId()).isNotEqualTo(originalSessionId);

		mockMvc.perform(get("/api/auth/me").session(authenticatedSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("owner"))
				.andExpect(jsonPath("$.authorities[0]").value("ROLE_OWNER"));
		mockMvc.perform(get("/api/admin/summary").session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/admin/customers").session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/admin/reports").session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/admin/payments?fromDate=2026-01-01&toDate=2026-12-31")
						.session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/admin/debts").session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/admin/expenses?fromDate=2026-01-01&toDate=2026-12-31")
						.session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/admin/inventory/products").session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/admin/notifications").session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/admin/settings").session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/services/manage").session(authenticatedSession))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/bookings").session(authenticatedSession))
				.andExpect(status().isOk());

		MvcResult postLoginCsrf = mockMvc.perform(get("/api/csrf").session(authenticatedSession))
				.andExpect(status().isOk())
				.andReturn();
		String subscriptionToken = JsonPathSupport.token(postLoginCsrf);
		mockMvc.perform(post("/api/notifications/subscriptions")
						.session(authenticatedSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/test\","
								+ "\"keys\":{\"p256dh\":\"test-public-key\",\"auth\":\"test-auth-key\"}}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.detail").value("CSRF token is missing or invalid."));
		mockMvc.perform(post("/api/notifications/subscriptions")
						.session(authenticatedSession)
						.header("X-CSRF-TOKEN", subscriptionToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/test\","
								+ "\"keys\":{\"p256dh\":\"test-public-key\",\"auth\":\"test-auth-key\"}}"))
				.andExpect(status().isNoContent());
		verify(notificationService).saveSubscription(
				"owner", true, "https://fcm.googleapis.com/fcm/send/test", "test-public-key", "test-auth-key");

		mockMvc.perform(post("/api/auth/logout")
						.session(authenticatedSession)
						.header("X-CSRF-TOKEN", preLoginToken))
				.andExpect(status().isForbidden());

		String logoutToken = JsonPathSupport.token(postLoginCsrf);
		mockMvc.perform(post("/api/auth/logout")
						.session(authenticatedSession)
						.header("X-CSRF-TOKEN", logoutToken))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/auth/me").session(authenticatedSession))
				.andExpect(status().isUnauthorized());
		MvcResult csrfAfterLogout = mockMvc.perform(get("/api/csrf"))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(JsonPathSupport.token(csrfAfterLogout))
				.isNotBlank()
				.isNotEqualTo(logoutToken);
	}

	@Test
	void invalidCredentialsAreGenericAndHttpBasicIsNotAccepted() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"owner\",\"password\":\"wrong-password\",\"rememberMe\":false}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("Username or password is invalid."));

		String basic = Base64.getEncoder().encodeToString(
				("owner:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
		mockMvc.perform(get("/api/bookings").header("Authorization", "Basic " + basic))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void loginRequiresCsrfAndRateLimitsFailedCredentials() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"owner\",\"password\":\"" + PASSWORD + "\",\"rememberMe\":false}"))
				.andExpect(status().isForbidden());

		String remoteAddress = "198.51.100.41";
		for (int attempt = 0; attempt < 5; attempt++) {
			mockMvc.perform(post("/api/auth/login")
							.with(csrf())
							.with(request -> {
								request.setRemoteAddr(remoteAddress);
								return request;
							})
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"owner\",\"password\":\"wrong-password\",\"rememberMe\":false}"))
					.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/api/auth/login")
						.with(csrf())
						.with(request -> {
							request.setRemoteAddr(remoteAddress);
							return request;
						})
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"owner\",\"password\":\"wrong-password\",\"rememberMe\":false}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	void anonymousAndNonOwnerManagementRequestsAreRejectedWhilePublicReadsRemainOpen() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/bookings"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/bookings").with(user("customer")))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/services")
						.with(user("customer"))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/services"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/booking-settings"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/csrf"))
				.andExpect(status().isOk());
	}

	private static final class JsonPathSupport {
		private static String token(MvcResult result) throws Exception {
			return com.jayway.jsonpath.JsonPath.read(
					result.getResponse().getContentAsString(), "$.token");
		}
	}
}
