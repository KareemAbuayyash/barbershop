package com.barbershop.backend.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.account.UsernameNormalizer;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository securityContextRepository;
	private final SessionAuthenticationStrategy loginSessionAuthenticationStrategy;
	private final RateLimitService rateLimitService;

	public AuthController(
			AuthenticationManager authenticationManager,
			SecurityContextRepository securityContextRepository,
			SessionAuthenticationStrategy loginSessionAuthenticationStrategy,
			RateLimitService rateLimitService) {
		this.authenticationManager = authenticationManager;
		this.securityContextRepository = securityContextRepository;
		this.loginSessionAuthenticationStrategy = loginSessionAuthenticationStrategy;
		this.rateLimitService = rateLimitService;
	}

	@PostMapping("/login")
	public ResponseEntity<?> login(
			@Valid @RequestBody LoginRequest request,
			HttpServletRequest servletRequest,
			HttpServletResponse servletResponse) {
		String remoteAddress = servletRequest.getRemoteAddr();
		rateLimitService.assertLoginAllowed(remoteAddress);

		if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
			rateLimitService.recordLoginFailure(remoteAddress);
			return invalidCredentials();
		}

		Authentication authentication;
		try {
			authentication = authenticationManager.authenticate(
					UsernamePasswordAuthenticationToken.unauthenticated(
							UsernameNormalizer.normalize(request.username()),
							request.password()));
		} catch (AuthenticationException exception) {
			rateLimitService.recordLoginFailure(remoteAddress);
			return invalidCredentials();
		}

		loginSessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, servletRequest, servletResponse);
		rateLimitService.clearLoginFailures(remoteAddress);
		return ResponseEntity.ok(currentUserResponse(authentication));
	}

	@GetMapping("/me")
	public CurrentUserResponse currentUser(Authentication authentication) {
		return currentUserResponse(authentication);
	}

	private static CurrentUserResponse currentUserResponse(Authentication authentication) {
		List<String> authorities = authentication.getAuthorities().stream()
				.map(authority -> authority.getAuthority())
				.filter(authority -> !authority.startsWith("FACTOR_"))
				.sorted()
				.toList();
		return new CurrentUserResponse(authentication.getName(), authorities);
	}

	private static ResponseEntity<ProblemDetail> invalidCredentials() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNAUTHORIZED,
				"Username or password is invalid.");
		problem.setTitle("Authentication failed");
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
	}
}
