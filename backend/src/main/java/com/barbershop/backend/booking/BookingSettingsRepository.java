package com.barbershop.backend.booking;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingSettingsRepository extends JpaRepository<BookingSettings, UUID> {

	Optional<BookingSettings> findBySingletonKeyTrue();
}
