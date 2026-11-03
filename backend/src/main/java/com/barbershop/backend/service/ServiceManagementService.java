package com.barbershop.backend.service;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import com.barbershop.backend.admin.NotificationService;
import com.barbershop.backend.service.dto.ServiceRequest;
import com.barbershop.backend.service.dto.ServiceResponse;

@Service
@Transactional(readOnly = true)
public class ServiceManagementService {

	private final ServiceRepository serviceRepository;
	private final NotificationService notificationService;
	private final JdbcTemplate jdbc;

	public ServiceManagementService(ServiceRepository serviceRepository, NotificationService notificationService, JdbcTemplate jdbc) {
		this.serviceRepository = serviceRepository;
		this.notificationService = notificationService;
		this.jdbc = jdbc;
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

	@Transactional
	public int archiveAllActiveServices() {
		List<ServiceEntity> activeServices = serviceRepository.findAllByActiveTrueOrderByNameAsc();
		activeServices.forEach(service -> service.setActive(false));
		serviceRepository.saveAllAndFlush(activeServices);
		activeServices.forEach(service -> notificationService.notifyServiceChanged(service.getId(), "archived"));
		return activeServices.size();
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
		ServiceEntity saved = serviceRepository.saveAndFlush(service);
		notificationService.notifyServiceChanged(saved.getId(), "created");
		return toResponse(saved);
	}

	@Transactional
	public ServiceResponse updateService(UUID id, ServiceRequest request) {
		ServiceEntity service = serviceRepository.findById(id)
				.orElseThrow(() -> serviceNotFound(id));
		service.setName(request.name());
		service.setDescription(request.description());
		service.setPrice(request.price());
		service.setDurationMinutes(request.durationMinutes());
		ServiceEntity saved = serviceRepository.saveAndFlush(service);
		notificationService.notifyServiceChanged(saved.getId(), "updated");
		return toResponse(saved);
	}

	@Transactional
	public ServiceResponse setServiceActive(UUID id, boolean active) {
		ServiceEntity service = serviceRepository.findById(id)
				.orElseThrow(() -> serviceNotFound(id));
		service.setActive(active);
		ServiceEntity saved = serviceRepository.saveAndFlush(service);
		notificationService.notifyServiceChanged(saved.getId(), active ? "activated" : "archived");
		return toResponse(saved);
	}

	@Transactional
	public void saveServiceImage(UUID id, MultipartFile image) {
		ServiceEntity service = serviceRepository.findById(id).orElseThrow(() -> serviceNotFound(id));
		if (image == null || image.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "اختر صورة للخدمة.");
		if (image.getSize() > 2 * 1024 * 1024) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "يجب أن يكون حجم الصورة 2 ميغابايت أو أقل.");
		}
		try {
			byte[] data = image.getBytes();
			String contentType = imageContentType(data);
			if (contentType == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "الصيغ المدعومة هي PNG وJPEG وWebP.");
			}
			jdbc.update("UPDATE services SET image_data = ?, image_content_type = ? WHERE id = ?", data, contentType, id);
			service.setImageContentType(contentType);
		} catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "تعذرت قراءة صورة الخدمة.", exception);
		}
	}

	@Transactional(readOnly = true)
	public ServiceImage serviceImage(UUID id) {
		List<ServiceImage> images = jdbc.query("SELECT image_content_type, image_data FROM services WHERE id = ? AND image_data IS NOT NULL",
				(rs, row) -> new ServiceImage(rs.getString("image_content_type"), rs.getBytes("image_data")), id);
		if (images.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service image not found.");
		return images.get(0);
	}

	private String imageContentType(byte[] data) {
		if (data.length >= 8 && (data[0] & 0xff) == 0x89 && data[1] == 0x50 && data[2] == 0x4e
				&& data[3] == 0x47 && data[4] == 0x0d && data[5] == 0x0a && data[6] == 0x1a && data[7] == 0x0a) return "image/png";
		if (data.length >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) return "image/jpeg";
		if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
				&& data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return "image/webp";
		return null;
	}

	public record ServiceImage(String contentType, byte[] data) {}

	private static ServiceResponse toResponse(ServiceEntity service) {
		return new ServiceResponse(
				service.getId(),
				service.getName(),
				service.getDescription(),
				service.getPrice(),
				service.getDurationMinutes(),
				service.isActive(),
				service.getCreatedAt(),
				service.getImageContentType() == null ? null : "/api/services/" + service.getId() + "/image");
	}

	private static ResponseStatusException serviceNotFound(UUID id) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found: " + id);
	}
}
