package com.barbershop.backend.booking;

import org.springframework.http.HttpStatus;

public class BookingApiException extends RuntimeException {

	private final HttpStatus status;
	private final String title;

	public BookingApiException(HttpStatus status, String title, String message) {
		super(message);
		this.status = status;
		this.title = title;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getTitle() {
		return title;
	}
}
