package com.barbershop.backend.service;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {
}
