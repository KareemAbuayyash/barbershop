package com.barbershop.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "services")
public class ServiceEntity {

	@Id
	@GeneratedValue
	@UuidGenerator
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "name", nullable = false, length = 150)
	private String name;

	@Column(name = "description", nullable = false, columnDefinition = "text")
	private String description;

	@Column(name = "price", nullable = false, precision = 10, scale = 2)
	private BigDecimal price;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "image_content_type", length = 100)
	private String imageContentType;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ServiceEntity() {
	}

	public ServiceEntity(String name, String description, BigDecimal price, int durationMinutes) {
		this.name = name;
		this.description = description;
		this.price = price;
		this.durationMinutes = durationMinutes;
	}

	@PrePersist
	private void setCreationTimestamp() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getImageContentType() {
		return imageContentType;
	}

	public void setImageContentType(String imageContentType) {
		this.imageContentType = imageContentType;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public void setDurationMinutes(int durationMinutes) {
		this.durationMinutes = durationMinutes;
	}

	public void setActive(boolean active) {
		this.active = active;
	}
}
