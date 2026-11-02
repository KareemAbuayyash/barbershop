package com.barbershop.backend.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

	List<Booking> findByCustomerAccount_UsernameNormalizedOrderByCreatedAtDesc(String usernameNormalized);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select booking from Booking booking where booking.id = :id")
	Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

	@Query("""
			select booking
			from Booking booking
			left join booking.customer customer
			left join booking.customerAccount account
			where (:filterStatus = false or booking.status = :status)
			  and (:filterMode = false or booking.bookingMode = :mode)
			  and (:queueOnly = false or booking.bookingMode in :queueModes)
			  and (:filterQuery = false
			       or lower(coalesce(customer.name, account.displayName, ''))
			          like :searchPattern
			       or replace(replace(replace(replace(coalesce(customer.phoneNumber, account.phoneNumber, ''), ' ', ''), '-', ''), '(', ''), ')', '')
			          like :phonePattern)
			  and (:filterDate = false
			       or booking.queueDate = :date
			       or (booking.queueDate is null
			           and booking.startsAt >= :dayStart
			           and booking.startsAt < :nextDayStart))
			  and (
			      :filterFromDate = false
			      or booking.queueDate >= :fromDate
			      or (booking.queueDate is null and booking.startsAt >= :fromDayStart)
			  )
			  and (:filterToDate = false
			       or booking.queueDate <= :toDate
			       or (booking.queueDate is null and booking.startsAt < :dayAfterToStart))
			order by
			  case when :filterDate = true and booking.queueDate = :date
			       then booking.queuePosition else null end,
			  case when :filterDate = true and booking.queueDate is null
			       then booking.startsAt else null end,
			  booking.createdAt desc,
			  booking.id desc
			""")
	Page<Booking> findManagementBookings(
			@Param("filterStatus") boolean filterStatus,
			@Param("status") BookingStatus status,
			@Param("filterMode") boolean filterMode,
			@Param("mode") BookingMode mode,
			@Param("queueOnly") boolean queueOnly,
			@Param("queueModes") List<BookingMode> queueModes,
			@Param("filterQuery") boolean filterQuery,
			@Param("searchPattern") String searchPattern,
			@Param("phonePattern") String phonePattern,
			@Param("filterDate") boolean filterDate,
			@Param("date") LocalDate date,
			@Param("dayStart") Instant dayStart,
			@Param("nextDayStart") Instant nextDayStart,
			@Param("filterFromDate") boolean filterFromDate,
			@Param("fromDate") LocalDate fromDate,
			@Param("fromDayStart") Instant fromDayStart,
			@Param("filterToDate") boolean filterToDate,
			@Param("toDate") LocalDate toDate,
			@Param("dayAfterToStart") Instant dayAfterToStart,
			Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select booking
			from Booking booking
			where booking.queueDate = :date
			  and booking.status in :statuses
			order by booking.queuePosition
			""")
	List<Booking> findQueueBookingsForUpdate(
			@Param("date") LocalDate date,
			@Param("statuses") List<BookingStatus> statuses);

	@Query("select max(booking.queuePosition) from Booking booking where booking.queueDate = :date")
	Integer findMaximumQueuePosition(@Param("date") LocalDate date);
}
