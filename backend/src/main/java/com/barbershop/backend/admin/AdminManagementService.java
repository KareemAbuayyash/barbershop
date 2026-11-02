package com.barbershop.backend.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.barbershop.backend.admin.AdminDtos.CustomerBooking;
import com.barbershop.backend.admin.AdminDtos.CustomerAccountSummary;
import com.barbershop.backend.admin.AdminDtos.CustomerPasswordRequest;
import com.barbershop.backend.admin.AdminDtos.CustomerDetails;
import com.barbershop.backend.admin.AdminDtos.CustomerSummary;
import com.barbershop.backend.admin.AdminDtos.CustomerUpdateRequest;
import com.barbershop.backend.admin.AdminDtos.BookingEmployeeRequest;
import com.barbershop.backend.admin.AdminDtos.DashboardResponse;
import com.barbershop.backend.admin.AdminDtos.DashboardTrendPoint;
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
import com.barbershop.backend.booking.ShopScheduleService;

@Service
public class AdminManagementService {

	private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Hebron");
	private static final List<String> PAYMENT_METHODS = List.of("CASH", "CARD", "TRANSFER", "OTHER");
	private static final List<String> MOVEMENT_TYPES = List.of("PURCHASE", "CONSUMPTION", "SALE", "ADJUSTMENT");
	private static final List<String> EMPLOYEE_PAYMENT_TYPES = List.of("SALARY", "COMMISSION", "ADVANCE", "OTHER");
	private static final String CUSTOMER_GROUPS = """
			WITH customer_keys AS (
			    SELECT c.id, c.name, c.phone_number,
			           CASE WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
			                THEN 'phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
			                ELSE 'id:' || c.id::text END AS group_key
			    FROM customers c
			),
			customer_groups AS (
			    SELECT group_key, (array_agg(id ORDER BY id))[1] AS id,
			           max(name) AS name, max(phone_number) AS phone_number
			    FROM customer_keys GROUP BY group_key
			),
			booking_totals AS (
			    SELECT c.group_key, count(DISTINCT CASE WHEN b.status = 'COMPLETED' THEN b.id END) AS visits,
			           coalesce(sum(CASE WHEN b.status = 'COMPLETED' THEN b.service_price_snapshot ELSE 0 END), 0) AS service_value
			    FROM customer_keys c JOIN bookings b ON b.customer_id = c.id
			    GROUP BY c.group_key
			),
			payment_totals AS (
			    SELECT c.group_key, coalesce(sum(p.amount), 0) AS total_paid
			    FROM customer_keys c JOIN booking_payments p ON p.customer_id = c.id
			    GROUP BY c.group_key
			),
			debt_totals AS (
			    SELECT c.group_key,
			           coalesce(sum(GREATEST(b.service_price_snapshot - coalesce(bp.paid, 0), 0)), 0) AS debt
			    FROM customer_keys c
			    JOIN bookings b ON b.customer_id = c.id AND b.status = 'COMPLETED'
			    LEFT JOIN (SELECT booking_id, sum(amount) AS paid FROM booking_payments GROUP BY booking_id) bp
			      ON bp.booking_id = b.id
			    GROUP BY c.group_key
			)
			""";

	private static final RowMapper<CustomerSummary> CUSTOMER_ROW = (rs, row) -> new CustomerSummary(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("phone_number"),
			rs.getLong("visits"),
			rs.getBigDecimal("total_paid"),
			rs.getBigDecimal("debt"));
	private static final RowMapper<Payment> PAYMENT_ROW = (rs, row) -> new Payment(
			rs.getObject("id", UUID.class),
			rs.getObject("booking_id", UUID.class),
			rs.getString("customer_name"),
			rs.getBigDecimal("amount"),
			rs.getString("method"),
			rs.getString("notes"),
			rs.getTimestamp("paid_at").toInstant());

	private final JdbcTemplate jdbc;
	private final PasswordEncoder passwordEncoder;
	private final ShopScheduleService shopScheduleService;

	public AdminManagementService(
			JdbcTemplate jdbc,
			PasswordEncoder passwordEncoder,
			ShopScheduleService shopScheduleService) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.shopScheduleService = shopScheduleService;
	}

	@Transactional(readOnly = true)
	public DashboardResponse dashboard() {
		LocalDate today = LocalDate.now(SHOP_ZONE);
		long pending = scalarLong("SELECT count(*) FROM bookings WHERE status = 'PENDING'");
		long todayBookings = jdbc.queryForObject("""
				SELECT count(*) FROM bookings
				WHERE queue_date = ? OR (queue_date IS NULL AND starts_at >= ? AND starts_at < ?)
				""", Long.class, today, start(today), start(today.plusDays(1)));
		long customers = scalarLong("SELECT count(DISTINCT coalesce(nullif(regexp_replace(phone_number, '[^0-9]', '', 'g'), ''), id::text)) FROM customers");
		BigDecimal collected = scalarMoney("SELECT coalesce(sum(amount), 0) FROM booking_payments WHERE paid_at >= ? AND paid_at < ?",
				start(today), start(today.plusDays(1)));
		BigDecimal debt = scalarMoney("""
				SELECT coalesce(sum(GREATEST(b.service_price_snapshot - coalesce(p.paid, 0), 0)), 0)
				FROM bookings b LEFT JOIN (SELECT booking_id, sum(amount) paid FROM booking_payments GROUP BY booking_id) p
				  ON p.booking_id = b.id
				WHERE b.status = 'COMPLETED'
				""");
		long lowStock = scalarLong("SELECT count(*) FROM inventory_products WHERE active AND quantity <= low_stock_threshold");
		return new DashboardResponse(pending, todayBookings, customers, collected, debt, lowStock);
	}

	@Transactional(readOnly = true)
	public List<DashboardTrendPoint> dashboardTrend() {
		LocalDate to = LocalDate.now(SHOP_ZONE);
		LocalDate from = to.minusDays(6);
		Map<LocalDate, Long> bookings = new java.util.HashMap<>();
		jdbc.query("""
				SELECT booking_date, count(*) AS total
				FROM (
				    SELECT CASE WHEN queue_date IS NOT NULL THEN queue_date
				                ELSE (starts_at AT TIME ZONE 'Asia/Hebron')::date END AS booking_date
				    FROM bookings
				    WHERE (queue_date BETWEEN ? AND ?)
				       OR (starts_at >= ? AND starts_at < ?)
				) daily_bookings
				GROUP BY booking_date
				""", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
						bookings.put(rs.getDate("booking_date").toLocalDate(), rs.getLong("total")),
				from, to, start(from), start(to.plusDays(1)));
		Map<LocalDate, BigDecimal> collected = new java.util.HashMap<>();
		jdbc.query("""
				SELECT (paid_at AT TIME ZONE 'Asia/Hebron')::date AS payment_date, sum(amount) AS total
				FROM booking_payments
				WHERE paid_at >= ? AND paid_at < ?
				GROUP BY payment_date
				""", (org.springframework.jdbc.core.RowCallbackHandler) rs -> collected.put(
						rs.getDate("payment_date").toLocalDate(), rs.getBigDecimal("total")),
				start(from), start(to.plusDays(1)));
		return from.datesUntil(to.plusDays(1))
				.map(date -> new DashboardTrendPoint(
						date, bookings.getOrDefault(date, 0L), collected.getOrDefault(date, BigDecimal.ZERO)))
				.toList();
	}

	@Transactional(readOnly = true)
	public PageResponse<CustomerSummary> customers(String query, int page, int size) {
		validatePage(page, size);
		String pattern = "%" + escapeLike(query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";
		String base = CUSTOMER_GROUPS + """
				SELECT g.id, g.name, g.phone_number,
				       coalesce(bt.visits, 0) visits,
				       coalesce(pt.total_paid, 0) total_paid,
				       coalesce(dt.debt, 0) debt
				FROM customer_groups g
				LEFT JOIN booking_totals bt ON bt.group_key = g.group_key
				LEFT JOIN payment_totals pt ON pt.group_key = g.group_key
				LEFT JOIN debt_totals dt ON dt.group_key = g.group_key
				WHERE (? = '%%' OR lower(g.name) LIKE ? OR coalesce(g.phone_number, '') LIKE ?)
				""";
		long total = jdbc.queryForObject(CUSTOMER_GROUPS + """
				SELECT count(*) FROM customer_groups
				WHERE (? = '%%' OR lower(name) LIKE ? OR coalesce(phone_number, '') LIKE ?)
				""", Long.class, pattern, pattern, pattern);
		List<CustomerSummary> content = jdbc.query(
				base + " ORDER BY g.name, g.id LIMIT ? OFFSET ?",
				CUSTOMER_ROW,
				pattern, pattern, pattern, size, page * size);
		return page(content, page, size, total);
	}

	@Transactional(readOnly = true)
	public CustomerDetails customer(UUID id) {
		String groupKey = queryString("""
				SELECT CASE WHEN regexp_replace(coalesce(phone_number, ''), '[^0-9]', '', 'g') <> ''
				            THEN 'phone:' || regexp_replace(phone_number, '[^0-9]', '', 'g')
				            ELSE 'id:' || id::text END
				FROM customers WHERE id = ?
				""", id);
		CustomerSummary summary = jdbc.queryForObject(CUSTOMER_GROUPS + """
				SELECT g.id, g.name, g.phone_number, coalesce(bt.visits, 0) visits,
				       coalesce(pt.total_paid, 0) total_paid, coalesce(dt.debt, 0) debt
				FROM customer_groups g
				LEFT JOIN booking_totals bt ON bt.group_key = g.group_key
				LEFT JOIN payment_totals pt ON pt.group_key = g.group_key
				LEFT JOIN debt_totals dt ON dt.group_key = g.group_key
				WHERE g.group_key = ?
				""", CUSTOMER_ROW, groupKey);
		List<CustomerBooking> bookings = jdbc.query("""
				SELECT b.id, b.service_name_snapshot, b.service_price_snapshot, b.status, b.created_at
				FROM bookings b JOIN customers c ON c.id = b.customer_id
				WHERE CASE WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				           ELSE 'id:' || c.id::text END = ?
				ORDER BY b.created_at DESC
				""", (rs, row) -> new CustomerBooking(
						rs.getObject("id", UUID.class),
						rs.getString("service_name_snapshot"),
						rs.getBigDecimal("service_price_snapshot"),
						rs.getString("status"),
						rs.getTimestamp("created_at").toInstant()), groupKey);
		List<Payment> payments = paymentsForGroup(groupKey);
		List<CustomerAccountSummary> accounts = jdbc.query("""
				SELECT DISTINCT a.id, a.username_normalized, a.display_name, a.phone_number
				FROM customer_accounts a
				JOIN bookings b ON b.customer_account_id = a.id
				JOIN customers c ON c.id = b.customer_id
				WHERE CASE WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				           ELSE 'id:' || c.id::text END = ?
				ORDER BY a.username_normalized
				""", (rs, row) -> new CustomerAccountSummary(
						rs.getObject("id", UUID.class), rs.getString("username_normalized"),
						rs.getString("display_name"), rs.getString("phone_number")), groupKey);
		return new CustomerDetails(summary, bookings, payments, accounts);
	}

	@Transactional
	public CustomerDetails updateCustomer(UUID id, CustomerUpdateRequest request) {
		String groupKey = customerGroupKey(id);
		String name = request.name().trim();
		if (name.length() < 2) throw badRequest("Customer name must contain at least 2 characters.");
		String phone = trimToNull(request.phoneNumber());
		jdbc.update("""
				UPDATE customer_accounts SET display_name = ?, phone_number = ?, updated_at = CURRENT_TIMESTAMP
				WHERE id IN (
				    SELECT DISTINCT b.customer_account_id
				    FROM bookings b JOIN customers c ON c.id = b.customer_id
				    WHERE b.customer_account_id IS NOT NULL
				      AND CASE WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				               THEN 'phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				               ELSE 'id:' || c.id::text END = ?
				)
				""", name, phone == null ? "" : phone, groupKey);
		jdbc.update("""
				UPDATE customers SET name = ?, phone_number = ?
				WHERE CASE WHEN regexp_replace(coalesce(phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'phone:' || regexp_replace(phone_number, '[^0-9]', '', 'g')
				           ELSE 'id:' || id::text END = ?
				""", name, phone, groupKey);
		return customer(id);
	}

	@Transactional
	public void resetCustomerPassword(UUID customerId, UUID accountId, CustomerPasswordRequest request) {
		String groupKey = customerGroupKey(customerId);
		int bytes = request.newPassword().getBytes(StandardCharsets.UTF_8).length;
		if (bytes < 8 || bytes > 72) {
			throw badRequest("Password must contain 8-72 UTF-8 bytes.");
		}
		int updated = jdbc.update("""
				UPDATE customer_accounts
				SET password_hash = ?, updated_at = CURRENT_TIMESTAMP
				WHERE id = ? AND id IN (
				    SELECT DISTINCT b.customer_account_id
				    FROM bookings b JOIN customers c ON c.id = b.customer_id
				    WHERE CASE WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				               THEN 'phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				               ELSE 'id:' || c.id::text END = ?
				)
				""", passwordEncoder.encode(request.newPassword()), accountId, groupKey);
		if (updated == 0) throw notFound("Customer account not found.");
	}

	@Transactional(readOnly = true)
	public List<Payment> payments(LocalDate fromDate, LocalDate toDate) {
		validateDateRange(fromDate, toDate);
		return jdbc.query("""
				SELECT p.id, p.booking_id, c.name customer_name, p.amount, p.method, p.notes, p.paid_at
				FROM booking_payments p JOIN customers c ON c.id = p.customer_id
				WHERE (? IS NULL OR p.paid_at >= ?)
				  AND (? IS NULL OR p.paid_at < ?)
				ORDER BY p.paid_at DESC LIMIT 1000
				""", PAYMENT_ROW,
				fromDate, fromDate == null ? null : start(fromDate),
				toDate, toDate == null ? null : start(toDate.plusDays(1)));
	}

	@Transactional
	public Payment recordPayment(UUID bookingId, PaymentRequest request) {
		requireChoice(request.method(), PAYMENT_METHODS, "Unsupported payment method.");
		try {
			return jdbc.queryForObject("""
					SELECT b.id AS booking_id, b.status, c.id AS customer_id, c.name AS customer_name,
					       b.service_price_snapshot AS service_value, b.employee_id,
					       coalesce((SELECT sum(amount) FROM booking_payments WHERE booking_id = b.id), 0) AS paid
					FROM bookings b JOIN customers c ON c.id = b.customer_id
					WHERE b.id = ? FOR UPDATE OF b
					""", (rs, row) -> {
				if (!"COMPLETED".equals(rs.getString("status"))) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed bookings can be paid.");
				}
				BigDecimal remaining = rs.getBigDecimal("service_value").subtract(rs.getBigDecimal("paid"));
				if (request.amount().compareTo(remaining) > 0) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment exceeds the outstanding booking balance.");
				}
				UUID customerId = rs.getObject("customer_id", UUID.class);
				UUID employeeId = rs.getObject("employee_id", UUID.class);
				UUID paymentId = UUID.randomUUID();
				jdbc.update("""
						INSERT INTO booking_payments (id, booking_id, customer_id, amount, method, notes)
						VALUES (?, ?, ?, ?, ?, ?)
						""", paymentId, bookingId, customerId, request.amount(), request.method(),
						trimToNull(request.notes()));
				if (employeeId != null) {
					BigDecimal commissionPercent = scalarMoney(
							"SELECT coalesce((SELECT commission_percent FROM shop_employees WHERE id = ? AND active = TRUE), 0)",
							employeeId);
					if (commissionPercent.signum() > 0) {
						BigDecimal commission = request.amount().multiply(commissionPercent)
								.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
						jdbc.update("""
								INSERT INTO employee_commissions (employee_id, payment_id, amount)
								VALUES (?, ?, ?)
								""", employeeId, paymentId, commission);
					}
				}
				return new Payment(paymentId, bookingId, rs.getString("customer_name"), request.amount(),
						request.method(), trimToNull(request.notes()), Instant.now());
			}, bookingId);
		} catch (EmptyResultDataAccessException exception) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found.");
		}
	}

	@Transactional
	public void assignBookingEmployee(UUID bookingId, BookingEmployeeRequest request) {
		try {
			String status = jdbc.queryForObject(
					"SELECT status FROM bookings WHERE id = ? FOR UPDATE", String.class, bookingId);
			if ("CANCELLED".equals(status)) throw new ResponseStatusException(
					HttpStatus.CONFLICT, "Cancelled bookings cannot be assigned.");
			boolean alreadyPaid = scalarLong(
					"SELECT count(*) FROM booking_payments WHERE booking_id = ?", bookingId) > 0;
			if (alreadyPaid) throw new ResponseStatusException(
					HttpStatus.CONFLICT, "Employee assignment cannot change after a payment is recorded.");
			if (request.employeeId() != null) {
				int active = jdbc.queryForObject(
						"SELECT count(*) FROM shop_employees WHERE id = ? AND active = TRUE",
						Integer.class, request.employeeId());
				if (active == 0) throw notFound("Active employee not found.");
			}
			jdbc.update("UPDATE bookings SET employee_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
					request.employeeId(), bookingId);
		} catch (EmptyResultDataAccessException exception) {
			throw notFound("Booking not found.");
		}
	}

	@Transactional(readOnly = true)
	public List<DebtResponse> debts(String query) {
		String pattern = "%" + escapeLike(query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";
		return jdbc.query("""
				SELECT b.id booking_id, c.id customer_id, c.name customer_name, c.phone_number,
				       b.service_name_snapshot, b.service_price_snapshot,
				       b.service_price_snapshot - coalesce(p.paid, 0) balance
				FROM bookings b JOIN customers c ON c.id = b.customer_id
				LEFT JOIN (SELECT booking_id, sum(amount) paid FROM booking_payments GROUP BY booking_id) p
				  ON p.booking_id = b.id
				WHERE b.status = 'COMPLETED'
				  AND b.service_price_snapshot > coalesce(p.paid, 0)
				  AND (lower(c.name) LIKE ? OR coalesce(c.phone_number, '') LIKE ?)
				ORDER BY b.created_at, b.id LIMIT 1000
				""", (rs, row) -> new DebtResponse(
						rs.getObject("booking_id", UUID.class),
						rs.getObject("customer_id", UUID.class),
						rs.getString("customer_name"),
						rs.getString("phone_number"),
						rs.getString("service_name_snapshot"),
						rs.getBigDecimal("service_price_snapshot"),
						rs.getBigDecimal("balance")), pattern, pattern);
	}

	@Transactional(readOnly = true)
	public List<Expense> expenses(LocalDate fromDate, LocalDate toDate) {
		validateDateRange(fromDate, toDate);
		return jdbc.query("""
				SELECT id, category, description, amount, expense_date, method, notes, created_at
				FROM shop_expenses
				WHERE (? IS NULL OR expense_date >= ?)
				  AND (? IS NULL OR expense_date <= ?)
				ORDER BY expense_date DESC, created_at DESC LIMIT 1000
				""", (rs, row) -> new Expense(
						rs.getObject("id", UUID.class),
						rs.getString("category"),
						rs.getString("description"),
						rs.getBigDecimal("amount"),
						rs.getObject("expense_date", LocalDate.class),
						rs.getString("method"),
						rs.getString("notes"),
						rs.getTimestamp("created_at").toInstant()),
				fromDate, fromDate, toDate, toDate);
	}

	@Transactional
	public Expense createExpense(ExpenseRequest request, String actor) {
		requireChoice(request.method(), PAYMENT_METHODS, "Unsupported payment method.");
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO shop_expenses (id, category, description, amount, expense_date, method, notes, created_by)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", id, request.category().trim(), request.description().trim(), request.amount(),
				request.expenseDate(), request.method(), trimToNull(request.notes()), actor);
		return jdbc.queryForObject("""
				SELECT id, category, description, amount, expense_date, method, notes, created_at
				FROM shop_expenses WHERE id = ?
				""", (rs, row) -> new Expense(id, rs.getString("category"), rs.getString("description"),
				rs.getBigDecimal("amount"), rs.getObject("expense_date", LocalDate.class),
				rs.getString("method"), rs.getString("notes"), rs.getTimestamp("created_at").toInstant()), id);
	}

	@Transactional(readOnly = true)
	public ReportResponse report(LocalDate fromDate, LocalDate toDate) {
		if (fromDate == null || toDate == null) {
			LocalDate today = LocalDate.now(SHOP_ZONE);
			fromDate = fromDate == null ? today.withDayOfMonth(1) : fromDate;
			toDate = toDate == null ? today : toDate;
		}
		validateDateRange(fromDate, toDate);
		BigDecimal revenue = scalarMoney("""
				SELECT coalesce(sum(amount), 0) FROM booking_payments
				WHERE paid_at >= ? AND paid_at < ?
				""", start(fromDate), start(toDate.plusDays(1)));
		BigDecimal expenses = scalarMoney("""
				SELECT coalesce(sum(amount), 0) FROM shop_expenses
				WHERE expense_date >= ? AND expense_date <= ?
				""", fromDate, toDate);
		BigDecimal debt = scalarMoney("""
				SELECT coalesce(sum(GREATEST(b.service_price_snapshot - coalesce(p.paid, 0), 0)), 0)
				FROM bookings b LEFT JOIN (SELECT booking_id, sum(amount) paid FROM booking_payments GROUP BY booking_id) p
				  ON p.booking_id = b.id
				WHERE b.status = 'COMPLETED'
				""");
		return new ReportResponse(fromDate, toDate, revenue, expenses, revenue.subtract(expenses), debt);
	}

	@Transactional(readOnly = true)
	public List<Product> products() {
		return jdbc.query("""
				SELECT id, name, unit, quantity, low_stock_threshold, active
				FROM inventory_products ORDER BY active DESC, name
				""", (rs, row) -> new Product(rs.getObject("id", UUID.class), rs.getString("name"),
				rs.getString("unit"), rs.getBigDecimal("quantity"), rs.getBigDecimal("low_stock_threshold"),
				rs.getBoolean("active")));
	}

	@Transactional
	public Product createProduct(ProductRequest request, String actor) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO inventory_products (id, name, unit, quantity, low_stock_threshold)
				VALUES (?, ?, ?, ?, ?)
				""", id, request.name().trim(), request.unit().trim(), request.initialQuantity(),
				request.lowStockThreshold());
		if (request.initialQuantity().signum() > 0) {
			insertMovement(id, "PURCHASE", request.initialQuantity(), null, "Initial stock", actor);
		}
		return findProduct(id);
	}

	@Transactional
	public Product recordMovement(UUID productId, InventoryMovement movement, String actor) {
		requireChoice(movement.movementType(), MOVEMENT_TYPES, "Unsupported inventory movement.");
		jdbc.queryForObject("SELECT id FROM inventory_products WHERE id = ? FOR UPDATE", UUID.class, productId);
		BigDecimal delta = switch (movement.movementType()) {
			case "PURCHASE", "ADJUSTMENT" -> movement.quantity();
			case "CONSUMPTION", "SALE" -> movement.quantity().negate();
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported inventory movement.");
		};
		int updated = jdbc.update("""
				UPDATE inventory_products
				SET quantity = quantity + ?, updated_at = CURRENT_TIMESTAMP
				WHERE id = ? AND quantity + ? >= 0
				""", delta, productId, delta);
		if (updated != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Movement would make inventory stock negative.");
		}
		insertMovement(productId, movement.movementType(), movement.quantity(), movement.unitCost(),
				trimToNull(movement.notes()), actor);
		return findProduct(productId);
	}

	@Transactional
	public Product updateProduct(UUID id, ProductUpdateRequest request) {
		int updated = jdbc.update("""
				UPDATE inventory_products SET name = ?, unit = ?, low_stock_threshold = ?,
				       active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
				""", request.name().trim(), request.unit().trim(), request.lowStockThreshold(),
				request.active(), id);
		if (updated == 0) throw notFound("Inventory product not found.");
		return findProduct(id);
	}

	@Transactional(readOnly = true)
	public List<InventoryMovementResponse> inventoryMovements(UUID productId) {
		return jdbc.query("""
				SELECT id, product_id, movement_type, quantity, unit_cost, notes, created_at
				FROM inventory_movements WHERE product_id = ? ORDER BY created_at DESC LIMIT 200
				""", (rs, row) -> new InventoryMovementResponse(
						rs.getObject("id", UUID.class),
						rs.getObject("product_id", UUID.class),
						rs.getString("movement_type"),
						rs.getBigDecimal("quantity"),
						rs.getBigDecimal("unit_cost"),
						rs.getString("notes"),
						rs.getTimestamp("created_at").toInstant()), productId);
	}

	@Transactional(readOnly = true)
	public List<Notification> notifications() {
		long pending = scalarLong("SELECT count(*) FROM bookings WHERE status = 'PENDING'");
		long lowStock = scalarLong("SELECT count(*) FROM inventory_products WHERE active AND quantity <= low_stock_threshold");
		List<Notification> results = new java.util.ArrayList<>();
		if (pending > 0) results.add(new Notification("PENDING_BOOKINGS", "توجد حجوزات بانتظار التأكيد.", pending));
		if (lowStock > 0) results.add(new Notification("LOW_STOCK", "توجد منتجات وصلت إلى حد المخزون المنخفض.", lowStock));
		return results;
	}

	@Transactional(readOnly = true)
	public ShopProfile shopProfile() {
		return jdbc.queryForObject("""
				SELECT phone_number, address, opening_hours::text opening_hours, updated_at
				FROM shop_profile WHERE singleton_key = TRUE
				""", (rs, row) -> new ShopProfile(rs.getString("phone_number"), rs.getString("address"),
				rs.getString("opening_hours"), rs.getTimestamp("updated_at").toInstant()));
	}

	@Transactional
	public ShopProfile updateShopSettings(AdminSettingsRequest request, String actor) {
		shopScheduleService.validateSchedule(request.openingHours());
		jdbc.update("""
				UPDATE shop_profile SET phone_number = ?, address = ?,
				       opening_hours = ?::jsonb, updated_at = CURRENT_TIMESTAMP, updated_by = ?
				WHERE singleton_key = TRUE
				""", trimToNull(request.phoneNumber()), trimToNull(request.address()), request.openingHours(), actor);
		int updatedSettings = jdbc.update("""
				UPDATE booking_settings SET exact_appointment_enabled = ?,
				       confirmation_request_enabled = ?, day_queue_enabled = ?,
				       same_day_queue_enabled = ?, new_bookings_disabled = ?,
				       updated_at = CURRENT_TIMESTAMP
				WHERE singleton_key = TRUE
				""", request.exactAppointmentEnabled(), request.confirmationRequestEnabled(),
				request.dayQueueEnabled(), request.sameDayQueueEnabled(), request.newBookingsDisabled());
		if (updatedSettings == 0) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Booking settings are not initialized.");
		}
		return shopProfile();
	}

	@Transactional(readOnly = true)
	public List<Employee> employees() {
		return jdbc.query("""
				SELECT e.id, e.name, e.phone_number, e.job_title, e.base_salary, e.commission_percent,
				       coalesce(c.earned, 0) commission_earned, coalesce(p.paid, 0) commission_paid, e.active
				FROM shop_employees e
				LEFT JOIN (SELECT employee_id, sum(amount) earned FROM employee_commissions GROUP BY employee_id) c
				  ON c.employee_id = e.id
				LEFT JOIN (SELECT employee_id, sum(amount) paid FROM employee_payments
				           WHERE payment_type = 'COMMISSION' GROUP BY employee_id) p ON p.employee_id = e.id
				ORDER BY e.active DESC, e.name
				""", (rs, row) -> new Employee(rs.getObject("id", UUID.class), rs.getString("name"),
				rs.getString("phone_number"), rs.getString("job_title"), rs.getBigDecimal("base_salary"),
				rs.getBigDecimal("commission_percent"), rs.getBigDecimal("commission_earned"),
				rs.getBigDecimal("commission_paid"), rs.getBoolean("active")));
	}

	@Transactional
	public Employee saveEmployee(UUID id, EmployeeRequest request) {
		UUID employeeId = id == null ? UUID.randomUUID() : id;
		if (id == null) {
			jdbc.update("""
					INSERT INTO shop_employees (id, name, phone_number, job_title, base_salary, commission_percent, active)
					VALUES (?, ?, ?, ?, ?, ?, ?)
					""", employeeId, request.name().trim(), trimToNull(request.phoneNumber()),
					request.jobTitle().trim(), request.baseSalary(), request.commissionPercent(), request.active());
		} else {
			int updated = jdbc.update("""
					UPDATE shop_employees SET name = ?, phone_number = ?, job_title = ?, base_salary = ?,
					       commission_percent = ?, active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
					""", request.name().trim(), trimToNull(request.phoneNumber()), request.jobTitle().trim(),
					request.baseSalary(), request.commissionPercent(), request.active(), employeeId);
			if (updated == 0) throw notFound("Employee not found.");
		}
		return jdbc.queryForObject("""
				SELECT e.id, e.name, e.phone_number, e.job_title, e.base_salary, e.commission_percent,
				       coalesce(c.earned, 0) commission_earned, coalesce(p.paid, 0) commission_paid, e.active
				FROM shop_employees e
				LEFT JOIN (SELECT employee_id, sum(amount) earned FROM employee_commissions GROUP BY employee_id) c
				  ON c.employee_id = e.id
				LEFT JOIN (SELECT employee_id, sum(amount) paid FROM employee_payments
				           WHERE payment_type = 'COMMISSION' GROUP BY employee_id) p ON p.employee_id = e.id
				WHERE e.id = ?
				""", (rs, row) -> new Employee(employeeId, rs.getString("name"), rs.getString("phone_number"),
				rs.getString("job_title"), rs.getBigDecimal("base_salary"), rs.getBigDecimal("commission_percent"),
				rs.getBigDecimal("commission_earned"), rs.getBigDecimal("commission_paid"), rs.getBoolean("active")), employeeId);
	}

	@Transactional(readOnly = true)
	public List<EmployeePaymentRecord> employeePayments() {
		return jdbc.query("""
				SELECT p.id, p.employee_id, e.name employee_name, p.amount, p.payment_type,
				       p.period_start, p.period_end, p.notes, p.paid_at
				FROM employee_payments p JOIN shop_employees e ON e.id = p.employee_id
				ORDER BY p.paid_at DESC LIMIT 500
				""", (rs, row) -> new EmployeePaymentRecord(
						rs.getObject("id", UUID.class),
						rs.getObject("employee_id", UUID.class),
						rs.getString("employee_name"),
						rs.getBigDecimal("amount"),
						rs.getString("payment_type"),
						rs.getObject("period_start", LocalDate.class),
						rs.getObject("period_end", LocalDate.class),
						rs.getString("notes"),
						rs.getTimestamp("paid_at").toInstant()));
	}

	@Transactional
	public EmployeePaymentResponse recordEmployeePayment(UUID employeeId, EmployeePayment request, String actor) {
		requireChoice(request.paymentType(), EMPLOYEE_PAYMENT_TYPES, "Unsupported employee payment type.");
		if (request.periodStart() != null && request.periodEnd() != null
				&& request.periodStart().isAfter(request.periodEnd())) {
			throw badRequest("Invalid payroll period.");
		}
		String employeeName;
		BigDecimal commissionBalance = BigDecimal.ZERO;
		try {
			employeeName = jdbc.queryForObject("SELECT name FROM shop_employees WHERE id = ? FOR UPDATE",
					String.class, employeeId);
		} catch (EmptyResultDataAccessException exception) {
			throw notFound("Employee not found.");
		}
		if ("COMMISSION".equals(request.paymentType())) {
			commissionBalance = scalarMoney("""
					SELECT coalesce((SELECT sum(amount) FROM employee_commissions WHERE employee_id = ?), 0)
					       - coalesce((SELECT sum(amount) FROM employee_payments WHERE employee_id = ? AND payment_type = 'COMMISSION'), 0)
					""", employeeId, employeeId);
			if (request.amount().compareTo(commissionBalance) > 0) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment exceeds accrued commission.");
			}
		}
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO employee_payments (id, employee_id, amount, payment_type, period_start, period_end, notes, created_by)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", id, employeeId, request.amount(), request.paymentType(), request.periodStart(),
				request.periodEnd(), trimToNull(request.notes()), actor);
		return jdbc.queryForObject("""
				SELECT paid_at FROM employee_payments WHERE id = ?
				""", (rs, row) -> new EmployeePaymentResponse(id, employeeId, employeeName, request.amount(),
				request.paymentType(), request.periodStart(), request.periodEnd(),
				trimToNull(request.notes()), rs.getTimestamp("paid_at").toInstant()), id);
	}

	public record DebtResponse(
			UUID bookingId,
			UUID customerId,
			String customerName,
			String phoneNumber,
			String serviceName,
			BigDecimal servicePrice,
			BigDecimal balance) {
	}

	private List<Payment> paymentsForGroup(String groupKey) {
		return jdbc.query("""
				SELECT p.id, p.booking_id, c.name customer_name, p.amount, p.method, p.notes, p.paid_at
				FROM booking_payments p JOIN customers c ON c.id = p.customer_id
				WHERE CASE WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				           ELSE 'id:' || c.id::text END = ?
				ORDER BY p.paid_at DESC
				""", PAYMENT_ROW, groupKey);
	}

	private Product findProduct(UUID id) {
		return jdbc.queryForObject("""
				SELECT id, name, unit, quantity, low_stock_threshold, active
				FROM inventory_products WHERE id = ?
				""", (rs, row) -> new Product(id, rs.getString("name"), rs.getString("unit"),
				rs.getBigDecimal("quantity"), rs.getBigDecimal("low_stock_threshold"), rs.getBoolean("active")), id);
	}

	private void insertMovement(
			UUID productId,
			String type,
			BigDecimal quantity,
			BigDecimal unitCost,
			String notes,
			String actor) {
		jdbc.update("""
				INSERT INTO inventory_movements (product_id, movement_type, quantity, unit_cost, notes, created_by)
				VALUES (?, ?, ?, ?, ?, ?)
				""", productId, type, quantity, unitCost, notes, actor);
	}

	private long scalarLong(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private BigDecimal scalarMoney(String sql, Object... args) {
		BigDecimal result = jdbc.queryForObject(sql, BigDecimal.class, args);
		return result == null ? BigDecimal.ZERO : result;
	}

	private String queryString(String sql, Object... args) {
		try {
			return jdbc.queryForObject(sql, String.class, args);
		} catch (EmptyResultDataAccessException exception) {
			throw notFound("Customer not found.");
		}
	}

	private String customerGroupKey(UUID id) {
		return queryString("""
				SELECT CASE WHEN regexp_replace(coalesce(phone_number, ''), '[^0-9]', '', 'g') <> ''
				            THEN 'phone:' || regexp_replace(phone_number, '[^0-9]', '', 'g')
				            ELSE 'id:' || id::text END
				FROM customers WHERE id = ?
				""", id);
	}

	private static <T> PageResponse<T> page(List<T> content, int page, int size, long total) {
		return new PageResponse<>(content, page, size, total, (int) Math.ceil((double) total / size));
	}

	private static void validatePage(int page, int size) {
		if (page < 0 || size < 1 || size > 100 || (long) page * size > 100_000) {
			throw badRequest("Invalid pagination.");
		}
	}

	private static void validateDateRange(LocalDate from, LocalDate to) {
		if (from != null && to != null && from.isAfter(to)) throw badRequest("Start date must not follow end date.");
		if (from != null && to != null && to.isAfter(from.plusYears(10))) {
			throw badRequest("Date range must not exceed ten years.");
		}
	}

	private static Timestamp start(LocalDate date) {
		return Timestamp.from(date.atStartOfDay(SHOP_ZONE).toInstant());
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String trimToNull(String value) {
		if (value == null || value.isBlank()) return null;
		return value.trim();
	}

	private static void requireChoice(String value, List<String> choices, String message) {
		if (value == null || !choices.contains(value)) throw badRequest(message);
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	private static ResponseStatusException notFound(String message) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
	}
}
