package com.barbershop.backend.admin;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OwnerPushController {

	private final OwnerPushService service;

	public OwnerPushController(OwnerPushService service) {
		this.service = service;
	}

	@GetMapping("/api/push/configuration")
	public Map<String, Object> configuration() {
		return service.publicConfiguration();
	}

	@PostMapping("/api/admin/push-subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void subscribe(@Valid @RequestBody PushSubscriptionRequest request) {
		service.saveSubscription(request.endpoint(), request.keys().p256dh(), request.keys().auth());
	}

	@DeleteMapping("/api/admin/push-subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unsubscribe(@RequestParam @NotBlank @Size(max = 2048) String endpoint) {
		service.removeSubscription(endpoint);
	}

	public record PushSubscriptionRequest(
			@NotBlank @Size(max = 2048) String endpoint,
			@NotNull @Valid Keys keys) {
	}

	public record Keys(
			@NotBlank @Size(max = 200) String p256dh,
			@NotBlank @Size(max = 100) String auth) {
	}

}
