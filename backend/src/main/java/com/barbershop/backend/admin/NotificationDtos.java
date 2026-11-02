package com.barbershop.backend.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class NotificationDtos {

	private NotificationDtos() {
	}

	public record NotificationItem(
			UUID id,
			String eventType,
			String title,
			String body,
			String targetPath,
			Instant createdAt,
			Instant readAt) {
	}

	public record NotificationCenter(
			List<NotificationItem> content,
			long unreadCount,
			int page,
			int size,
			long totalElements,
			int totalPages) {
	}

	public record PushSubscriptionRequest(
			@NotBlank @Size(max = 2048) String endpoint,
			@NotNull @Valid Keys keys) {
	}

	public record SubscriptionStatusRequest(
			@NotBlank @Size(max = 2048) String endpoint,
			boolean enabled) {
	}

	public record SubscriptionStatusResponse(boolean enabled) {
	}

	public record Keys(
			@NotBlank @Size(max = 200) String p256dh,
			@NotBlank @Size(max = 100) String auth) {
	}

	public record NotificationPreferences(Map<String, Boolean> preferences) {
	}
}

