package com.barbershop.backend.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.admin.AdminDtos.CustomerDetails;
import com.barbershop.backend.admin.AdminDtos.CustomerPasswordRequest;
import com.barbershop.backend.admin.AdminDtos.CustomerSummary;
import com.barbershop.backend.admin.AdminDtos.CustomerUpdateRequest;
import com.barbershop.backend.admin.AdminDtos.BookingEmployeeRequest;
import com.barbershop.backend.admin.AdminDtos.DashboardResponse;
import com.barbershop.backend.admin.AdminDtos.AdminSettingsRequest;
import com.barbershop.backend.admin.AdminDtos.Employee;
import com.barbershop.backend.admin.AdminDtos.EmployeePayment;
import com.barbershop.backend.admin.AdminDtos.EmployeePaymentResponse;
import com.barbershop.backend.admin.AdminDtos.EmployeePaymentRecord;
import com.barbershop.backend.admin.AdminDtos.EmployeeRequest;
import com.barbershop.backend.admin.AdminDtos.Expense;
import com.barbershop.backend.admin.AdminDtos.ExpenseRequest;
import com.barbershop.backend.admin.AdminDtos.InventoryMovement;
import com.barbershop.backend.admin.AdminDtos.InventoryMovementResponse;
import com.barbershop.backend.admin.AdminDtos.Notification;
import com.barbershop.backend.admin.AdminDtos.PageResponse;
import com.barbershop.backend.admin.AdminDtos.Payment;
import com.barbershop.backend.admin.AdminDtos.PaymentRequest;
import com.barbershop.backend.admin.AdminDtos.Product;
import com.barbershop.backend.admin.AdminDtos.ProductRequest;
import com.barbershop.backend.admin.AdminDtos.ProductUpdateRequest;
import com.barbershop.backend.admin.AdminDtos.ReportResponse;
import com.barbershop.backend.admin.AdminDtos.ShopProfile;

@RestController
@RequestMapping("/api/admin")
public class AdminManagementController {

	private final AdminManagementService service;

	public AdminManagementController(AdminManagementService service) {
		this.service = service;
	}

	@GetMapping("/summary")
	public DashboardResponse summary() {
		return service.dashboard();
	}

	@GetMapping("/summary/trend")
	public List<AdminDtos.DashboardTrendPoint> summaryTrend() {
		return service.dashboardTrend();
	}

	@GetMapping("/customers")
	public PageResponse<CustomerSummary> customers(
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return service.customers(q, page, size);
	}

	@GetMapping("/customers/{id}")
	public CustomerDetails customer(@PathVariable UUID id) {
		return service.customer(id);
	}

	@PutMapping("/customers/{id}")
	public CustomerDetails updateCustomer(
			@PathVariable UUID id,
			@Valid @RequestBody CustomerUpdateRequest request) {
		return service.updateCustomer(id, request);
	}

	@PutMapping("/customers/{id}/accounts/{accountId}/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resetCustomerPassword(
			@PathVariable UUID id,
			@PathVariable UUID accountId,
			@Valid @RequestBody CustomerPasswordRequest request) {
		service.resetCustomerPassword(id, accountId, request);
	}

	@GetMapping("/payments")
	public List<Payment> payments(
			@RequestParam(required = false) LocalDate fromDate,
			@RequestParam(required = false) LocalDate toDate) {
		return service.payments(fromDate, toDate);
	}

	@PostMapping("/bookings/{bookingId}/payments")
	@ResponseStatus(HttpStatus.CREATED)
	public Payment recordBookingPayment(
			@PathVariable UUID bookingId,
			@Valid @RequestBody PaymentRequest request) {
		return service.recordPayment(bookingId, request);
	}

	@GetMapping("/debts")
	public List<AdminManagementService.DebtResponse> debts(@RequestParam(required = false) String q) {
		return service.debts(q);
	}

	@PostMapping("/debts/{bookingId}/payments")
	@ResponseStatus(HttpStatus.CREATED)
	public Payment settleDebt(
			@PathVariable UUID bookingId,
			@Valid @RequestBody PaymentRequest request) {
		return service.recordPayment(bookingId, request);
	}

	@PutMapping("/bookings/{bookingId}/employee")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void assignBookingEmployee(
			@PathVariable UUID bookingId,
			@RequestBody BookingEmployeeRequest request) {
		service.assignBookingEmployee(bookingId, request);
	}

	@GetMapping("/expenses")
	public List<Expense> expenses(
			@RequestParam(required = false) LocalDate fromDate,
			@RequestParam(required = false) LocalDate toDate) {
		return service.expenses(fromDate, toDate);
	}

	@PostMapping("/expenses")
	@ResponseStatus(HttpStatus.CREATED)
	public Expense createExpense(
			@Valid @RequestBody ExpenseRequest request,
			Authentication authentication) {
		return service.createExpense(request, authentication.getName());
	}

	@GetMapping("/reports")
	public ReportResponse report(
			@RequestParam(required = false) LocalDate fromDate,
			@RequestParam(required = false) LocalDate toDate) {
		return service.report(fromDate, toDate);
	}

	@GetMapping("/inventory/products")
	public List<Product> products() {
		return service.products();
	}

	@PostMapping("/inventory/products")
	@ResponseStatus(HttpStatus.CREATED)
	public Product createProduct(
			@Valid @RequestBody ProductRequest request,
			Authentication authentication) {
		return service.createProduct(request, authentication.getName());
	}

	@PostMapping("/inventory/products/{id}/movements")
	public Product recordInventoryMovement(
			@PathVariable UUID id,
			@Valid @RequestBody InventoryMovement request,
			Authentication authentication) {
		return service.recordMovement(id, request, authentication.getName());
	}

	@PutMapping("/inventory/products/{id}")
	public Product updateProduct(@PathVariable UUID id, @Valid @RequestBody ProductUpdateRequest request) {
		return service.updateProduct(id, request);
	}

	@GetMapping("/inventory/products/{id}/movements")
	public List<InventoryMovementResponse> inventoryMovements(@PathVariable UUID id) {
		return service.inventoryMovements(id);
	}

	@GetMapping("/employees")
	public List<Employee> employees() {
		return service.employees();
	}

	@PostMapping("/employees")
	@ResponseStatus(HttpStatus.CREATED)
	public Employee createEmployee(@Valid @RequestBody EmployeeRequest request) {
		return service.saveEmployee(null, request);
	}

	@PutMapping("/employees/{id}")
	public Employee updateEmployee(@PathVariable UUID id, @Valid @RequestBody EmployeeRequest request) {
		return service.saveEmployee(id, request);
	}

	@PostMapping("/employees/{id}/payments")
	@ResponseStatus(HttpStatus.CREATED)
	public EmployeePaymentResponse employeePayment(
			@PathVariable UUID id,
			@Valid @RequestBody EmployeePayment request,
			Authentication authentication) {
		return service.recordEmployeePayment(id, request, authentication.getName());
	}

	@GetMapping("/employee-payments")
	public List<EmployeePaymentRecord> employeePayments() {
		return service.employeePayments();
	}

	@GetMapping("/notifications")
	public List<Notification> notifications() {
		return service.notifications();
	}

	@GetMapping("/settings")
	public ShopProfile shopProfile() {
		return service.shopProfile();
	}

	@PutMapping("/settings")
	public ShopProfile updateShopProfile(
			@Valid @RequestBody AdminSettingsRequest request,
			Authentication authentication) {
		return service.updateShopSettings(request, authentication.getName());
	}
}
