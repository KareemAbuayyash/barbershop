package com.barbershop.backend.account;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.barbershop.backend.customer.CustomerAccountRepository;

@Component
public class OwnerAccountBootstrapper implements ApplicationRunner {

	private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9._-]{3,64}");

	private final OwnerBootstrapProperties properties;
	private final ManagementAccountRepository repository;
	private final CustomerAccountRepository customerAccountRepository;
	private final JdbcTemplate jdbcTemplate;
	private final PasswordEncoder passwordEncoder;
	private final TransactionTemplate transactionTemplate;

	public OwnerAccountBootstrapper(
			OwnerBootstrapProperties properties,
			ManagementAccountRepository repository,
			CustomerAccountRepository customerAccountRepository,
			JdbcTemplate jdbcTemplate,
			PasswordEncoder passwordEncoder,
			TransactionTemplate transactionTemplate) {
		this.properties = properties;
		this.repository = repository;
		this.customerAccountRepository = customerAccountRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.passwordEncoder = passwordEncoder;
		this.transactionTemplate = transactionTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.isBootstrapEnabled()) {
			return;
		}

		boolean usernameConfigured = !properties.getUsername().isBlank();
		boolean passwordConfigured = !properties.getPassword().isBlank();
		if (usernameConfigured != passwordConfigured) {
			throw new IllegalStateException(
					"Owner bootstrap requires both BARBER_ADMIN_USERNAME and BARBER_ADMIN_PASSWORD, or neither.");
		}

		transactionTemplate.executeWithoutResult(status -> {
			AccountWriteLock.acquire(jdbcTemplate);
			if (repository.existsByRole(ManagementAccount.OWNER_ROLE)) {
				return;
			}
			if (!usernameConfigured) {
				throw new IllegalStateException(
						"No owner account exists. Configure BARBER_ADMIN_USERNAME and BARBER_ADMIN_PASSWORD.");
			}

			String username = UsernameNormalizer.normalize(properties.getUsername());
			String password = properties.getPassword();
			validateCredentials(username, password);
			if (customerAccountRepository.existsByUsernameNormalized(username)) {
				throw new IllegalStateException(
						"BARBER_ADMIN_USERNAME is already used by a customer account. Configure a different owner username.");
			}
			repository.saveAndFlush(new ManagementAccount(username, passwordEncoder.encode(password)));
		});
	}

	private static void validateCredentials(String username, String password) {
		if (!USERNAME_PATTERN.matcher(username).matches()) {
			throw new IllegalStateException(
					"BARBER_ADMIN_USERNAME must normalize to 3-64 lowercase letters, digits, dots, underscores, or hyphens.");
		}
		int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
		if (passwordBytes < 12 || passwordBytes > 72) {
			throw new IllegalStateException(
					"BARBER_ADMIN_PASSWORD must contain 12-72 UTF-8 bytes for BCrypt.");
		}
	}
}
