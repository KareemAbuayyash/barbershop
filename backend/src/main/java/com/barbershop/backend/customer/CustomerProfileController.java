package com.barbershop.backend.customer;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/customer/profile")
public class CustomerProfileController {

	private final CustomerAccountRepository customerAccountRepository;

	public CustomerProfileController(CustomerAccountRepository customerAccountRepository) {
		this.customerAccountRepository = customerAccountRepository;
	}

	@GetMapping
	public CustomerProfile getProfile(Authentication authentication) {
		CustomerAccount account = customerAccountRepository
				.findByUsernameNormalized(authentication.getName())
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "Customer account is unavailable."));
		return new CustomerProfile(account.getDisplayName(), account.getPhoneNumber());
	}

	public record CustomerProfile(String name, String phoneNumber) {
	}
}
