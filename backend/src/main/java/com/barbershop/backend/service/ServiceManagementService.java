package com.barbershop.backend.service;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.barbershop.backend.service.dto.ServiceRequest;
import com.barbershop.backend.service.dto.ServiceResponse;

@Service
@Transactional(readOnly = true)
public class ServiceManagementService {

	private final ServiceRepository serviceRepository;

	public ServiceManagementService(ServiceRepository serviceRepository) {
		this.serviceRepository = serviceRepository;
	}

	public List<ServiceResponse> findActiveServices() {
		return serviceRepository.findAllByActiveTrueOrderByNameAsc().stream()
				.map(ServiceManagementService::toResponse)
				.toList();
	}

	public List<ServiceResponse> findAllServices() {
		return serviceRepository.findAllByOrderByNameAsc().stream()
				.map(ServiceManagementService::toResponse)
				.toList();
	}

	public ServiceResponse findActiveService(UUID id) {
		return serviceRepository.findByIdAndActiveTrue(id)
				.map(ServiceManagementService::toResponse)
				.orElseThrow(() -> serviceNotFound(id));
	}

	@Transactional
	public ServiceResponse createService(ServiceRequest request) {
		ServiceEntity service = new ServiceEntity(
				request.name(),
				request.description(),
				request.price(),
				request.durationMinutes());
		return toResponse(serviceRepository.save(service));
	}

	@Transactional
	public ServiceResponse updateService(UUID id, ServiceRequest request) {
		ServiceEntity service = serviceRepository.findById(id)
				.orElseThrow(() -> serviceNotFound(id));
		service.setName(request.name());
		service.setDescription(request.description());
		service.setPrice(request.price());
		service.setDurationMinutes(request.durationMinutes());
		return toResponse(serviceRepository.save(service));
	}

	@Transactional
	public ServiceResponse setServiceActive(UUID id, boolean active) {
		ServiceEntity service = serviceRepository.findById(id)
				.orElseThrow(() -> serviceNotFound(id));
		service.setActive(active);
		return toResponse(serviceRepository.save(service));
	}

	private static ServiceResponse toResponse(ServiceEntity service) {
		return new ServiceResponse(
				service.getId(),
				service.getName(),
				service.getDescription(),
				service.getPrice(),
				service.getDurationMinutes(),
				service.isActive(),
				service.getCreatedAt());
	}

	private static ResponseStatusException serviceNotFound(UUID id) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found: " + id);
	}
}
