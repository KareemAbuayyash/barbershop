package com.barbershop.backend.security;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class BackendHealthController {

	private final JdbcTemplate jdbc;

	public BackendHealthController(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping
	public Map<String, String> health() {
		jdbc.queryForObject("SELECT 1", Integer.class);
		return Map.of("status", "UP");
	}
}
