package com.barbershop.backend.admin;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.admin.NotificationDtos.NotificationCenter;
import com.barbershop.backend.admin.NotificationDtos.NotificationPreferences;
import com.barbershop.backend.admin.NotificationDtos.PushSubscriptionRequest;
import com.barbershop.backend.admin.NotificationDtos.SubscriptionStatusRequest;
import com.barbershop.backend.admin.NotificationDtos.SubscriptionStatusResponse;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	private final NotificationService service;

	public NotificationController(NotificationService service) {
		this.service = service;
	}

	@GetMapping("/configuration")
	public Map<String, Object> configuration() {
		return service.publicConfiguration();
	}

	@GetMapping
	public NotificationCenter list(
			Authentication authentication,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "30") int size) {
		return service.listNotifications(authentication.getName(), isOwner(authentication), page, size);
	}

	@PatchMapping("/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markRead(@PathVariable UUID id, Authentication authentication) {
		service.markRead(authentication.getName(), isOwner(authentication), id);
	}

	@PostMapping("/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markAllRead(Authentication authentication) {
		service.markAllRead(authentication.getName(), isOwner(authentication));
	}

	@GetMapping("/preferences")
	public NotificationPreferences preferences(Authentication authentication) {
		return service.preferences(authentication.getName(), isOwner(authentication));
	}

	@PutMapping("/preferences")
	public NotificationPreferences updatePreferences(
			Authentication authentication,
			@Valid @RequestBody NotificationPreferences request) {
		return service.updatePreferences(authentication.getName(), isOwner(authentication), request);
	}

	@PostMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void subscribe(
			Authentication authentication,
			@Valid @RequestBody PushSubscriptionRequest request) {
		service.saveSubscription(
				authentication.getName(),
				isOwner(authentication),
				request.endpoint(),
				request.keys().p256dh(),
				request.keys().auth());
	}

	@PatchMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void setSubscriptionEnabled(
			Authentication authentication,
			@Valid @RequestBody SubscriptionStatusRequest request) {
		service.setSubscriptionEnabled(
				authentication.getName(), isOwner(authentication), request.endpoint(), request.enabled());
	}

	@GetMapping("/subscriptions/status")
	public SubscriptionStatusResponse subscriptionStatus(
			Authentication authentication,
			@RequestParam @NotBlank @Size(max = 2048) String endpoint) {
		return new SubscriptionStatusResponse(
				service.subscriptionEnabled(authentication.getName(), isOwner(authentication), endpoint));
	}

	@DeleteMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unsubscribe(
			Authentication authentication,
			@RequestParam @NotBlank @Size(max = 2048) String endpoint) {
		service.removeSubscription(authentication.getName(), isOwner(authentication), endpoint);
	}

	private boolean isOwner(Authentication authentication) {
		return authentication.getAuthorities().stream()
				.anyMatch(authority -> "ROLE_OWNER".equals(authority.getAuthority()));
	}
}

