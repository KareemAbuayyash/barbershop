package com.barbershop.backend.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.barbershop.backend.account.ManagementAccount;
import com.barbershop.backend.account.ManagementAccountRepository;
import com.barbershop.backend.account.UsernameNormalizer;
import com.barbershop.backend.customer.CustomerAccount;
import com.barbershop.backend.customer.CustomerAccountRepository;

@Service
public class ManagementUserDetailsService implements UserDetailsService {

	private final ManagementAccountRepository repository;
	private final CustomerAccountRepository customerAccountRepository;

	public ManagementUserDetailsService(
			ManagementAccountRepository repository,
			CustomerAccountRepository customerAccountRepository) {
		this.repository = repository;
		this.customerAccountRepository = customerAccountRepository;
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		String normalizedUsername = UsernameNormalizer.normalize(username);
		return repository.findByUsernameNormalized(normalizedUsername)
				.map(account -> User.withUsername(account.getUsernameNormalized())
						.password(account.getPasswordHash())
						.authorities(account.getRole())
						.disabled(!account.isEnabled())
						.build())
				.or(() -> customerAccountRepository.findByUsernameNormalized(normalizedUsername)
						.map(this::toCustomerUser))
				.orElseThrow(() -> new UsernameNotFoundException("Account not found."));
	}

	private org.springframework.security.core.userdetails.UserDetails toCustomerUser(CustomerAccount account) {
		return User.withUsername(account.getUsernameNormalized())
				.password(account.getPasswordHash())
				.authorities(CustomerAccount.CUSTOMER_ROLE)
				.disabled(!account.isEnabled())
				.build();
	}
}
