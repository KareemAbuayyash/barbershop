package com.barbershop.backend.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.barbershop.backend.customer.CustomerEntity;
import com.barbershop.backend.service.ServiceEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "bookings")
public class Booking {

	@Id
	@GeneratedValue
	@UuidGenerator
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "customer_id")
	private CustomerEntity customer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "service_id", nullable = false)
	private ServiceEntity service;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private BookingStatus status = BookingStatus.PENDING;

	@Column(name = "notes", columnDefinition = "text")
	private String notes;

	@Column(name = "service_name_snapshot", nullable = false, length = 150)
	private String serviceNameSnapshot;

	@Column(name = "service_price_snapshot", nullable = false, precision = 10, scale = 2)
	private BigDecimal servicePriceSnapshot;

	@Column(name = "service_duration_snapshot_minutes", nullable = false)
	private int serviceDurationSnapshotMinutes;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Booking() {
	}

	public Booking(CustomerEntity customer, ServiceEntity service, Instant startsAt, String notes) {
		this.customer = customer;
		this.service = service;
		this.startsAt = startsAt;
		this.notes = notes;
		this.serviceNameSnapshot = service.getName();
		this.servicePriceSnapshot = service.getPrice();
		this.serviceDurationSnapshotMinutes = service.getDurationMinutes();
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

	public CustomerEntity getCustomer() {
		return customer;
	}

	public ServiceEntity getService() {
		return service;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public BookingStatus getStatus() {
		return status;
	}

	public String getNotes() {
		return notes;
	}

	public String getServiceNameSnapshot() {
		return serviceNameSnapshot;
	}

	public BigDecimal getServicePriceSnapshot() {
		return servicePriceSnapshot;
	}

	public int getServiceDurationSnapshotMinutes() {
		return serviceDurationSnapshotMinutes;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setStartsAt(Instant startsAt) {
		this.startsAt = startsAt;
	}

	public void setStatus(BookingStatus status) {
		this.status = status;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}
}
