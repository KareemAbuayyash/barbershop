package com.barbershop.backend.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.SQLException;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.hibernate.exception.ConstraintViolationException;

import com.barbershop.backend.booking.BookingApiException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(BookingApiException.class)
	public ResponseEntity<ProblemDetail> handleBookingError(BookingApiException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				exception.getStatus(),
				exception.getMessage());
		problem.setTitle(exception.getTitle());
		return ResponseEntity.status(exception.getStatus()).body(problem);
	}

	@ExceptionHandler({DataIntegrityViolationException.class, TransactionSystemException.class})
	public ResponseEntity<ProblemDetail> handleBookingPersistenceConflict(RuntimeException exception) {
		boolean appointmentConflict = hasAppointmentExclusionViolation(exception);
		HttpStatus status = appointmentConflict ? HttpStatus.CONFLICT : HttpStatus.INTERNAL_SERVER_ERROR;
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				status,
				appointmentConflict
						? "The requested appointment time conflicts with another confirmed booking."
						: "The booking operation could not be completed.");
		problem.setTitle(appointmentConflict ? "Appointment time unavailable" : "Booking operation failed");
		return ResponseEntity.status(status).body(problem);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
		Map<String, String> errors = exception.getBindingResult().getFieldErrors().stream()
				.collect(Collectors.toMap(
						FieldError::getField,
						error -> error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage(),
						(first, second) -> first,
						LinkedHashMap::new));

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				"One or more request fields are invalid.");
		problem.setTitle("Request validation failed");
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ProblemDetail> handleUnreadableRequest() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				"The request body is missing or malformed.");
		problem.setTitle("Invalid request body");
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ProblemDetail> handleInvalidRequestParameter() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				"A path or query parameter has an invalid format.");
		problem.setTitle("Invalid request parameter");
		return ResponseEntity.badRequest().body(problem);
	}

	private static boolean hasAppointmentExclusionViolation(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& "ex_bookings_confirmed_appointment_no_overlap".equals(violation.getConstraintName())) {
				return true;
			}
			if (cause instanceof SQLException sqlException && "23P01".equals(sqlException.getSQLState())) {
				return true;
			}
		}
		return false;
	}
}
