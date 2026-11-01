package com.barbershop.backend.customer;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, UUID> {

	Optional<CustomerAccount> findByUsernameNormalized(String usernameNormalized);

	boolean existsByUsernameNormalized(String usernameNormalized);

	@Transactional
	void deleteByUsernameNormalized(String usernameNormalized);
}
