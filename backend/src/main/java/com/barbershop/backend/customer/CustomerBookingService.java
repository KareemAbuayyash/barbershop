package com.barbershop.backend.customer;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.barbershop.backend.booking.Booking;
import com.barbershop.backend.booking.BookingRepository;
import com.barbershop.backend.customer.dto.CustomerBookingResponse;

@Service
@Transactional(readOnly = true)
public class CustomerBookingService {

	private final BookingRepository bookingRepository;

	public CustomerBookingService(BookingRepository bookingRepository) {
		this.bookingRepository = bookingRepository;
	}

	public List<CustomerBookingResponse> findBookings(String username) {
		return bookingRepository.findByCustomerAccount_UsernameNormalizedOrderByCreatedAtDesc(username).stream()
				.map(CustomerBookingService::toResponse)
				.toList();
	}

	private static CustomerBookingResponse toResponse(Booking booking) {
		return new CustomerBookingResponse(
				booking.getId(),
				booking.getBookingMode(),
				booking.getStatus(),
				booking.getStartsAt(),
				booking.getQueueDate(),
				booking.getQueuePosition(),
				booking.getNotes(),
				booking.getOwnerNote(),
				booking.getServiceNameSnapshot(),
				booking.getServicePriceSnapshot(),
				booking.getServiceDurationSnapshotMinutes(),
				booking.getCreatedAt());
	}
}
