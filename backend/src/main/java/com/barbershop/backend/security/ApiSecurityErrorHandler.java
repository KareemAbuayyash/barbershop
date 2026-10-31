package com.barbershop.backend.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

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
		write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Access is denied.");
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
