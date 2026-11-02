package com.barbershop.backend.admin;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.admin.NotificationDtos.NotificationCenter;
import com.barbershop.backend.admin.NotificationDtos.PushSubscriptionRequest;
import com.barbershop.backend.admin.NotificationDtos.SubscriptionStatusRequest;
import com.barbershop.backend.admin.NotificationDtos.SubscriptionStatusResponse;

@RestController
@RequestMapping("/api/guest-notifications/{token}")
public class GuestNotificationController {

	private final NotificationService service;

	public GuestNotificationController(NotificationService service) {
		this.service = service;
	}

	@GetMapping("/configuration")
	public Map<String, Object> configuration(@PathVariable String token) {
		return service.guestConfiguration(token);
	}

	@GetMapping
	public NotificationCenter list(
			@PathVariable String token,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "30") int size) {
		return service.listGuestNotifications(token, page, size);
	}

	@PatchMapping("/{notificationId}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markRead(@PathVariable String token, @PathVariable UUID notificationId) {
		service.markGuestRead(token, notificationId);
	}

	@PostMapping("/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markAllRead(@PathVariable String token) {
		service.markAllGuestRead(token);
	}

	@PostMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void subscribe(
			@PathVariable String token,
			@Valid @RequestBody PushSubscriptionRequest request) {
		service.saveGuestSubscription(token, request.endpoint(), request.keys().p256dh(), request.keys().auth());
	}

	@PatchMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void setSubscriptionEnabled(
			@PathVariable String token,
			@Valid @RequestBody SubscriptionStatusRequest request) {
		service.setGuestSubscriptionEnabled(token, request.endpoint(), request.enabled());
	}

	@GetMapping("/subscriptions/status")
	public SubscriptionStatusResponse subscriptionStatus(
			@PathVariable String token,
			@RequestParam @NotBlank @Size(max = 2048) String endpoint) {
		return new SubscriptionStatusResponse(service.guestSubscriptionEnabled(token, endpoint));
	}

	@DeleteMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unsubscribe(
			@PathVariable String token,
			@RequestParam @NotBlank @Size(max = 2048) String endpoint) {
		service.removeGuestSubscription(token, endpoint);
	}
}
