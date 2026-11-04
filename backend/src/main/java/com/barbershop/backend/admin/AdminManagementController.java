package com.barbershop.backend.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.barbershop.backend.admin.AdminDtos.CustomerDetails;
import com.barbershop.backend.admin.AdminDtos.CustomerPasswordRequest;
import com.barbershop.backend.admin.AdminDtos.CustomerSummary;
import com.barbershop.backend.admin.AdminDtos.CustomerUpdateRequest;
import com.barbershop.backend.admin.AdminDtos.ManualDebtRequest;
import com.barbershop.backend.admin.AdminDtos.ManualCustomerDebt;
import com.barbershop.backend.admin.AdminDtos.CheckoutRequest;
import com.barbershop.backend.admin.AdminDtos.DashboardResponse;
import com.barbershop.backend.admin.AdminDtos.AdminSettingsRequest;
import com.barbershop.backend.admin.AdminDtos.BestSellingProduct;
import com.barbershop.backend.admin.AdminDtos.BestSellingService;
import com.barbershop.backend.admin.AdminDtos.BookingSalesReport;
import com.barbershop.backend.admin.AdminDtos.Expense;
import com.barbershop.backend.admin.AdminDtos.ExpenseRequest;
import com.barbershop.backend.admin.AdminDtos.InventoryMovement;
import com.barbershop.backend.admin.AdminDtos.InventoryMovementResponse;
import com.barbershop.backend.admin.AdminDtos.Notification;
import com.barbershop.backend.admin.AdminDtos.PageResponse;
import com.barbershop.backend.admin.AdminDtos.Payment;
import com.barbershop.backend.admin.AdminDtos.PaymentRequest;
import com.barbershop.backend.admin.AdminDtos.Product;
import com.barbershop.backend.admin.AdminDtos.ProductImage;
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
			@RequestParam(defaultValue = "ALL") String type,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return service.customers(q, type, page, size);
	}

	@GetMapping("/customers/{id}")
	public CustomerDetails customer(@PathVariable UUID id, @RequestParam(required = false) String type) {
		return service.customer(id, type);
	}

	@PutMapping("/customers/{id}")
	public CustomerDetails updateCustomer(
			@PathVariable UUID id,
			@RequestParam(required = false) String type,
			@Valid @RequestBody CustomerUpdateRequest request) {
		return service.updateCustomer(id, type, request);
	}

	@PutMapping("/customers/{id}/accounts/{accountId}/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resetCustomerPassword(
			@PathVariable UUID id,
			@PathVariable UUID accountId,
			@RequestParam(required = false) String type,
			@Valid @RequestBody CustomerPasswordRequest request) {
		service.resetCustomerPassword(id, type, accountId, request);
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

	@PostMapping("/bookings/{bookingId}/checkout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void checkoutBooking(
			@PathVariable UUID bookingId,
			@Valid @RequestBody CheckoutRequest request,
			Authentication authentication) {
		service.checkoutBooking(bookingId, request, authentication.getName());
	}

	@GetMapping("/debts")
	public List<AdminManagementService.DebtResponse> debts(@RequestParam(required = false) String q) {
		return service.debts(q);
	}

	@PostMapping("/debts/{bookingId}/reminder")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void sendDebtReminder(@PathVariable UUID bookingId) {
		service.sendDebtReminderForBooking(bookingId);
	}

	@PostMapping("/bookings/{bookingId}/reminder")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void sendBookingReminder(@PathVariable UUID bookingId) {
		service.sendBookingReminder(bookingId);
	}

	@PostMapping("/debts/customers/{customerId}/reminder")
	public AdminManagementService.DebtReminderResult sendCustomerDebtReminder(
			@PathVariable UUID customerId,
			@RequestParam String type) {
		return service.sendDebtReminder(customerId, type);
	}

	@PostMapping("/debts/customers/{customerId}/payments")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void recordCustomerDebtPayment(
			@PathVariable UUID customerId,
			@RequestParam String type,
			@Valid @RequestBody PaymentRequest request) {
		service.recordCustomerPayment(customerId, type, request);
	}

	@PostMapping("/manual-debts")
	@ResponseStatus(HttpStatus.CREATED)
	public ManualCustomerDebt addManualDebt(
			@Valid @RequestBody ManualDebtRequest request,
			Authentication authentication) {
		return service.addManualCustomerDebt(request, authentication.getName());
	}

	@PostMapping("/debts/{bookingId}/payments")
	@ResponseStatus(HttpStatus.CREATED)
	public Payment settleDebt(
			@PathVariable UUID bookingId,
			@Valid @RequestBody PaymentRequest request) {
		return service.recordPayment(bookingId, request);
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

	@PutMapping("/expenses/{id}")
	public Expense updateExpense(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest request) {
		return service.updateExpense(id, request);
	}

	@DeleteMapping("/expenses/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteExpense(@PathVariable UUID id) {
		service.deleteExpense(id);
	}

	@GetMapping("/reports")
	public ReportResponse report(
			@RequestParam(required = false) LocalDate fromDate,
			@RequestParam(required = false) LocalDate toDate) {
		return service.report(fromDate, toDate);
	}

	@GetMapping("/reports/products")
	public List<BestSellingProduct> bestSellingProducts(
			@RequestParam(required = false) LocalDate fromDate,
			@RequestParam(required = false) LocalDate toDate) {
		return service.bestSellingProducts(fromDate, toDate);
	}

	@GetMapping("/reports/services")
	public List<BestSellingService> bestSellingServices(
			@RequestParam(required = false) LocalDate fromDate,
			@RequestParam(required = false) LocalDate toDate) {
		return service.bestSellingServices(fromDate, toDate);
	}

	@GetMapping("/reports/bookings")
	public List<BookingSalesReport> bookingSales(
			@RequestParam(required = false) LocalDate fromDate,
			@RequestParam(required = false) LocalDate toDate) {
		return service.bookingSales(fromDate, toDate);
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

	@PostMapping(value = "/inventory/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Product createProductWithImage(
			@Valid @RequestPart("product") ProductRequest request,
			@RequestPart(value = "image", required = false) MultipartFile image,
			Authentication authentication) {
		return service.createProduct(request, authentication.getName(), image);
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

	@PutMapping(value = "/inventory/products/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Product updateProductWithImage(
			@PathVariable UUID id,
			@Valid @RequestPart("product") ProductUpdateRequest request,
			@RequestPart(value = "image", required = false) MultipartFile image) {
		return service.updateProduct(id, request, image);
	}

	@DeleteMapping("/inventory/products/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteProduct(@PathVariable UUID id) {
		service.deleteProduct(id);
	}

	@GetMapping(value = "/inventory/products/{id}/image")
	public ResponseEntity<byte[]> productImage(@PathVariable UUID id) {
		ProductImage image = service.productImage(id);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(image.contentType()))
				.cacheControl(CacheControl.noStore())
				.header("X-Content-Type-Options", "nosniff")
				.body(image.data());
	}

	@GetMapping("/inventory/products/{id}/movements")
	public List<InventoryMovementResponse> inventoryMovements(@PathVariable UUID id) {
		return service.inventoryMovements(id);
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
