package com.barbershop.backend.booking;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingSettingsRepository extends JpaRepository<BookingSettings, UUID> {
}
