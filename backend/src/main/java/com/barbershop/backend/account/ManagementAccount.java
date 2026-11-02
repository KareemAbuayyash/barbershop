package com.barbershop.backend.account;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "management_accounts")
public class ManagementAccount {

	public static final String OWNER_ROLE = "ROLE_OWNER";

	@Id
	@GeneratedValue
	@UuidGenerator
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "username_normalized", nullable = false, length = 64, unique = true)
	private String usernameNormalized;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "role", nullable = false, length = 32, unique = true)
	private String role = OWNER_ROLE;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ManagementAccount() {
	}

	public ManagementAccount(String usernameNormalized, String passwordHash) {
		this.usernameNormalized = usernameNormalized;
		this.passwordHash = passwordHash;
	}

	@PrePersist
	private void setCreationTimestamps() {
		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		if (updatedAt == null) {
			updatedAt = now;
		}
	}

	@PreUpdate
	private void setUpdatedTimestamp() {
		updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getUsernameNormalized() {
		return usernameNormalized;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getRole() {
		return role;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setUsernameNormalized(String usernameNormalized) {
		this.usernameNormalized = usernameNormalized;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}
}
