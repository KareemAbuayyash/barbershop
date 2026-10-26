package com.barbershop.backend.booking;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "booking_settings")
public class BookingSettings {

	@Id
	@GeneratedValue
	@UuidGenerator
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "exact_appointment_enabled", nullable = false)
	private boolean exactAppointmentEnabled;

	@Column(name = "confirmation_request_enabled", nullable = false)
	private boolean confirmationRequestEnabled;

	@Column(name = "day_queue_enabled", nullable = false)
	private boolean dayQueueEnabled;

	@Column(name = "same_day_queue_enabled", nullable = false)
	private boolean sameDayQueueEnabled;

	@Column(name = "new_bookings_disabled", nullable = false)
	private boolean newBookingsDisabled = true;

	@Column(name = "singleton_key", nullable = false, unique = true, insertable = false, updatable = false)
	private boolean singletonKey = true;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BookingSettings() {
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

	public boolean isExactAppointmentEnabled() {
		return exactAppointmentEnabled;
	}

	public boolean isConfirmationRequestEnabled() {
		return confirmationRequestEnabled;
	}

	public boolean isDayQueueEnabled() {
		return dayQueueEnabled;
	}

	public boolean isSameDayQueueEnabled() {
		return sameDayQueueEnabled;
	}

	public boolean isNewBookingsDisabled() {
		return newBookingsDisabled;
	}

	public boolean isSingletonKey() {
		return singletonKey;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setExactAppointmentEnabled(boolean exactAppointmentEnabled) {
		this.exactAppointmentEnabled = exactAppointmentEnabled;
	}

	public void setConfirmationRequestEnabled(boolean confirmationRequestEnabled) {
		this.confirmationRequestEnabled = confirmationRequestEnabled;
	}

	public void setDayQueueEnabled(boolean dayQueueEnabled) {
		this.dayQueueEnabled = dayQueueEnabled;
	}

	public void setSameDayQueueEnabled(boolean sameDayQueueEnabled) {
		this.sameDayQueueEnabled = sameDayQueueEnabled;
	}

	public void setNewBookingsDisabled(boolean newBookingsDisabled) {
		this.newBookingsDisabled = newBookingsDisabled;
	}
}
