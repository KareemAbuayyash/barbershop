package com.barbershop.backend.customer;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.barbershop.backend.account.AccountWriteLock;
import com.barbershop.backend.account.ManagementAccountRepository;
import com.barbershop.backend.account.UsernameNormalizer;
import com.barbershop.backend.customer.dto.CustomerRegistrationRequest;
import com.barbershop.backend.customer.dto.CustomerRegistrationResponse;

@Service
public class CustomerAccountRegistrationService {

	private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9._-]{3,64}");

	private final CustomerAccountRepository customerAccountRepository;
	private final ManagementAccountRepository managementAccountRepository;
	private final JdbcTemplate jdbcTemplate;
	private final PasswordEncoder passwordEncoder;

	public CustomerAccountRegistrationService(
			CustomerAccountRepository customerAccountRepository,
			ManagementAccountRepository managementAccountRepository,
			JdbcTemplate jdbcTemplate,
			PasswordEncoder passwordEncoder) {
		this.customerAccountRepository = customerAccountRepository;
		this.managementAccountRepository = managementAccountRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public CustomerRegistrationResponse register(CustomerRegistrationRequest request) {
		AccountWriteLock.acquire(jdbcTemplate);
		String username = UsernameNormalizer.normalize(request.username());
		if (!USERNAME_PATTERN.matcher(username).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username format is invalid.");
		}
		String displayName = request.displayName().trim();
		if (displayName.length() < 2) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Display name must contain at least 2 characters.");
		}
		int passwordBytes = request.password().getBytes(StandardCharsets.UTF_8).length;
		if (passwordBytes < 12 || passwordBytes > 72) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"Password must contain 12-72 UTF-8 bytes.");
		}
		if (managementAccountRepository.findByUsernameNormalized(username).isPresent()
				|| customerAccountRepository.existsByUsernameNormalized(username)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is unavailable.");
		}

		CustomerAccount account = customerAccountRepository.saveAndFlush(new CustomerAccount(
				username,
				passwordEncoder.encode(request.password()),
				displayName,
				request.phoneNumber().trim()));
		return new CustomerRegistrationResponse(
				account.getId(),
				account.getUsernameNormalized(),
				CustomerAccount.CUSTOMER_ROLE);
	}
}
