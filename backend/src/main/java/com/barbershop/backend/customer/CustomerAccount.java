package com.barbershop.backend.customer;

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
@Table(name = "customer_accounts")
public class CustomerAccount {

	public static final String CUSTOMER_ROLE = "ROLE_CUSTOMER";

	@Id
	@GeneratedValue
	@UuidGenerator
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "username_normalized", nullable = false, length = 64, unique = true)
	private String usernameNormalized;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 150)
	private String displayName;

	@Column(name = "phone_number", nullable = false, length = 32)
	private String phoneNumber;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CustomerAccount() {
	}

	public CustomerAccount(
			String usernameNormalized,
			String passwordHash,
			String displayName,
			String phoneNumber) {
		this.usernameNormalized = usernameNormalized;
		this.passwordHash = passwordHash;
		this.displayName = displayName;
		this.phoneNumber = phoneNumber;
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

	public String getDisplayName() {
		return displayName;
	}

	public String getPhoneNumber() {
		return phoneNumber;
	}

	public boolean isEnabled() {
		return enabled;
	}
}
