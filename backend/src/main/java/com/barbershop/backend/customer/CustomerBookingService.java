package com.barbershop.backend.customer;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Transactional;

import com.barbershop.backend.booking.Booking;
import com.barbershop.backend.booking.BookingRepository;
import com.barbershop.backend.booking.BookingManagementService;
import com.barbershop.backend.booking.dto.BookingManagementResponse;
import com.barbershop.backend.booking.dto.CustomerRescheduleRequest;
import com.barbershop.backend.customer.dto.CustomerBookingResponse;

@Service
@Transactional(readOnly = true)
public class CustomerBookingService {

	private final BookingRepository bookingRepository;
	private final BookingManagementService bookingManagementService;

	public CustomerBookingService(BookingRepository bookingRepository, BookingManagementService bookingManagementService) {
		this.bookingRepository = bookingRepository;
		this.bookingManagementService = bookingManagementService;
	}

	public List<CustomerBookingResponse> findBookings(String username) {
		return bookingRepository.findByCustomerAccount_UsernameNormalizedOrderByCreatedAtDesc(username).stream()
				.map(CustomerBookingService::toResponse)
				.toList();
	}

	@Transactional
	public BookingManagementResponse cancelBooking(java.util.UUID id, String username) {
		return bookingManagementService.cancelByCustomer(id, username);
	}

	@Transactional
	public BookingManagementResponse requestReschedule(
			java.util.UUID id, String username, CustomerRescheduleRequest request) {
		return bookingManagementService.requestRescheduleByCustomer(id, username, request);
	}

	private static CustomerBookingResponse toResponse(Booking booking) {
		return new CustomerBookingResponse(
				booking.getId(),
				booking.getService().getId(),
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
