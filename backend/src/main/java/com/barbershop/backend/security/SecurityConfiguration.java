package com.barbershop.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * Service mutations are temporarily authenticated-only until application-specific
 * barber/admin accounts and authorization are introduced. Spring Boot's generated
 * development credentials are not a production authentication system. Clients can
 * obtain a session-backed CSRF token from GET /api/csrf and must retain the session
 * cookie and send the returned token in the X-CSRF-TOKEN header for mutations.
 */
@Configuration
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.GET, "/api/services", "/api/services/*").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/booking-settings").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
						.requestMatchers("/api/services/**").authenticated()
						.requestMatchers("/api/booking-settings/**").authenticated()
						.anyRequest().denyAll())
				.httpBasic(Customizer.withDefaults())
				.formLogin(form -> form.disable())
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
				.csrf(csrf -> csrf
						.csrfTokenRepository(new HttpSessionCsrfTokenRepository()));

		return http.build();
	}
}
