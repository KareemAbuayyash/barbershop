package com.barbershop.backend.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagementAccountRepository extends JpaRepository<ManagementAccount, UUID> {

	Optional<ManagementAccount> findByUsernameNormalized(String usernameNormalized);

	boolean existsByRole(String role);

	Optional<ManagementAccount> findByRole(String role);
}
