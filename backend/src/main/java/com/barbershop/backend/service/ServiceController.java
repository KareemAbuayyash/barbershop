package com.barbershop.backend.service;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.service.dto.ServiceActiveRequest;
import com.barbershop.backend.service.dto.ServiceRequest;
import com.barbershop.backend.service.dto.ServiceResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

	private final ServiceManagementService serviceManagementService;

	public ServiceController(ServiceManagementService serviceManagementService) {
		this.serviceManagementService = serviceManagementService;
	}

	@GetMapping
	public List<ServiceResponse> getActiveServices() {
		return serviceManagementService.findActiveServices();
	}

	@GetMapping("/manage")
	public List<ServiceResponse> getAllServicesForManagement() {
		return serviceManagementService.findAllServices();
	}

	@GetMapping("/{id}")
	public ServiceResponse getActiveService(@PathVariable UUID id) {
		return serviceManagementService.findActiveService(id);
	}

	@PostMapping
	public ResponseEntity<ServiceResponse> createService(@Valid @RequestBody ServiceRequest request) {
		ServiceResponse created = serviceManagementService.createService(request);
		return ResponseEntity.created(URI.create("/api/services/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	public ServiceResponse updateService(
			@PathVariable UUID id,
			@Valid @RequestBody ServiceRequest request) {
		return serviceManagementService.updateService(id, request);
	}

	@PatchMapping("/{id}/active")
	public ServiceResponse setServiceActive(
			@PathVariable UUID id,
			@Valid @RequestBody ServiceActiveRequest request) {
		return serviceManagementService.setServiceActive(id, request.active());
	}
}
