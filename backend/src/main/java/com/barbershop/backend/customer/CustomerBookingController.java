package com.barbershop.backend.customer;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.customer.dto.CustomerBookingResponse;

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
}
