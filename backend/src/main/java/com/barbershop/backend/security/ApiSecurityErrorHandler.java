package com.barbershop.backend.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

@Component
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(ApiSecurityErrorHandler.class);

	@Override
	public void commence(
			HttpServletRequest request,
			HttpServletResponse response,
			org.springframework.security.core.AuthenticationException exception) throws IOException {
		write(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Authentication is required.");
	}

	@Override
	public void handle(
			HttpServletRequest request,
			HttpServletResponse response,
			AccessDeniedException exception) throws IOException {
		String detail = exception instanceof CsrfException
				? "CSRF token is missing or invalid."
				: "Access is denied.";
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		LOGGER.warn("Rejected {} {} with HTTP 403 ({}, authenticated={}, authorities={}).",
				request.getMethod(), request.getRequestURI(), exception.getClass().getSimpleName(),
				authentication != null && authentication.isAuthenticated(),
				authentication == null ? "none" : authentication.getAuthorities());
		write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", detail);
	}

	private static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(
				"{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
						+ ",\"detail\":\"" + detail + "\"}");
	}
}
