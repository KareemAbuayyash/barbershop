package com.barbershop.backend.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.barbershop.backend.account.ManagementAccount;
import com.barbershop.backend.account.ManagementAccountRepository;
import com.barbershop.backend.account.UsernameNormalizer;

@Service
public class ManagementUserDetailsService implements UserDetailsService {

	private final ManagementAccountRepository repository;

	public ManagementUserDetailsService(ManagementAccountRepository repository) {
		this.repository = repository;
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		String normalizedUsername = UsernameNormalizer.normalize(username);
		ManagementAccount account = repository.findByUsernameNormalized(normalizedUsername)
				.orElseThrow(() -> new UsernameNotFoundException("Management account not found."));
		return User.withUsername(account.getUsernameNormalized())
				.password(account.getPasswordHash())
				.authorities(account.getRole())
				.disabled(!account.isEnabled())
				.build();
	}
}
