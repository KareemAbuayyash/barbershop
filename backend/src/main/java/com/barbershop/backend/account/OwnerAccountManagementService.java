package com.barbershop.backend.account;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.barbershop.backend.customer.CustomerAccountRepository;

@Service
public class OwnerAccountManagementService {

	private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9._-]{3,64}");

	private final ManagementAccountRepository accounts;
	private final CustomerAccountRepository customerAccounts;
	private final JdbcTemplate jdbc;
	private final PasswordEncoder passwordEncoder;

	public OwnerAccountManagementService(
			ManagementAccountRepository accounts,
			CustomerAccountRepository customerAccounts,
			JdbcTemplate jdbc,
			PasswordEncoder passwordEncoder) {
		this.accounts = accounts;
		this.customerAccounts = customerAccounts;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public String update(OwnerAccountRequest request) {
		AccountWriteLock.acquire(jdbc);
		ManagementAccount owner = accounts.findByRole(ManagementAccount.OWNER_ROLE)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner account is unavailable."));
		String username = UsernameNormalizer.normalize(request.username());
		if (!USERNAME_PATTERN.matcher(username).matches()) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Username must be 3-64 letters, numbers, dots, dashes, or underscores.");
		}
		if (!username.equals(owner.getUsernameNormalized())
				&& (customerAccounts.existsByUsernameNormalized(username)
						|| accounts.findByUsernameNormalized(username).isPresent())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is unavailable.");
		}

		boolean changingUsername = !username.equals(owner.getUsernameNormalized());
		boolean changingPassword = request.newPassword() != null && !request.newPassword().isBlank();
		if (changingUsername || changingPassword) {
			if (request.currentPassword() == null
					|| !passwordEncoder.matches(request.currentPassword(), owner.getPasswordHash())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
			}
		}
		if (changingPassword) {
			int passwordBytes = request.newPassword().getBytes(StandardCharsets.UTF_8).length;
			if (passwordBytes < 8 || passwordBytes > 72) {
				throw new ResponseStatusException(
						HttpStatus.BAD_REQUEST, "Password must contain 8-72 UTF-8 bytes.");
			}
			owner.setPasswordHash(passwordEncoder.encode(request.newPassword()));
		}

		owner.setUsernameNormalized(username);
		accounts.saveAndFlush(owner);
		return username;
	}

	public record OwnerAccountRequest(
			String username,
			String currentPassword,
			String newPassword) {
	}
}
