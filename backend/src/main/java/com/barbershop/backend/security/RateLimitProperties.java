package com.barbershop.backend.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "barbershop.security.rate-limit")
public class RateLimitProperties {

	private int maxTrackedAddresses = 10_000;
	private int loginMaxFailures = 5;
	private Duration loginWindow = Duration.ofMinutes(15);
	private int bookingMaxAttempts = 10;
	private Duration bookingWindow = Duration.ofMinutes(10);

	public int getMaxTrackedAddresses() {
		return maxTrackedAddresses;
	}

	public void setMaxTrackedAddresses(int maxTrackedAddresses) {
		this.maxTrackedAddresses = maxTrackedAddresses;
	}

	public int getLoginMaxFailures() {
		return loginMaxFailures;
	}

	public void setLoginMaxFailures(int loginMaxFailures) {
		this.loginMaxFailures = loginMaxFailures;
	}

	public Duration getLoginWindow() {
		return loginWindow;
	}

	public void setLoginWindow(Duration loginWindow) {
		this.loginWindow = loginWindow;
	}

	public int getBookingMaxAttempts() {
		return bookingMaxAttempts;
	}

	public void setBookingMaxAttempts(int bookingMaxAttempts) {
		this.bookingMaxAttempts = bookingMaxAttempts;
	}

	public Duration getBookingWindow() {
		return bookingWindow;
	}

	public void setBookingWindow(Duration bookingWindow) {
		this.bookingWindow = bookingWindow;
	}
}
