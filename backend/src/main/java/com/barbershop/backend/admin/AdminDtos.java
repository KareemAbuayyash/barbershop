package com.barbershop.backend.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AdminDtos {

	private AdminDtos() {
	}

	public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
	}

	public record DashboardResponse(
			long pendingBookings,
			long bookingsToday,
			long activeCustomers,
			BigDecimal collectedToday,
			BigDecimal outstandingDebt,
			long lowStockProducts) {
	}

	public record DashboardTrendPoint(LocalDate date, long bookings, BigDecimal collected) {
	}

	public record CustomerSummary(
			UUID id,
			String name,
			String phoneNumber,
			long visits,
			BigDecimal totalPaid,
			BigDecimal outstandingDebt,
			boolean hasAccount) {
	}

	public record CustomerBooking(
			UUID id,
			String serviceName,
			BigDecimal servicePrice,
			String status,
			Instant createdAt,
			List<RetailItem> products) {
	}

	public record RetailItem(
			String productName,
			String unit,
			BigDecimal quantity,
			BigDecimal unitPrice,
			BigDecimal lineTotal) {
	}

	public record Payment(
			UUID id,
			UUID bookingId,
			String customerName,
			BigDecimal amount,
			String method,
			String notes,
			Instant paidAt) {
	}

	public record CustomerDetails(
			CustomerSummary customer,
			List<CustomerBooking> bookings,
			List<Payment> payments,
			List<CustomerAccountSummary> accounts) {
	}

	public record CustomerAccountSummary(UUID id, String username, String displayName, String phoneNumber) {
	}

	public record CustomerUpdateRequest(
			@NotBlank @Size(max = 150) String name,
			@Size(max = 32) String phoneNumber) {
	}

	public record CustomerPasswordRequest(@NotBlank @Size(max = 72) String newPassword) {
	}

	public record PaymentRequest(
			@NotNull @DecimalMin(value = "0.01") BigDecimal amount,
			@NotBlank String method,
			@Size(max = 1000) String notes) {
	}

	public record CheckoutRequest(
			@NotNull @DecimalMin(value = "0.00") BigDecimal amount,
			@NotBlank String method,
			@Size(max = 1000) String notes,
			@NotNull @Size(max = 30) List<@NotNull @jakarta.validation.Valid CheckoutProduct> products) {
	}

	public record CheckoutProduct(
			@NotNull UUID productId,
			@NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
			@NotNull @DecimalMin(value = "0.01") BigDecimal unitPrice) {
	}

	public record Expense(
			UUID id,
			String category,
			String description,
			BigDecimal amount,
			LocalDate expenseDate,
			String method,
			String notes,
			Instant createdAt) {
	}

	public record ExpenseRequest(
			@NotBlank @Size(max = 80) String category,
			@NotBlank @Size(max = 500) String description,
			@NotNull @DecimalMin(value = "0.01") BigDecimal amount,
			@NotNull LocalDate expenseDate,
			@NotBlank String method,
			@Size(max = 1000) String notes) {
	}

	public record ReportResponse(
			LocalDate fromDate,
			LocalDate toDate,
			BigDecimal collectedRevenue,
			BigDecimal expenses,
			BigDecimal netProfit,
			BigDecimal outstandingDebt) {
	}

	public record BestSellingProduct(
			String productName,
			String unit,
			BigDecimal quantitySold,
			BigDecimal salesTotal,
			long bookingCount) {
	}

	public record BestSellingService(String serviceName, long bookingCount, BigDecimal serviceRevenue) {
	}

	public record BookingSalesReport(
			UUID bookingId,
			String customerName,
			Instant completedAt,
			String serviceName,
			BigDecimal servicePrice,
			List<RetailItem> products,
			BigDecimal invoiceTotal,
			BigDecimal amountPaid,
			BigDecimal balance) {
	}

	public record Product(
			UUID id,
			String name,
			String unit,
			BigDecimal quantity,
			BigDecimal lowStockThreshold,
			BigDecimal purchasePrice,
			BigDecimal salePrice,
			String imageUrl,
			boolean active) {
	}

	public record ProductRequest(
			@NotBlank @Size(max = 150) String name,
			@NotBlank @Size(max = 30) String unit,
			@JsonAlias("initialQuantity")
			@NotNull @DecimalMin(value = "0.000", inclusive = true) BigDecimal availableQuantity,
			@NotNull @DecimalMin(value = "0.000", inclusive = true) BigDecimal lowStockThreshold,
			@DecimalMin(value = "0.00", inclusive = true) BigDecimal purchasePrice,
			@DecimalMin(value = "0.01") BigDecimal salePrice) {
	}

	public record ProductUpdateRequest(
			@NotBlank @Size(max = 150) String name,
			@NotBlank @Size(max = 30) String unit,
			@NotNull @DecimalMin(value = "0.000", inclusive = true) BigDecimal lowStockThreshold,
			@DecimalMin(value = "0.00", inclusive = true) BigDecimal purchasePrice,
			@DecimalMin(value = "0.01") BigDecimal salePrice,
			boolean active) {
	}

	public record ProductImage(String contentType, byte[] data) {
	}

	public record InventoryMovement(
			@NotBlank String movementType,
			@NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
			@DecimalMin(value = "0.00") BigDecimal unitCost,
			@Size(max = 1000) String notes) {
	}

	public record InventoryMovementResponse(
			UUID id,
			UUID productId,
			String movementType,
			BigDecimal quantity,
			BigDecimal unitCost,
			String notes,
			Instant createdAt) {
	}

	public record Employee(
			UUID id,
			String name,
			String phoneNumber,
			String jobTitle,
			BigDecimal baseSalary,
			BigDecimal commissionPercent,
			BigDecimal commissionEarned,
			BigDecimal commissionPaid,
			boolean active) {
	}

	public record EmployeeRequest(
			@NotBlank @Size(max = 150) String name,
			@Size(max = 32) String phoneNumber,
			@NotBlank @Size(max = 100) String jobTitle,
			@NotNull @DecimalMin(value = "0.00") BigDecimal baseSalary,
			@NotNull @DecimalMin(value = "0.00") BigDecimal commissionPercent,
			boolean active) {
	}

	public record EmployeePayment(
			@NotNull @DecimalMin(value = "0.01") BigDecimal amount,
			@NotBlank String paymentType,
			LocalDate periodStart,
			LocalDate periodEnd,
			@Size(max = 1000) String notes) {
	}

	public record BookingEmployeeRequest(UUID employeeId) {
	}

	public record EmployeePaymentResponse(
			UUID id,
			UUID employeeId,
			String employeeName,
			BigDecimal amount,
			String paymentType,
			LocalDate periodStart,
			LocalDate periodEnd,
			String notes,
			Instant paidAt) {
	}

	public record EmployeePaymentRecord(
			UUID id,
			UUID employeeId,
			String employeeName,
			BigDecimal amount,
			String paymentType,
			LocalDate periodStart,
			LocalDate periodEnd,
			String notes,
			Instant paidAt) {
	}

	public record Notification(String type, String message, long count) {
	}

	public record ShopProfile(String phoneNumber, String address, String openingHours, Instant updatedAt) {
	}

	public record AdminSettingsRequest(
			@Size(max = 32) String phoneNumber,
			@Size(max = 300) String address,
			@NotBlank String openingHours,
			@NotNull Boolean exactAppointmentEnabled,
			@NotNull Boolean confirmationRequestEnabled,
			@NotNull Boolean dayQueueEnabled,
			@NotNull Boolean sameDayQueueEnabled,
			@NotNull Boolean newBookingsDisabled) {
	}
}
