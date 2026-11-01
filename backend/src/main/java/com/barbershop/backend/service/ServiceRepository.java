package com.barbershop.backend.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {

	List<ServiceEntity> findAllByActiveTrueOrderByNameAsc();

	List<ServiceEntity> findAllByOrderByNameAsc();

	Optional<ServiceEntity> findByIdAndActiveTrue(UUID id);
}
