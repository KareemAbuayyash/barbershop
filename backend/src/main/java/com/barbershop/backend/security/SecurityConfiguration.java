package com.barbershop.backend.security;

import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

import com.barbershop.backend.account.OwnerBootstrapProperties;
import com.barbershop.backend.admin.NotificationService;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.CookieSerializer.CookieValue;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@EnableConfigurationProperties({OwnerBootstrapProperties.class, RateLimitProperties.class})
public class SecurityConfiguration {

	static final String REMEMBER_ME_COOKIE_REQUEST_ATTRIBUTE = "barbershop.remember-me-cookie";
	private static final int REMEMBERED_COOKIE_AGE_SECONDS = 30 * 24 * 60 * 60;
	private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfiguration.class);

	@Bean
	CookieSerializer springSessionCookieSerializer(Environment environment) {
		boolean secureCookie = environment.getProperty(
				"server.servlet.session.cookie.secure", Boolean.class, false);
		DefaultCookieSerializer serializer = new DefaultCookieSerializer() {
			@Override
			public void writeCookieValue(CookieValue cookieValue) {
				if (Boolean.TRUE.equals(cookieValue.getRequest()
						.getAttribute(REMEMBER_ME_COOKIE_REQUEST_ATTRIBUTE))) {
					cookieValue.setCookieMaxAge(REMEMBERED_COOKIE_AGE_SECONDS);
				}
				super.writeCookieValue(cookieValue);
			}
		};
		serializer.setCookiePath("/");
		serializer.setSameSite("Lax");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(secureCookie);
		return serializer;
	}

	@Bean
	HttpSessionCsrfTokenRepository csrfTokenRepository() {
		HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
		repository.setHeaderName("X-CSRF-TOKEN");
		return repository;
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	SessionAuthenticationStrategy loginSessionAuthenticationStrategy(
			HttpSessionCsrfTokenRepository csrfTokenRepository) {
		return new CompositeSessionAuthenticationStrategy(List.of(
				new ChangeSessionIdAuthenticationStrategy(),
				new CsrfAuthenticationStrategy(csrfTokenRepository)));
	}

	@Bean
	AuthenticationManager authenticationManager(
			UserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

	@Bean
	SecurityFilterChain applicationSecurity(
			HttpSecurity http,
			HttpSessionCsrfTokenRepository csrfTokenRepository,
			SecurityContextRepository securityContextRepository,
			ApiSecurityErrorHandler securityErrorHandler,
			NotificationService notificationService,
			Environment environment) throws Exception {
		http
				.authorizeHttpRequests(authorize -> authorize
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.GET, "/api/services/manage").hasRole("OWNER")
						.requestMatchers(HttpMethod.GET, "/api/services/*/image").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/services", "/api/services/*").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/booking-settings").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/shop-profile").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/bookings/availability").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/health").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/bookings").permitAll()
						.requestMatchers(HttpMethod.POST,
								"/api/auth/login",
								"/api/auth/register",
								"/api/auth/logout").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
						.requestMatchers(HttpMethod.GET, "/api/customer/bookings", "/api/customer/profile").hasRole("CUSTOMER")
						.requestMatchers("/api/customer/bookings/**").hasRole("CUSTOMER")
						.requestMatchers("/api/guest-notifications/**").permitAll()
						.requestMatchers("/api/notifications/**").hasAnyRole("OWNER", "CUSTOMER")
						.requestMatchers("/api/admin/**").hasRole("OWNER")
						.requestMatchers(HttpMethod.GET, "/api/bookings/availability/*").hasRole("OWNER")
						.requestMatchers(HttpMethod.GET, "/api/bookings", "/api/bookings/*").hasRole("OWNER")
						.requestMatchers(HttpMethod.PATCH,
								"/api/bookings/*/confirm",
								"/api/bookings/*/cancel",
								"/api/bookings/queue/reorder").hasRole("OWNER")
						.requestMatchers(HttpMethod.PUT, "/api/bookings/*/schedule").hasRole("OWNER")
						.requestMatchers(HttpMethod.PATCH, "/api/services/archive").hasRole("OWNER")
						.requestMatchers(HttpMethod.POST, "/api/services").hasRole("OWNER")
						.requestMatchers(HttpMethod.PUT, "/api/services/*/image").hasRole("OWNER")
						.requestMatchers(HttpMethod.PUT, "/api/services/*").hasRole("OWNER")
						.requestMatchers(HttpMethod.PATCH, "/api/services/*/active").hasRole("OWNER")
						.requestMatchers(HttpMethod.PUT, "/api/booking-settings").hasRole("OWNER")
						.anyRequest().denyAll())
				.securityContext(context -> context
						.securityContextRepository(securityContextRepository)
						.requireExplicitSave(true))
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
						.sessionFixation(fixation -> fixation.changeSessionId()))
				.csrf(csrf -> csrf
						.csrfTokenRepository(csrfTokenRepository))
				.headers(headers -> {
					headers.addHeaderWriter(new StaticHeadersWriter(
							"Referrer-Policy", "strict-origin-when-cross-origin"));
					headers.addHeaderWriter(new StaticHeadersWriter(
							"Permissions-Policy", "camera=(self), microphone=(), geolocation=()"));
					if (environment.acceptsProfiles(Profiles.of("prod"))) {
						headers.addHeaderWriter(new StaticHeadersWriter("Content-Security-Policy",
								"default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; "
									+ "form-action 'self'; script-src 'self'; connect-src 'self'; "
									+ "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
									+ "font-src 'self' data: https://fonts.gstatic.com; "
									+ "img-src 'self' data: blob: https://images.unsplash.com; worker-src 'self' blob:;"));
					}
				})
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(securityErrorHandler)
						.accessDeniedHandler(securityErrorHandler))
				.logout(logout -> logout
						.logoutUrl("/api/auth/logout")
						.invalidateHttpSession(true)
						.clearAuthentication(true)
						.logoutSuccessHandler((request, response, authentication) -> {
							String pushEndpoint = request.getHeader("X-Push-Endpoint");
							// This also handles devices whose session expired or whose old logout
							// happened before account-push cleanup was installed.
							if (pushEndpoint != null && !pushEndpoint.isBlank()) {
								try {
									notificationService.removeDeviceSubscription(pushEndpoint);
								} catch (RuntimeException exception) {
									LOGGER.warn("Unable to detach this browser's account push subscriptions during logout.", exception);
								}
							}
							response.setStatus(HttpServletResponse.SC_NO_CONTENT);
						}))
				.formLogin(form -> form.disable());

		return http.build();
	}
}
