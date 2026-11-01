package com.barbershop.backend.security;

import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import com.barbershop.backend.account.OwnerBootstrapProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration
@EnableConfigurationProperties({OwnerBootstrapProperties.class, RateLimitProperties.class})
public class SecurityConfiguration {

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
			ApiSecurityErrorHandler securityErrorHandler) throws Exception {
		http
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.GET, "/api/services/manage").hasRole("OWNER")
						.requestMatchers(HttpMethod.GET, "/api/services", "/api/services/*").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/booking-settings").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/bookings").permitAll()
						.requestMatchers(HttpMethod.POST,
								"/api/auth/login",
								"/api/auth/register",
								"/api/auth/logout").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
						.requestMatchers(HttpMethod.GET, "/api/customer/bookings").hasRole("CUSTOMER")
						.requestMatchers(HttpMethod.GET, "/api/bookings", "/api/bookings/*").hasRole("OWNER")
						.requestMatchers(HttpMethod.PATCH,
								"/api/bookings/*/confirm",
								"/api/bookings/*/cancel",
								"/api/bookings/*/complete",
								"/api/bookings/queue/reorder").hasRole("OWNER")
						.requestMatchers(HttpMethod.POST, "/api/services").hasRole("OWNER")
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
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(securityErrorHandler)
						.accessDeniedHandler(securityErrorHandler))
				.logout(logout -> logout
						.logoutUrl("/api/auth/logout")
						.invalidateHttpSession(true)
						.clearAuthentication(true)
						.logoutSuccessHandler((request, response, authentication) ->
								response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
				.formLogin(form -> form.disable());

		return http.build();
	}
}
