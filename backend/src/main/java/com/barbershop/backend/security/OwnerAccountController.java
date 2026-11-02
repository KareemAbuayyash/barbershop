package com.barbershop.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.account.OwnerAccountManagementService;

@RestController
@RequestMapping("/api/admin/owner")
public class OwnerAccountController {

	private final OwnerAccountManagementService ownerAccountManagementService;
	private final UserDetailsService userDetailsService;
	private final SecurityContextRepository securityContextRepository;

	public OwnerAccountController(
			OwnerAccountManagementService ownerAccountManagementService,
			UserDetailsService userDetailsService,
			SecurityContextRepository securityContextRepository) {
		this.ownerAccountManagementService = ownerAccountManagementService;
		this.userDetailsService = userDetailsService;
		this.securityContextRepository = securityContextRepository;
	}

	@GetMapping
	public OwnerProfile currentOwner(Authentication authentication) {
		return new OwnerProfile(authentication.getName());
	}

	@PutMapping
	public OwnerProfile updateOwner(
			@Valid @RequestBody OwnerProfileUpdate request,
			Authentication authentication,
			HttpServletRequest servletRequest,
			HttpServletResponse servletResponse) {
		String username = ownerAccountManagementService.update(new OwnerAccountManagementService.OwnerAccountRequest(
				request.username(), request.currentPassword(), request.newPassword()));
		UserDetails owner = userDetailsService.loadUserByUsername(username);
		Authentication updatedAuthentication = UsernamePasswordAuthenticationToken.authenticated(
				owner,
				null,
				owner.getAuthorities());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(updatedAuthentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, servletRequest, servletResponse);
		return new OwnerProfile(username);
	}

	public record OwnerProfile(String username) {
	}

	public record OwnerProfileUpdate(
			@NotBlank @Size(max = 64) String username,
			@Size(max = 72) String currentPassword,
			@Size(max = 72) String newPassword) {
	}
}
