package com.barbershop.backend.account;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerAccountBootstrapIntegrationTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private ManagementAccountRepository repository;

	@Autowired
	private OwnerAccountBootstrapper bootstrapper;

	@Autowired
	private OwnerBootstrapProperties properties;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private String schema;

	@org.junit.jupiter.api.BeforeAll
	void createIsolatedSchema() throws Exception {
		schema = "owner_bootstrap_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA " + schema);
		}
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.load()
				.migrate();
	}

	@BeforeEach
	void clearIsolatedAccount() {
		properties.setUsername("");
		properties.setPassword("");
		inSchemaAction(() -> repository.deleteAllInBatch());
	}

	@AfterEach
	void clearCredentials() {
		properties.setUsername("");
		properties.setPassword("");
		properties.setBootstrapEnabled(false);
	}

	@AfterAll
	void dropIsolatedSchema() throws Exception {
		if (schema != null) {
			try (Connection connection = dataSource.getConnection();
					Statement statement = connection.createStatement()) {
				statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
			}
		}
	}

	@Test
	void createsExactlyOneOwnerWithBcryptHash() {
		properties.setUsername(" Owner ");
		properties.setPassword("first-test-password");

		runBootstrap();

		ManagementAccount account = inSchemaValue(() -> repository.findByUsernameNormalized("owner").orElseThrow());
		assertThat(account.getRole()).isEqualTo(ManagementAccount.OWNER_ROLE);
		assertThat(account.getPasswordHash()).isNotEqualTo("first-test-password");
		assertThat(passwordEncoder.matches("first-test-password", account.getPasswordHash())).isTrue();
		assertThat(ownerCount()).isEqualTo(1);
	}

	@Test
	void acceptsAnEightByteOwnerPassword() {
		properties.setUsername("owner");
		properties.setPassword("eight888");

		runBootstrap();

		ManagementAccount account = inSchemaValue(() -> repository.findByUsernameNormalized("owner").orElseThrow());
		assertThat(passwordEncoder.matches("eight888", account.getPasswordHash())).isTrue();
	}

	@Test
	void startupNeverResetsAnExistingOwnersPassword() {
		properties.setUsername("Owner");
		properties.setPassword("first-test-password");
		runBootstrap();
		String originalHash = inSchemaValue(() -> repository.findByUsernameNormalized("owner").orElseThrow()
				.getPasswordHash());

		properties.setUsername("different-owner");
		properties.setPassword("another-test-password");
		runBootstrap();

		assertThat(ownerCount()).isEqualTo(1);
		assertThat(inSchemaValue(() -> repository.findByUsernameNormalized("owner").orElseThrow().getPasswordHash()))
				.isEqualTo(originalHash);
		assertThat(inSchemaValue(() -> repository.findByUsernameNormalized("different-owner"))).isEmpty();
	}

	@Test
	void missingAndPartialBootstrapConfigurationFailWithoutAnOwner() {
		assertThatThrownBy(this::runBootstrap)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No owner account exists");

		properties.setUsername("owner");
		assertThatThrownBy(this::runBootstrap)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("both BARBER_ADMIN_USERNAME and BARBER_ADMIN_PASSWORD");
	}

	@Test
	void partialConfigurationFailsEvenWhenAnOwnerAlreadyExists() {
		properties.setUsername("owner");
		properties.setPassword("first-test-password");
		runBootstrap();
		properties.setPassword("");

		assertThatThrownBy(this::runBootstrap)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("both BARBER_ADMIN_USERNAME and BARBER_ADMIN_PASSWORD");
		assertThat(ownerCount()).isEqualTo(1);
	}

	@Test
	void concurrentStartupCreatesOnlyOneOwner() throws Exception {
		properties.setUsername("owner");
		properties.setPassword("first-test-password");
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(this::runBootstrap);
			Future<?> second = executor.submit(this::runBootstrap);
			first.get();
			second.get();
		} finally {
			executor.shutdownNow();
		}

		assertThat(ownerCount()).isEqualTo(1);
	}

	@Test
	void migrationRestrictsOwnerRoleAndEnforcesItsUniqueness() {
		inSchemaAction(() -> {
			jdbcTemplate.update("""
					INSERT INTO management_accounts (
					    id, username_normalized, password_hash, role
					) VALUES (?, 'owner-one', ?, 'ROLE_OWNER')
					""", UUID.randomUUID(), "{bcrypt}test");
		});

		assertThatThrownBy(() -> inSchemaAction(() -> jdbcTemplate.update("""
				INSERT INTO management_accounts (
				    id, username_normalized, password_hash, role
				) VALUES (?, 'owner-two', ?, 'ROLE_OWNER')
				""", UUID.randomUUID(), "{bcrypt}test")))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> inSchemaAction(() -> jdbcTemplate.update("""
				INSERT INTO management_accounts (
				    id, username_normalized, password_hash, role
				) VALUES (?, 'other-role', ?, 'ROLE_BARBER')
				""", UUID.randomUUID(), "{bcrypt}test")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void runBootstrap() {
		properties.setBootstrapEnabled(true);
		inSchemaAction(() -> bootstrapper.run(new DefaultApplicationArguments(new String[0])));
	}

	private long ownerCount() {
		return inSchemaValue(() -> repository.count());
	}

	private void inSchemaAction(Runnable action) {
		transactionTemplate.executeWithoutResult(status -> {
			jdbcTemplate.execute("SET LOCAL search_path TO \"" + schema + "\"");
			action.run();
		});
	}

	private <T> T inSchemaValue(java.util.concurrent.Callable<T> action) {
		return transactionTemplate.execute(status -> {
			jdbcTemplate.execute("SET LOCAL search_path TO \"" + schema + "\"");
			try {
				return action.call();
			} catch (RuntimeException exception) {
				throw exception;
			} catch (Exception exception) {
				throw new IllegalStateException(exception);
			}
		});
	}
}
