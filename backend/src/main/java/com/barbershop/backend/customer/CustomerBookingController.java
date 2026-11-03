package com.barbershop.backend.customer;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.customer.dto.CustomerBookingResponse;
import com.barbershop.backend.booking.dto.CustomerRescheduleRequest;

@RestController
@RequestMapping("/api/customer/bookings")
public class CustomerBookingController {

	private final CustomerBookingService customerBookingService;

	public CustomerBookingController(CustomerBookingService customerBookingService) {
		this.customerBookingService = customerBookingService;
	}

	@GetMapping
	public List<CustomerBookingResponse> getCustomerBookings(Authentication authentication) {
		return customerBookingService.findBookings(authentication.getName());
	}

	@PatchMapping("/{id}/cancel")
	public Object cancelBooking(@PathVariable java.util.UUID id, Authentication authentication) {
		return customerBookingService.cancelBooking(id, authentication.getName());
	}

	@PatchMapping("/{id}/reschedule-request")
	public Object requestReschedule(
			@PathVariable java.util.UUID id,
			@Valid @org.springframework.web.bind.annotation.RequestBody CustomerRescheduleRequest request,
			Authentication authentication) {
		return customerBookingService.requestReschedule(id, authentication.getName(), request);
	}
}
