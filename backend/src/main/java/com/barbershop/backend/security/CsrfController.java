package com.barbershop.backend.security;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {

	@GetMapping("/api/csrf")
	public CsrfTokenResponse getCsrfToken(CsrfToken csrfToken) {
		return new CsrfTokenResponse(
				csrfToken.getHeaderName(),
				csrfToken.getParameterName(),
				csrfToken.getToken());
	}

	public record CsrfTokenResponse(String headerName, String parameterName, String token) {
	}
}
