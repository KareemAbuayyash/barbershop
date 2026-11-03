package com.barbershop.backend.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
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
import org.springframework.web.multipart.MultipartFile;

import com.barbershop.backend.admin.AdminDtos.CustomerBooking;
import com.barbershop.backend.admin.AdminDtos.CustomerAccountSummary;
import com.barbershop.backend.admin.AdminDtos.CustomerPasswordRequest;
import com.barbershop.backend.admin.AdminDtos.CustomerDetails;
import com.barbershop.backend.admin.AdminDtos.CustomerSummary;
import com.barbershop.backend.admin.AdminDtos.CustomerUpdateRequest;
import com.barbershop.backend.admin.AdminDtos.CheckoutProduct;
import com.barbershop.backend.admin.AdminDtos.CheckoutRequest;
import com.barbershop.backend.admin.AdminDtos.DashboardResponse;
import com.barbershop.backend.admin.AdminDtos.DashboardTrendPoint;
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
import com.barbershop.backend.admin.AdminDtos.ManualCustomerDebt;
import com.barbershop.backend.admin.AdminDtos.ManualDebtPayment;
import com.barbershop.backend.admin.AdminDtos.ManualDebtRequest;
import com.barbershop.backend.admin.AdminDtos.Product;
import com.barbershop.backend.admin.AdminDtos.ProductImage;
import com.barbershop.backend.admin.AdminDtos.ProductRequest;
import com.barbershop.backend.admin.AdminDtos.ProductUpdateRequest;
import com.barbershop.backend.admin.AdminDtos.ReportResponse;
import com.barbershop.backend.admin.AdminDtos.RetailItem;
import com.barbershop.backend.admin.AdminDtos.ShopProfile;
import com.barbershop.backend.booking.ShopScheduleService;

@Service
public class AdminManagementService {

	private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Hebron");
	private static final List<String> PAYMENT_METHODS = List.of("CASH", "CARD", "TRANSFER", "OTHER");
	private static final List<String> MOVEMENT_TYPES = List.of("PURCHASE", "CONSUMPTION", "SALE", "ADJUSTMENT");
	private static final String CUSTOMER_GROUPS = """
			WITH guest_customers AS (
			    SELECT c.id, c.name, c.phone_number,
			           CASE WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
			                THEN 'guest:phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
			                ELSE 'guest:id:' || c.id::text END AS group_key
			    FROM customers c
			    WHERE EXISTS (SELECT 1 FROM bookings b WHERE b.customer_id = c.id AND b.customer_account_id IS NULL)
			),
			customer_keys AS (
			    SELECT 'account:' || a.id::text AS group_key, a.id, a.display_name AS name,
			           a.phone_number, TRUE AS has_account, 0 AS priority
			    FROM customer_accounts a
			    UNION ALL
			    SELECT gc.group_key, gc.id, gc.name, gc.phone_number, FALSE, 1
			    FROM guest_customers gc
			    UNION ALL
			    SELECT CASE WHEN md.customer_account_id IS NOT NULL THEN 'account:' || md.customer_account_id::text
			                WHEN regexp_replace(coalesce(md.phone_number, ''), '[^0-9]', '', 'g') <> ''
			                THEN 'guest:phone:' || regexp_replace(md.phone_number, '[^0-9]', '', 'g')
			                ELSE 'manual:id:' || md.id::text END,
			           md.id, md.customer_name, md.phone_number, md.customer_account_id IS NOT NULL, 2
			    FROM manual_customer_debts md
			),
			customer_groups AS (
			    SELECT group_key, (array_agg(id ORDER BY priority, id))[1] AS id,
			           (array_agg(name ORDER BY priority, id))[1] AS name,
			           (array_agg(phone_number ORDER BY priority, id))[1] AS phone_number,
			           bool_or(has_account) AS has_account
			    FROM customer_keys
			    GROUP BY group_key
			),
			booking_identities AS (
			    SELECT b.id AS booking_id,
			           CASE WHEN b.customer_account_id IS NOT NULL THEN 'account:' || b.customer_account_id::text
			                WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
			                THEN 'guest:phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
			                ELSE 'guest:id:' || c.id::text END AS group_key
			    FROM bookings b JOIN customers c ON c.id = b.customer_id
			),
			booking_totals AS (
			    SELECT bi.group_key, count(DISTINCT CASE WHEN b.status = 'COMPLETED' THEN b.id END) AS visits
			    FROM booking_identities bi JOIN bookings b ON b.id = bi.booking_id
			    GROUP BY bi.group_key
			),
			payment_components AS (
			    SELECT bi.group_key, sum(p.amount) AS total_paid
			    FROM booking_identities bi JOIN booking_payments p ON p.booking_id = bi.booking_id
			    GROUP BY bi.group_key
			    UNION ALL
			    SELECT CASE WHEN md.customer_account_id IS NOT NULL THEN 'account:' || md.customer_account_id::text
			                WHEN regexp_replace(coalesce(md.phone_number, ''), '[^0-9]', '', 'g') <> ''
			                THEN 'guest:phone:' || regexp_replace(md.phone_number, '[^0-9]', '', 'g')
			                ELSE 'manual:id:' || md.id::text END, sum(mp.amount)
			    FROM manual_customer_debt_payments mp JOIN manual_customer_debts md ON md.id = mp.manual_debt_id
			    GROUP BY 1
			),
			payment_totals AS (
			    SELECT group_key, sum(total_paid) AS total_paid
			    FROM payment_components GROUP BY group_key
			),
			debt_components AS (
			    SELECT bi.group_key,
			           coalesce(sum(GREATEST(
			               b.service_price_snapshot + coalesce(ri.retail_total, 0) - coalesce(bp.paid, 0), 0)), 0) AS debt
			    FROM booking_identities bi
			    JOIN bookings b ON b.id = bi.booking_id AND b.status = 'COMPLETED'
			    LEFT JOIN (SELECT booking_id, sum(amount) AS paid FROM booking_payments GROUP BY booking_id) bp
			      ON bp.booking_id = b.id
			    LEFT JOIN (SELECT booking_id, sum(line_total) AS retail_total
			               FROM booking_retail_items GROUP BY booking_id) ri ON ri.booking_id = b.id
			    GROUP BY bi.group_key
			    UNION ALL
			    SELECT CASE WHEN md.customer_account_id IS NOT NULL THEN 'account:' || md.customer_account_id::text
			                WHEN regexp_replace(coalesce(md.phone_number, ''), '[^0-9]', '', 'g') <> ''
			                THEN 'guest:phone:' || regexp_replace(md.phone_number, '[^0-9]', '', 'g')
			                ELSE 'manual:id:' || md.id::text END,
			           sum(GREATEST(md.total_amount - coalesce(mp.paid, 0), 0))
			    FROM manual_customer_debts md
			    LEFT JOIN (SELECT manual_debt_id, sum(amount) paid
			               FROM manual_customer_debt_payments GROUP BY manual_debt_id) mp ON mp.manual_debt_id = md.id
			    GROUP BY 1
			),
			debt_totals AS (
			    SELECT group_key, sum(debt) AS debt
			    FROM debt_components GROUP BY group_key
			)
			""";

	private static final RowMapper<CustomerSummary> CUSTOMER_ROW = (rs, row) -> new CustomerSummary(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("phone_number"),
			rs.getLong("visits"),
			rs.getBigDecimal("total_paid"),
			rs.getBigDecimal("debt"),
			rs.getBoolean("has_account"));
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
	private final NotificationService notificationService;

	public AdminManagementService(
			JdbcTemplate jdbc,
			PasswordEncoder passwordEncoder,
			ShopScheduleService shopScheduleService,
			NotificationService notificationService) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.shopScheduleService = shopScheduleService;
		this.notificationService = notificationService;
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
		BigDecimal collected = scalarMoney("""
				SELECT coalesce((SELECT sum(amount) FROM booking_payments WHERE paid_at >= ? AND paid_at < ?), 0)
				     + coalesce((SELECT sum(amount) FROM manual_customer_debt_payments WHERE paid_at >= ? AND paid_at < ?), 0)
				""", start(today), start(today.plusDays(1)), start(today), start(today.plusDays(1)));
		BigDecimal debt = scalarMoney("""
				SELECT coalesce((SELECT sum(GREATEST(
				    b.service_price_snapshot + coalesce(ri.retail_total, 0) - coalesce(p.paid, 0), 0))
				    FROM bookings b LEFT JOIN (SELECT booking_id, sum(amount) paid FROM booking_payments GROUP BY booking_id) p
				      ON p.booking_id = b.id
				    LEFT JOIN (SELECT booking_id, sum(line_total) retail_total FROM booking_retail_items GROUP BY booking_id) ri
				      ON ri.booking_id = b.id WHERE b.status = 'COMPLETED'), 0)
				     + coalesce((SELECT sum(GREATEST(md.total_amount - coalesce(mp.paid, 0), 0))
				       FROM manual_customer_debts md LEFT JOIN (SELECT manual_debt_id, sum(amount) paid
				         FROM manual_customer_debt_payments GROUP BY manual_debt_id) mp ON mp.manual_debt_id = md.id), 0)
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
	public PageResponse<CustomerSummary> customers(String query, String type, int page, int size) {
		validatePage(page, size);
		if (!List.of("ALL", "ACCOUNTS", "GUESTS").contains(type)) {
			throw badRequest("Unsupported customer type filter.");
		}
		String pattern = "%" + escapeLike(query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";
		String base = CUSTOMER_GROUPS + """
				SELECT g.id, g.name, g.phone_number,
				       coalesce(bt.visits, 0) visits,
				       coalesce(pt.total_paid, 0) total_paid,
				       coalesce(dt.debt, 0) debt, g.has_account
				FROM customer_groups g
				LEFT JOIN booking_totals bt ON bt.group_key = g.group_key
				LEFT JOIN payment_totals pt ON pt.group_key = g.group_key
				LEFT JOIN debt_totals dt ON dt.group_key = g.group_key
				WHERE (? = '%%' OR lower(g.name) LIKE ? OR coalesce(g.phone_number, '') LIKE ?)
				  AND (? = 'ALL' OR (? = 'ACCOUNTS' AND g.has_account) OR (? = 'GUESTS' AND NOT g.has_account))
				""";
		long total = jdbc.queryForObject(CUSTOMER_GROUPS + """
				SELECT count(*) FROM customer_groups
				WHERE (? = '%%' OR lower(name) LIKE ? OR coalesce(phone_number, '') LIKE ?)
				  AND (? = 'ALL' OR (? = 'ACCOUNTS' AND has_account) OR (? = 'GUESTS' AND NOT has_account))
				""", Long.class, pattern, pattern, pattern, type, type, type);
		List<CustomerSummary> content = jdbc.query(
				base + " ORDER BY g.name, g.id LIMIT ? OFFSET ?",
				CUSTOMER_ROW,
				pattern, pattern, pattern, type, type, type, size, page * size);
		return page(content, page, size, total);
	}

	@Transactional(readOnly = true)
	public CustomerDetails customer(UUID id, String type) {
		String groupKey = customerGroupKey(id, type);
		CustomerSummary summary = jdbc.queryForObject(CUSTOMER_GROUPS + """
				SELECT g.id, g.name, g.phone_number, coalesce(bt.visits, 0) visits,
				       coalesce(pt.total_paid, 0) total_paid, coalesce(dt.debt, 0) debt, g.has_account
				FROM customer_groups g
				LEFT JOIN booking_totals bt ON bt.group_key = g.group_key
				LEFT JOIN payment_totals pt ON pt.group_key = g.group_key
				LEFT JOIN debt_totals dt ON dt.group_key = g.group_key
				WHERE g.group_key = ?
				""", CUSTOMER_ROW, groupKey);
		Map<UUID, List<RetailItem>> retailItemsByBooking = new java.util.HashMap<>();
		jdbc.query("""
				SELECT ri.booking_id, ri.product_name_snapshot, ri.product_unit_snapshot,
				       ri.quantity, ri.unit_price, ri.line_total
				FROM booking_retail_items ri JOIN bookings b ON b.id = ri.booking_id
				JOIN customers c ON c.id = b.customer_id
				WHERE CASE WHEN b.customer_account_id IS NOT NULL THEN 'account:' || b.customer_account_id::text
				           WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'guest:phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				           ELSE 'guest:id:' || c.id::text END = ?
				ORDER BY ri.created_at, ri.id
				""", (org.springframework.jdbc.core.RowCallbackHandler) rs -> retailItemsByBooking
				.computeIfAbsent(rs.getObject("booking_id", UUID.class), ignored -> new java.util.ArrayList<>())
				.add(new RetailItem(rs.getString("product_name_snapshot"), rs.getString("product_unit_snapshot"),
						rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_total"))), groupKey);
		List<CustomerBooking> bookings = jdbc.query("""
				SELECT b.id, b.service_name_snapshot, b.service_price_snapshot, b.status, b.created_at,
				       b.service_price_snapshot + coalesce(ri.retail_total, 0) AS total_amount,
				       coalesce(p.paid, 0) AS paid_amount,
				       greatest(b.service_price_snapshot + coalesce(ri.retail_total, 0) - coalesce(p.paid, 0), 0) AS balance
				FROM bookings b JOIN customers c ON c.id = b.customer_id
				LEFT JOIN (SELECT booking_id, sum(amount) paid FROM booking_payments GROUP BY booking_id) p ON p.booking_id = b.id
				LEFT JOIN (SELECT booking_id, sum(line_total) retail_total FROM booking_retail_items GROUP BY booking_id) ri ON ri.booking_id = b.id
				WHERE CASE WHEN b.customer_account_id IS NOT NULL THEN 'account:' || b.customer_account_id::text
				           WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'guest:phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				           ELSE 'guest:id:' || c.id::text END = ?
				ORDER BY b.created_at DESC
				""", (rs, row) -> {
					UUID bookingId = rs.getObject("id", UUID.class);
					return new CustomerBooking(
							bookingId,
							rs.getString("service_name_snapshot"),
							rs.getBigDecimal("service_price_snapshot"),
							rs.getBigDecimal("total_amount"),
							rs.getBigDecimal("paid_amount"),
							rs.getBigDecimal("balance"),
							rs.getString("status"),
							rs.getTimestamp("created_at").toInstant(),
							retailItemsByBooking.getOrDefault(bookingId, List.of()));
				}, groupKey);
		List<Payment> payments = paymentsForGroup(groupKey);
		List<CustomerAccountSummary> accounts = jdbc.query("""
				SELECT DISTINCT a.id, a.username_normalized, a.display_name, a.phone_number
				FROM customer_accounts a
				WHERE 'account:' || a.id::text = ?
				ORDER BY a.username_normalized
				""", (rs, row) -> new CustomerAccountSummary(
						rs.getObject("id", UUID.class), rs.getString("username_normalized"),
						rs.getString("display_name"), rs.getString("phone_number")), groupKey);
		List<ManualCustomerDebt> manualDebts = jdbc.query("""
				SELECT md.id, md.description, md.total_amount,
				       coalesce(mp.paid, 0) AS paid_amount,
				       greatest(md.total_amount - coalesce(mp.paid, 0), 0) AS balance,
				       md.created_at
				FROM manual_customer_debts md
				LEFT JOIN (SELECT manual_debt_id, sum(amount) paid FROM manual_customer_debt_payments GROUP BY manual_debt_id) mp
				  ON mp.manual_debt_id = md.id
				WHERE CASE WHEN md.customer_account_id IS NOT NULL THEN 'account:' || md.customer_account_id::text
				           WHEN regexp_replace(coalesce(md.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'guest:phone:' || regexp_replace(md.phone_number, '[^0-9]', '', 'g')
				           ELSE 'manual:id:' || md.id::text END = ?
				ORDER BY md.created_at, md.id
				""", (rs, row) -> {
			UUID debtId = rs.getObject("id", UUID.class);
			List<ManualDebtPayment> debtPayments = jdbc.query("""
					SELECT id, amount, method, notes, paid_at
					FROM manual_customer_debt_payments WHERE manual_debt_id = ? ORDER BY paid_at, id
					""", (paymentRs, paymentRow) -> new ManualDebtPayment(
						paymentRs.getObject("id", UUID.class), paymentRs.getBigDecimal("amount"),
						paymentRs.getString("method"), paymentRs.getString("notes"),
						paymentRs.getTimestamp("paid_at").toInstant()), debtId);
			return new ManualCustomerDebt(debtId, rs.getString("description"), rs.getBigDecimal("total_amount"),
					rs.getBigDecimal("paid_amount"), rs.getBigDecimal("balance"),
					rs.getTimestamp("created_at").toInstant(), debtPayments);
		}, groupKey);
		return new CustomerDetails(summary, bookings, payments, accounts, manualDebts);
	}

	@Transactional
	public CustomerDetails updateCustomer(UUID id, String type, CustomerUpdateRequest request) {
		String groupKey = customerGroupKey(id, type);
		String name = request.name().trim();
		if (name.length() < 2) throw badRequest("Customer name must contain at least 2 characters.");
		String phone = trimToNull(request.phoneNumber());
		jdbc.update("""
				UPDATE customer_accounts SET display_name = ?, phone_number = ?, updated_at = CURRENT_TIMESTAMP
				WHERE 'account:' || id::text = ?
				""", name, phone == null ? "" : phone, groupKey);
		jdbc.update("""
				UPDATE manual_customer_debts SET customer_name = ?, phone_number = ?
				WHERE CASE WHEN customer_account_id IS NOT NULL THEN 'account:' || customer_account_id::text
				           WHEN regexp_replace(coalesce(phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'guest:phone:' || regexp_replace(phone_number, '[^0-9]', '', 'g')
				           ELSE 'manual:id:' || id::text END = ?
				""", name, phone == null ? "" : phone, groupKey);
		jdbc.update("""
				UPDATE customers SET name = ?, phone_number = ?
				WHERE ? LIKE 'guest:%'
				  AND CASE WHEN regexp_replace(coalesce(phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'guest:phone:' || regexp_replace(phone_number, '[^0-9]', '', 'g')
				           ELSE 'guest:id:' || id::text END = ?
				  AND EXISTS (SELECT 1 FROM bookings b WHERE b.customer_id = customers.id AND b.customer_account_id IS NULL)
				""", name, phone, groupKey, groupKey);
		return customer(id, type);
	}

	@Transactional
	public void resetCustomerPassword(UUID customerId, String type, UUID accountId, CustomerPasswordRequest request) {
		String groupKey = customerGroupKey(customerId, type);
		int bytes = request.newPassword().getBytes(StandardCharsets.UTF_8).length;
		if (bytes < 8 || bytes > 72) {
			throw badRequest("Password must contain 8-72 UTF-8 bytes.");
		}
		int updated = jdbc.update("""
				UPDATE customer_accounts
				SET password_hash = ?, updated_at = CURRENT_TIMESTAMP
				WHERE id = ? AND ? = 'ACCOUNTS' AND 'account:' || id::text = ?
			""", passwordEncoder.encode(request.newPassword()), accountId, type, groupKey);
		if (updated == 0) throw notFound("Customer account not found.");
	}

	@Transactional(readOnly = true)
	public List<Payment> payments(LocalDate fromDate, LocalDate toDate) {
		validateDateRange(fromDate, toDate);
		return jdbc.query("""
				SELECT * FROM (
				    SELECT p.id, p.booking_id, c.name customer_name, p.amount, p.method, p.notes, p.paid_at
				    FROM booking_payments p JOIN customers c ON c.id = p.customer_id
				    WHERE (? IS NULL OR p.paid_at >= ?)
				      AND (? IS NULL OR p.paid_at < ?)
				    UNION ALL
				    SELECT p.id, p.manual_debt_id AS booking_id, d.customer_name, p.amount, p.method,
				           coalesce(p.notes, 'دين يدوي: ' || d.description) AS notes, p.paid_at
				    FROM manual_customer_debt_payments p JOIN manual_customer_debts d ON d.id = p.manual_debt_id
				    WHERE (? IS NULL OR p.paid_at >= ?)
				      AND (? IS NULL OR p.paid_at < ?)
				) all_payments
				ORDER BY paid_at DESC LIMIT 1000
				""", PAYMENT_ROW,
				fromDate, fromDate == null ? null : start(fromDate),
				toDate, toDate == null ? null : start(toDate.plusDays(1)),
				fromDate, fromDate == null ? null : start(fromDate),
				toDate, toDate == null ? null : start(toDate.plusDays(1)));
	}

	@Transactional
	public Payment recordPayment(UUID bookingId, PaymentRequest request) {
		return recordPayment(bookingId, request, true);
	}

	private Payment recordPayment(UUID bookingId, PaymentRequest request, boolean notifyCustomer) {
		requireChoice(request.method(), PAYMENT_METHODS, "Unsupported payment method.");
		try {
			return jdbc.queryForObject("""
					SELECT b.id AS booking_id, b.status, c.id AS customer_id, c.name AS customer_name,
					       b.service_price_snapshot AS service_value,
					       coalesce((SELECT sum(amount) FROM booking_payments WHERE booking_id = b.id), 0) AS paid,
					       coalesce((SELECT sum(line_total) FROM booking_retail_items WHERE booking_id = b.id), 0) AS retail_total
					FROM bookings b JOIN customers c ON c.id = b.customer_id
					WHERE b.id = ? FOR UPDATE OF b
					""", (rs, row) -> {
				if (!"COMPLETED".equals(rs.getString("status"))) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed bookings can be paid.");
				}
				BigDecimal previouslyPaid = rs.getBigDecimal("paid");
				BigDecimal serviceValue = rs.getBigDecimal("service_value");
				BigDecimal remaining = serviceValue.add(rs.getBigDecimal("retail_total")).subtract(previouslyPaid);
				if (request.amount().compareTo(remaining) > 0) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment exceeds the outstanding booking balance.");
				}
				UUID customerId = rs.getObject("customer_id", UUID.class);
				UUID paymentId = UUID.randomUUID();
				jdbc.update("""
						INSERT INTO booking_payments (id, booking_id, customer_id, amount, method, notes)
						VALUES (?, ?, ?, ?, ?, ?)
						""", paymentId, bookingId, customerId, request.amount(), request.method(),
						trimToNull(request.notes()));
				notificationService.notifyPaymentRecorded(paymentId, notifyCustomer);
				return new Payment(paymentId, bookingId, rs.getString("customer_name"), request.amount(),
						request.method(), trimToNull(request.notes()), Instant.now());
			}, bookingId);
		} catch (EmptyResultDataAccessException exception) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found.");
		}
	}

	@Transactional
	public void recordCustomerPayment(UUID customerId, String type, PaymentRequest request) {
		requireChoice(request.method(), PAYMENT_METHODS, "Unsupported payment method.");
		if (request.amount().scale() > 2) throw badRequest("Payment amount must have no more than two decimal places.");
		String groupKey = customerGroupKey(customerId, type);
		List<Map<String, Object>> dueBookings = jdbc.queryForList("""
				SELECT b.id AS booking_id,
				       b.service_price_snapshot + coalesce(ri.retail_total, 0) - coalesce(p.paid, 0) AS balance
				FROM bookings b JOIN customers c ON c.id = b.customer_id
				LEFT JOIN (SELECT booking_id, sum(amount) paid FROM booking_payments GROUP BY booking_id) p
				  ON p.booking_id = b.id
				LEFT JOIN (SELECT booking_id, sum(line_total) retail_total
				           FROM booking_retail_items GROUP BY booking_id) ri ON ri.booking_id = b.id
				WHERE b.status = 'COMPLETED'
				  AND CASE WHEN b.customer_account_id IS NOT NULL THEN 'account:' || b.customer_account_id::text
				           WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'guest:phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				           ELSE 'guest:id:' || c.id::text END = ?
				  AND b.service_price_snapshot + coalesce(ri.retail_total, 0) > coalesce(p.paid, 0)
				ORDER BY b.created_at, b.id
				FOR UPDATE OF b
				""", groupKey);
		List<Map<String, Object>> dueManualDebts = jdbc.queryForList("""
				SELECT md.id AS manual_debt_id, md.total_amount - coalesce(mp.paid, 0) AS balance
				FROM manual_customer_debts md
				LEFT JOIN (SELECT manual_debt_id, sum(amount) paid
				           FROM manual_customer_debt_payments GROUP BY manual_debt_id) mp ON mp.manual_debt_id = md.id
				WHERE CASE WHEN md.customer_account_id IS NOT NULL THEN 'account:' || md.customer_account_id::text
				           WHEN regexp_replace(coalesce(md.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'guest:phone:' || regexp_replace(md.phone_number, '[^0-9]', '', 'g')
				           ELSE 'manual:id:' || md.id::text END = ?
				  AND md.total_amount > coalesce(mp.paid, 0)
				ORDER BY md.created_at, md.id
				FOR UPDATE OF md
				""", groupKey);
		BigDecimal totalBalance = dueBookings.stream()
				.map(row -> (BigDecimal) row.get("balance"))
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.add(dueManualDebts.stream().map(row -> (BigDecimal) row.get("balance"))
						.reduce(BigDecimal.ZERO, BigDecimal::add));
		if (totalBalance.signum() <= 0 || request.amount().compareTo(totalBalance) > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Payment exceeds the customer's outstanding balance.");
		}

		BigDecimal remaining = request.amount();
		for (Map<String, Object> dueBooking : dueBookings) {
			if (remaining.signum() <= 0) break;
			BigDecimal allocated = remaining.min((BigDecimal) dueBooking.get("balance"));
			UUID bookingId = (UUID) dueBooking.get("booking_id");
			recordPayment(bookingId, new PaymentRequest(allocated, request.method(), request.notes()), false);
			remaining = remaining.subtract(allocated);
		}
		for (Map<String, Object> dueManualDebt : dueManualDebts) {
			if (remaining.signum() <= 0) break;
			BigDecimal allocated = remaining.min((BigDecimal) dueManualDebt.get("balance"));
			jdbc.update("""
					INSERT INTO manual_customer_debt_payments (id, manual_debt_id, amount, method, notes)
					VALUES (?, ?, ?, ?, ?)
					""", UUID.randomUUID(), dueManualDebt.get("manual_debt_id"), allocated,
					request.method(), trimToNull(request.notes()));
			remaining = remaining.subtract(allocated);
		}
		BigDecimal remainingBalance = jdbc.queryForObject(CUSTOMER_GROUPS + """
				SELECT coalesce(dt.debt, 0)
				FROM customer_groups g LEFT JOIN debt_totals dt ON dt.group_key = g.group_key
				WHERE g.group_key = ?
				""", BigDecimal.class, groupKey);
		UUID guestBookingId = null;
		if (!"ACCOUNTS".equals(type)) {
			guestBookingId = jdbc.query("""
					SELECT b.id FROM bookings b JOIN customers c ON c.id = b.customer_id
					WHERE b.customer_account_id IS NULL
					  AND CASE WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
					           THEN 'guest:phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
					           ELSE 'guest:id:' || c.id::text END = ?
				ORDER BY b.created_at DESC LIMIT 1
					""", (rs, row) -> rs.getObject("id", UUID.class), groupKey).stream().findFirst().orElse(null);
		}
		notificationService.notifyCustomerAggregatePayment(customerId,
				guestBookingId,
				request.amount(), remainingBalance == null ? BigDecimal.ZERO : remainingBalance);
	}

	@Transactional
	public ManualCustomerDebt addManualCustomerDebt(ManualDebtRequest request, String actor) {
		String name = request.customerName().trim();
		String phone = request.phoneNumber().trim();
		String digits = phone.replaceAll("[^0-9]", "");
		if (name.length() < 2 || digits.isBlank()) throw badRequest("Enter a valid customer name and phone number.");
		if (request.amount().scale() > 2) throw badRequest("Debt amount must have no more than two decimal places.");
		UUID accountId = jdbc.query("""
				SELECT id FROM customer_accounts
				WHERE regexp_replace(coalesce(phone_number, ''), '[^0-9]', '', 'g') = ?
				ORDER BY id LIMIT 1
				""", (rs, row) -> rs.getObject("id", UUID.class), digits).stream().findFirst().orElse(null);
		UUID debtId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO manual_customer_debts
				    (id, customer_account_id, customer_name, phone_number, description, total_amount, created_by)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", debtId, accountId, name, phone, request.description().trim(), request.amount(), actor);
		return jdbc.queryForObject("""
				SELECT id, description, total_amount, 0::numeric AS paid_amount,
				       total_amount AS balance, created_at
				FROM manual_customer_debts WHERE id = ?
				""", (rs, row) -> new ManualCustomerDebt(rs.getObject("id", UUID.class),
					rs.getString("description"), rs.getBigDecimal("total_amount"), rs.getBigDecimal("paid_amount"),
					rs.getBigDecimal("balance"), rs.getTimestamp("created_at").toInstant(), List.of()), debtId);
	}

	@Transactional
	public void checkoutBooking(UUID bookingId, CheckoutRequest request, String actor) {
		requireChoice(request.method(), PAYMENT_METHODS, "Unsupported payment method.");
		if (request.amount().scale() > 2) throw badRequest("Payment amount must have no more than two decimal places.");
		Map<String, Object> booking;
		try {
			booking = jdbc.queryForMap("""
					SELECT b.status, b.service_price_snapshot, c.id customer_id, b.queue_date
					FROM bookings b JOIN customers c ON c.id = b.customer_id
					WHERE b.id = ? FOR UPDATE OF b
					""", bookingId);
		} catch (EmptyResultDataAccessException exception) {
			throw notFound("Booking not found.");
		}
		if (!"CONFIRMED".equals(booking.get("status"))) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only confirmed bookings can be checked out.");
		}

		UUID customerId = (UUID) booking.get("customer_id");
		BigDecimal serviceValue = (BigDecimal) booking.get("service_price_snapshot");
		BigDecimal alreadyPaid = scalarMoney(
				"SELECT coalesce(sum(amount), 0) FROM booking_payments WHERE booking_id = ?", bookingId);
		BigDecimal retailTotal = BigDecimal.ZERO;

		for (CheckoutProduct item : request.products()) {
			if (item.quantity().scale() > 3 || item.unitPrice().scale() > 2) {
				throw badRequest("Product quantities must have at most three decimals and prices at most two.");
			}
			Map<String, Object> product;
			try {
				product = jdbc.queryForMap("""
						SELECT name, unit, quantity, low_stock_threshold, active
						FROM inventory_products WHERE id = ? FOR UPDATE
						""", item.productId());
			} catch (EmptyResultDataAccessException exception) {
				throw notFound("Inventory product not found.");
			}
			if (!Boolean.TRUE.equals(product.get("active"))) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "An inactive product cannot be sold.");
			}
			BigDecimal stock = (BigDecimal) product.get("quantity");
			if (stock.compareTo(item.quantity()) < 0) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient inventory for " + product.get("name") + ".");
			}

			String productName = (String) product.get("name");
			BigDecimal lineTotal = item.quantity().multiply(item.unitPrice()).setScale(2, RoundingMode.HALF_UP);
			retailTotal = retailTotal.add(lineTotal);
			jdbc.update("""
					INSERT INTO booking_retail_items
						(id, booking_id, product_id, product_name_snapshot, product_unit_snapshot,
						 quantity, unit_price, line_total)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					""", UUID.randomUUID(), bookingId, item.productId(), productName, product.get("unit"),
					item.quantity(), item.unitPrice(), lineTotal);
			jdbc.update("""
					UPDATE inventory_products
					SET quantity = quantity - ?, updated_at = CURRENT_TIMESTAMP
					WHERE id = ? AND quantity >= ?
					""", item.quantity(), item.productId(), item.quantity());
			UUID movementId = insertMovement(item.productId(), "SALE", item.quantity(), null, item.unitPrice(), lineTotal,
					"Sale with booking " + bookingId, actor);
			notificationService.notifyInventoryMovement(item.productId(), movementId, "SALE");
			BigDecimal remainingStock = stock.subtract(item.quantity());
			BigDecimal lowStockThreshold = (BigDecimal) product.get("low_stock_threshold");
			if (stock.compareTo(lowStockThreshold) > 0 && remainingStock.compareTo(lowStockThreshold) <= 0) {
				notificationService.notifyInventoryThreshold(item.productId(), movementId, remainingStock.signum() == 0);
			}
		}

		BigDecimal outstanding = serviceValue.add(retailTotal).subtract(alreadyPaid);
		if (request.amount().compareTo(outstanding) > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment exceeds the outstanding checkout balance.");
		}

		Object queueDateValue = booking.get("queue_date");
		LocalDate queueDate = queueDateValue instanceof java.sql.Date sqlDate
				? sqlDate.toLocalDate()
				: queueDateValue instanceof LocalDate date ? date : null;
		if (queueDate != null) jdbc.execute("SET CONSTRAINTS uq_bookings_queue_date_position DEFERRED");
		jdbc.update("UPDATE bookings SET status = 'COMPLETED', queue_position = CASE WHEN queue_date IS NULL THEN queue_position ELSE NULL END, updated_at = CURRENT_TIMESTAMP WHERE id = ?", bookingId);
		if (queueDate != null) {
			jdbc.update("""
					WITH numbered AS (
					    SELECT id, row_number() OVER (ORDER BY queue_position, created_at, id)::INTEGER position
					    FROM bookings WHERE queue_date = ? AND status IN ('PENDING', 'CONFIRMED')
					)
					UPDATE bookings b SET queue_position = numbered.position
					FROM numbered WHERE b.id = numbered.id
					""", queueDate);
			jdbc.update("UPDATE booking_queue_counters SET last_position = (SELECT count(*) FROM bookings WHERE queue_date = ? AND status IN ('PENDING', 'CONFIRMED')), updated_at = CURRENT_TIMESTAMP WHERE queue_date = ?",
				queueDate, queueDate);
			List<UUID> approaching = jdbc.query("SELECT id FROM bookings WHERE queue_date = ? AND status IN ('PENDING', 'CONFIRMED') AND queue_position <= 2",
				(rs, row) -> rs.getObject("id", UUID.class), queueDate);
			approaching.forEach(notificationService::notifyQueueApproaching);
		}
		if (request.amount().signum() > 0) {
			UUID paymentId = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO booking_payments (id, booking_id, customer_id, amount, method, notes)
					VALUES (?, ?, ?, ?, ?, ?)
					""", paymentId, bookingId, customerId, request.amount(), request.method(),
					trimToNull(request.notes()));
		}
		notificationService.notifyCheckoutCompleted(bookingId);
	}

	@Transactional(readOnly = true)
	public List<DebtResponse> debts(String query) {
		String pattern = "%" + escapeLike(query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";
		return jdbc.query("""
				SELECT b.id booking_id, c.id customer_id, c.name customer_name, c.phone_number,
				       b.service_name_snapshot, b.service_price_snapshot,
				       b.service_price_snapshot + coalesce(ri.retail_total, 0) - coalesce(p.paid, 0) balance
				FROM bookings b JOIN customers c ON c.id = b.customer_id
				LEFT JOIN (SELECT booking_id, sum(amount) paid FROM booking_payments GROUP BY booking_id) p
				  ON p.booking_id = b.id
				LEFT JOIN (
				    SELECT booking_id, sum(line_total) retail_total
				    FROM booking_retail_items GROUP BY booking_id
				) ri ON ri.booking_id = b.id
				WHERE b.status = 'COMPLETED'
				  AND b.service_price_snapshot + coalesce(ri.retail_total, 0) > coalesce(p.paid, 0)
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

	@Transactional
	public void sendDebtReminder(UUID customerId, String type) {
		String groupKey = customerGroupKey(customerId, type);
		BigDecimal totalBalance = jdbc.queryForObject(CUSTOMER_GROUPS + """
				SELECT coalesce(dt.debt, 0)
				FROM customer_groups g LEFT JOIN debt_totals dt ON dt.group_key = g.group_key
				WHERE g.group_key = ?
				""", BigDecimal.class, groupKey);
		if (totalBalance == null || totalBalance.signum() <= 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This customer has no outstanding balance.");
		}
		if ("ACCOUNTS".equals(type)) {
			notificationService.notifyAccountDebtReminder(customerId, totalBalance);
			return;
		}
		UUID bookingId = jdbc.query("""
				SELECT b.id
				FROM bookings b JOIN customers c ON c.id = b.customer_id
				WHERE b.customer_account_id IS NULL
				  AND CASE WHEN b.customer_account_id IS NOT NULL THEN 'account:' || b.customer_account_id::text
				           WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'guest:phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				           ELSE 'guest:id:' || c.id::text END = ?
				ORDER BY b.created_at DESC, b.id DESC LIMIT 1
				""", (rs, row) -> rs.getObject("id", UUID.class), groupKey).stream().findFirst().orElse(null);
		if (bookingId == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"This customer has no registered account or guest booking for notifications.");
		}
		notificationService.notifyDebtReminder(bookingId, totalBalance);
	}

	@Transactional
	public void sendDebtReminderForBooking(UUID bookingId) {
		BigDecimal balance;
		try {
			balance = jdbc.queryForObject("""
					SELECT b.service_price_snapshot + coalesce(ri.retail_total, 0) - coalesce(p.paid, 0)
					FROM bookings b
					LEFT JOIN (SELECT booking_id, sum(amount) paid FROM booking_payments GROUP BY booking_id) p
					  ON p.booking_id = b.id
					LEFT JOIN (SELECT booking_id, sum(line_total) retail_total
					           FROM booking_retail_items GROUP BY booking_id) ri ON ri.booking_id = b.id
					WHERE b.id = ? AND b.status = 'COMPLETED'
					""", BigDecimal.class, bookingId);
		} catch (EmptyResultDataAccessException exception) {
			throw notFound("Unpaid completed booking not found.");
		}
		if (balance == null || balance.signum() <= 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking has no outstanding balance.");
		}
		notificationService.notifyDebtReminder(bookingId, balance);
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
		Expense expense = jdbc.queryForObject("""
				SELECT id, category, description, amount, expense_date, method, notes, created_at
				FROM shop_expenses WHERE id = ?
				""", (rs, row) -> new Expense(id, rs.getString("category"), rs.getString("description"),
				rs.getBigDecimal("amount"), rs.getObject("expense_date", LocalDate.class),
				rs.getString("method"), rs.getString("notes"), rs.getTimestamp("created_at").toInstant()), id);
		notificationService.notifyExpenseCreated(id);
		return expense;
	}

	@Transactional
	public Expense updateExpense(UUID id, ExpenseRequest request) {
		requireChoice(request.method(), PAYMENT_METHODS, "Unsupported payment method.");
		int updated = jdbc.update("""
				UPDATE shop_expenses
				SET category = ?, description = ?, amount = ?, expense_date = ?, method = ?, notes = ?
				WHERE id = ?
				""", request.category().trim(), request.description().trim(), request.amount(),
				request.expenseDate(), request.method(), trimToNull(request.notes()), id);
		if (updated == 0) throw notFound("Expense not found.");
		return jdbc.queryForObject("""
				SELECT id, category, description, amount, expense_date, method, notes, created_at
				FROM shop_expenses WHERE id = ?
				""", (rs, row) -> new Expense(rs.getObject("id", UUID.class), rs.getString("category"),
				 rs.getString("description"), rs.getBigDecimal("amount"),
				 rs.getObject("expense_date", LocalDate.class), rs.getString("method"),
				 rs.getString("notes"), rs.getTimestamp("created_at").toInstant()), id);
	}

	@Transactional
	public void deleteExpense(UUID id) {
		if (jdbc.update("DELETE FROM shop_expenses WHERE id = ?", id) == 0) {
			throw notFound("Expense not found.");
		}
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
				SELECT coalesce((SELECT sum(amount) FROM booking_payments WHERE paid_at >= ? AND paid_at < ?), 0)
				     + coalesce((SELECT sum(amount) FROM manual_customer_debt_payments WHERE paid_at >= ? AND paid_at < ?), 0)
				""", start(fromDate), start(toDate.plusDays(1)), start(fromDate), start(toDate.plusDays(1)));
		BigDecimal expenses = scalarMoney("""
				SELECT coalesce(sum(amount), 0) FROM shop_expenses
				WHERE expense_date >= ? AND expense_date <= ?
				""", fromDate, toDate);
		BigDecimal debt = scalarMoney("""
				SELECT coalesce((SELECT sum(GREATEST(
				    b.service_price_snapshot + coalesce(ri.retail_total, 0) - coalesce(p.paid, 0), 0))
				    FROM bookings b LEFT JOIN (SELECT booking_id, sum(amount) paid FROM booking_payments GROUP BY booking_id) p
				      ON p.booking_id = b.id
				    LEFT JOIN (SELECT booking_id, sum(line_total) retail_total FROM booking_retail_items GROUP BY booking_id) ri
				      ON ri.booking_id = b.id WHERE b.status = 'COMPLETED'), 0)
				     + coalesce((SELECT sum(GREATEST(md.total_amount - coalesce(mp.paid, 0), 0))
				       FROM manual_customer_debts md LEFT JOIN (SELECT manual_debt_id, sum(amount) paid
				         FROM manual_customer_debt_payments GROUP BY manual_debt_id) mp ON mp.manual_debt_id = md.id), 0)
				""");
		return new ReportResponse(fromDate, toDate, revenue, expenses, revenue.subtract(expenses), debt);
	}

	@Transactional(readOnly = true)
	public List<BestSellingProduct> bestSellingProducts(LocalDate fromDate, LocalDate toDate) {
		LocalDate[] range = validatedSalesRange(fromDate, toDate);
		return jdbc.query("""
				SELECT ri.product_name_snapshot, ri.product_unit_snapshot,
				       sum(ri.quantity) AS quantity_sold, sum(ri.line_total) AS sales_total,
				       count(DISTINCT ri.booking_id) AS booking_count
				FROM booking_retail_items ri
				JOIN bookings b ON b.id = ri.booking_id
				WHERE b.status = 'COMPLETED'
				  AND b.updated_at >= ? AND b.updated_at < ?
				GROUP BY ri.product_name_snapshot, ri.product_unit_snapshot
				ORDER BY quantity_sold DESC, sales_total DESC, ri.product_name_snapshot
				""", (rs, row) -> new BestSellingProduct(
						rs.getString("product_name_snapshot"),
						rs.getString("product_unit_snapshot"),
						rs.getBigDecimal("quantity_sold"),
						rs.getBigDecimal("sales_total"),
						rs.getLong("booking_count")),
				start(range[0]), start(range[1].plusDays(1)));
	}

	@Transactional(readOnly = true)
	public List<BestSellingService> bestSellingServices(LocalDate fromDate, LocalDate toDate) {
		LocalDate[] range = validatedSalesRange(fromDate, toDate);
		return jdbc.query("""
				SELECT b.service_name_snapshot, count(*) AS booking_count,
				       sum(b.service_price_snapshot) AS service_revenue
				FROM bookings b
				WHERE b.status = 'COMPLETED'
				  AND b.updated_at >= ? AND b.updated_at < ?
				GROUP BY b.service_name_snapshot
				ORDER BY booking_count DESC, service_revenue DESC, b.service_name_snapshot
				""", (rs, row) -> new BestSellingService(
						rs.getString("service_name_snapshot"),
						rs.getLong("booking_count"),
						rs.getBigDecimal("service_revenue")),
				start(range[0]), start(range[1].plusDays(1)));
	}

	@Transactional(readOnly = true)
	public List<BookingSalesReport> bookingSales(LocalDate fromDate, LocalDate toDate) {
		LocalDate[] range = validatedSalesRange(fromDate, toDate);
		List<BookingSalesBase> bookings = jdbc.query("""
				SELECT b.id, coalesce(c.name, ca.display_name, 'عميل') AS customer_name,
				       b.updated_at AS completed_at, b.service_name_snapshot, b.service_price_snapshot,
				       coalesce(ri.retail_total, 0) AS retail_total, coalesce(p.paid_total, 0) AS paid_total
				FROM bookings b
				LEFT JOIN customers c ON c.id = b.customer_id
				LEFT JOIN customer_accounts ca ON ca.id = b.customer_account_id
				LEFT JOIN (
				    SELECT booking_id, sum(line_total) AS retail_total
				    FROM booking_retail_items GROUP BY booking_id
				) ri ON ri.booking_id = b.id
				LEFT JOIN (
				    SELECT booking_id, sum(amount) AS paid_total
				    FROM booking_payments GROUP BY booking_id
				) p ON p.booking_id = b.id
				WHERE b.status = 'COMPLETED'
				  AND b.updated_at >= ? AND b.updated_at < ?
				ORDER BY b.updated_at DESC, b.id
				LIMIT 500
				""", (rs, row) -> new BookingSalesBase(
						rs.getObject("id", UUID.class),
						rs.getString("customer_name"),
						rs.getTimestamp("completed_at").toInstant(),
						rs.getString("service_name_snapshot"),
						rs.getBigDecimal("service_price_snapshot"),
						rs.getBigDecimal("retail_total"),
						rs.getBigDecimal("paid_total")),
				start(range[0]), start(range[1].plusDays(1)));
		if (bookings.isEmpty()) return List.of();

		Map<UUID, List<RetailItem>> itemsByBooking = new java.util.HashMap<>();
		String placeholders = String.join(",", java.util.Collections.nCopies(bookings.size(), "?"));
		Object[] bookingIds = bookings.stream().map(BookingSalesBase::id).toArray();
		jdbc.query("""
				SELECT booking_id, product_name_snapshot, product_unit_snapshot, quantity, unit_price, line_total
				FROM booking_retail_items WHERE booking_id IN (%s)
				ORDER BY created_at, id
				""".formatted(placeholders), (rs, row) -> {
			UUID bookingId = rs.getObject("booking_id", UUID.class);
			itemsByBooking.computeIfAbsent(bookingId, ignored -> new java.util.ArrayList<>()).add(
					new RetailItem(rs.getString("product_name_snapshot"), rs.getString("product_unit_snapshot"),
							rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_total")));
			return null;
		}, bookingIds);

		return bookings.stream().map(booking -> {
			BigDecimal total = booking.servicePrice().add(booking.retailTotal());
			BigDecimal balance = total.subtract(booking.paidTotal()).max(BigDecimal.ZERO);
			return new BookingSalesReport(
					booking.id(), booking.customerName(), booking.completedAt(), booking.serviceName(),
					booking.servicePrice(), itemsByBooking.getOrDefault(booking.id(), List.of()),
					total, booking.paidTotal(), balance);
		}).toList();
	}

	private LocalDate[] validatedSalesRange(LocalDate fromDate, LocalDate toDate) {
		if (fromDate == null || toDate == null) {
			LocalDate today = LocalDate.now(SHOP_ZONE);
			fromDate = fromDate == null ? today.withDayOfMonth(1) : fromDate;
			toDate = toDate == null ? today : toDate;
		}
		validateDateRange(fromDate, toDate);
		return new LocalDate[] { fromDate, toDate };
	}

	private record BookingSalesBase(
			UUID id,
			String customerName,
			Instant completedAt,
			String serviceName,
			BigDecimal servicePrice,
			BigDecimal retailTotal,
			BigDecimal paidTotal) {
	}

	@Transactional(readOnly = true)
	public List<Product> products() {
		return jdbc.query("""
				SELECT id, name, unit, quantity, low_stock_threshold, purchase_price, sale_price,
				       CASE WHEN image_data IS NOT NULL THEN '/api/admin/inventory/products/' || id::text || '/image' END AS image_url,
				       active
				FROM inventory_products ORDER BY active DESC, name
				""", (rs, row) -> new Product(rs.getObject("id", UUID.class), rs.getString("name"),
				rs.getString("unit"), rs.getBigDecimal("quantity"), rs.getBigDecimal("low_stock_threshold"),
				rs.getBigDecimal("purchase_price"), rs.getBigDecimal("sale_price"), rs.getString("image_url"),
				rs.getBoolean("active")));
	}

	@Transactional
	public Product createProduct(ProductRequest request, String actor) {
		return createProduct(request, actor, null);
	}

	@Transactional
	public Product createProduct(ProductRequest request, String actor, MultipartFile image) {
		UUID id = UUID.randomUUID();
		BigDecimal purchasePrice = request.purchasePrice() == null ? BigDecimal.ZERO : request.purchasePrice();
		BigDecimal salePrice = request.salePrice() == null ? BigDecimal.ZERO : request.salePrice();
		jdbc.update("""
				INSERT INTO inventory_products
				    (id, name, unit, quantity, low_stock_threshold, purchase_price, sale_price)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", id, request.name().trim(), request.unit().trim(), request.availableQuantity(),
				request.lowStockThreshold(), purchasePrice, salePrice);
		if (request.availableQuantity().signum() > 0) {
			insertMovement(id, "PURCHASE", request.availableQuantity(), purchasePrice, null, null, "Initial stock", actor);
		}
		if (image != null && !image.isEmpty()) saveProductImage(id, image);
		Product product = findProduct(id);
		notificationService.notifyInventoryProductCreated(id);
		return product;
	}

	@Transactional
	public Product recordMovement(UUID productId, InventoryMovement movement, String actor) {
		requireChoice(movement.movementType(), MOVEMENT_TYPES, "Unsupported inventory movement.");
		Map<String, Object> product;
		try {
			product = jdbc.queryForMap("""
					SELECT quantity, low_stock_threshold, name, unit
					FROM inventory_products WHERE id = ? FOR UPDATE
					""", productId);
		} catch (EmptyResultDataAccessException exception) {
			throw notFound("Inventory product not found.");
		}
		BigDecimal currentStock = (BigDecimal) product.get("quantity");
		BigDecimal lowStockThreshold = (BigDecimal) product.get("low_stock_threshold");
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
		BigDecimal newStock = currentStock.add(delta);
		BigDecimal movementLineTotal = "SALE".equals(movement.movementType()) && movement.unitPrice() != null
				? movement.quantity().multiply(movement.unitPrice()).setScale(2, RoundingMode.HALF_UP)
				: null;
		UUID movementId = insertMovement(productId, movement.movementType(), movement.quantity(), movement.unitCost(), movement.unitPrice(), movementLineTotal,
				trimToNull(movement.notes()), actor);
		notificationService.notifyInventoryMovement(productId, movementId, movement.movementType());
		if (currentStock.compareTo(lowStockThreshold) > 0 && newStock.compareTo(lowStockThreshold) <= 0) {
			notificationService.notifyInventoryThreshold(productId, movementId, newStock.signum() == 0);
		}
		if ("PURCHASE".equals(movement.movementType()) && movement.unitCost() != null) {
			jdbc.update("UPDATE inventory_products SET purchase_price = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
					movement.unitCost(), productId);
		}
		Product productResult = findProduct(productId);
		notificationService.notifyInventoryProductChanged(productId, "updated");
		return productResult;
	}

	@Transactional
	public Product updateProduct(UUID id, ProductUpdateRequest request) {
		return updateProduct(id, request, null);
	}

	@Transactional
	public Product updateProduct(UUID id, ProductUpdateRequest request, MultipartFile image) {
		int updated = jdbc.update("""
				UPDATE inventory_products SET name = ?, unit = ?, low_stock_threshold = ?,
				       purchase_price = coalesce(?, purchase_price), sale_price = coalesce(?, sale_price),
				       active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
				""", request.name().trim(), request.unit().trim(), request.lowStockThreshold(),
				request.purchasePrice(), request.salePrice(), request.active(), id);
		if (updated == 0) throw notFound("Inventory product not found.");
		if (image != null && !image.isEmpty()) saveProductImage(id, image);
		Product product = findProduct(id);
		notificationService.notifyInventoryProductChanged(id, "updated");
		return product;
	}

	@Transactional
	public void deleteProduct(UUID id) {
		Map<String, Object> product;
		try {
			product = jdbc.queryForMap("SELECT quantity FROM inventory_products WHERE id = ? FOR UPDATE", id);
		} catch (EmptyResultDataAccessException exception) {
			throw notFound("Inventory product not found.");
		}
		long movements = scalarLong("SELECT count(*) FROM inventory_movements WHERE product_id = ?", id);
		long soldItems = scalarLong("SELECT count(*) FROM booking_retail_items WHERE product_id = ?", id);
		if (((BigDecimal) product.get("quantity")).signum() != 0 || movements > 0 || soldItems > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"لا يمكن حذف منتج لديه رصيد أو حركات سابقة. أوقف المنتج من شاشة التعديل.");
		}
		jdbc.update("DELETE FROM inventory_products WHERE id = ?", id);
	}

	@Transactional(readOnly = true)
	public ProductImage productImage(UUID id) {
		List<ProductImage> images = jdbc.query("""
				SELECT image_content_type, image_data FROM inventory_products
				WHERE id = ? AND image_data IS NOT NULL
				""", (rs, row) -> new ProductImage(rs.getString("image_content_type"), rs.getBytes("image_data")), id);
		if (images.isEmpty()) throw notFound("Inventory product image not found.");
		return images.get(0);
	}

	@Transactional(readOnly = true)
	public List<InventoryMovementResponse> inventoryMovements(UUID productId) {
		return jdbc.query("""
				SELECT id, product_id, movement_type, quantity, unit_cost, unit_price, line_total, notes, created_at
				FROM inventory_movements WHERE product_id = ? ORDER BY created_at DESC LIMIT 200
				""", (rs, row) -> new InventoryMovementResponse(
						rs.getObject("id", UUID.class),
						rs.getObject("product_id", UUID.class),
						rs.getString("movement_type"),
						rs.getBigDecimal("quantity"),
						rs.getBigDecimal("unit_cost"),
						rs.getBigDecimal("unit_price"),
						rs.getBigDecimal("line_total"),
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
				request.dayQueueEnabled(), false, request.newBookingsDisabled());
		if (updatedSettings == 0) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Booking settings are not initialized.");
		}
		notificationService.notifySettingsChanged();
		return shopProfile();
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
				FROM booking_payments p JOIN bookings b ON b.id = p.booking_id
				JOIN customers c ON c.id = b.customer_id
				WHERE CASE WHEN b.customer_account_id IS NOT NULL THEN 'account:' || b.customer_account_id::text
				           WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				           THEN 'guest:phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				           ELSE 'guest:id:' || c.id::text END = ?
				ORDER BY p.paid_at DESC
				""", PAYMENT_ROW, groupKey);
	}

	private Product findProduct(UUID id) {
		return jdbc.queryForObject("""
				SELECT id, name, unit, quantity, low_stock_threshold, purchase_price, sale_price,
				       CASE WHEN image_data IS NOT NULL THEN '/api/admin/inventory/products/' || id::text || '/image' END AS image_url,
				       active
				FROM inventory_products WHERE id = ?
				""", (rs, row) -> new Product(id, rs.getString("name"), rs.getString("unit"),
				rs.getBigDecimal("quantity"), rs.getBigDecimal("low_stock_threshold"),
				rs.getBigDecimal("purchase_price"), rs.getBigDecimal("sale_price"),
				rs.getString("image_url"), rs.getBoolean("active")), id);
	}

	private void saveProductImage(UUID id, MultipartFile image) {
		if (image.getSize() > 2 * 1024 * 1024) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product image must be 2 MB or smaller.");
		}
		try {
			byte[] data = image.getBytes();
			if (data.length == 0 || data.length > 2 * 1024 * 1024) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product image must be 2 MB or smaller.");
			}
			String contentType = imageContentType(data);
			if (contentType == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PNG, JPEG, and WebP images are supported.");
			}
			int updated = jdbc.update("""
					UPDATE inventory_products
					SET image_data = ?, image_content_type = ?, updated_at = CURRENT_TIMESTAMP
					WHERE id = ?
					""", data, contentType, id);
			if (updated == 0) throw notFound("Inventory product not found.");
		} catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read product image.", exception);
		}
	}

	private String imageContentType(byte[] data) {
		if (data.length >= 8
				&& (data[0] & 0xff) == 0x89 && data[1] == 0x50 && data[2] == 0x4e
				&& data[3] == 0x47 && data[4] == 0x0d && data[5] == 0x0a
				&& data[6] == 0x1a && data[7] == 0x0a) return "image/png";
		if (data.length >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8
				&& (data[2] & 0xff) == 0xff) return "image/jpeg";
		if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
				&& data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return "image/webp";
		return null;
	}

	private UUID insertMovement(
			UUID productId,
			String type,
			BigDecimal quantity,
			BigDecimal unitCost,
			BigDecimal unitPrice,
			BigDecimal lineTotal,
			String notes,
			String actor) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO inventory_movements (id, product_id, movement_type, quantity, unit_cost, unit_price, line_total, notes, created_by)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, productId, type, quantity, unitCost, unitPrice, lineTotal, notes, actor);
		return id;
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

	private String customerGroupKey(UUID id, String type) {
		return queryString("""
				SELECT group_key FROM (
				    SELECT 'account:' || a.id::text AS group_key, 'ACCOUNTS' AS customer_type
				    FROM customer_accounts a WHERE a.id = ?
				    UNION ALL
				    SELECT CASE WHEN regexp_replace(coalesce(c.phone_number, ''), '[^0-9]', '', 'g') <> ''
				                THEN 'guest:phone:' || regexp_replace(c.phone_number, '[^0-9]', '', 'g')
				                ELSE 'guest:id:' || c.id::text END, 'GUESTS' AS customer_type
				    FROM customers c WHERE c.id = ? AND EXISTS (
				        SELECT 1 FROM bookings b WHERE b.customer_id = c.id AND b.customer_account_id IS NULL
				    )
			    UNION ALL
			    SELECT CASE WHEN md.customer_account_id IS NOT NULL THEN 'account:' || md.customer_account_id::text
			                WHEN regexp_replace(coalesce(md.phone_number, ''), '[^0-9]', '', 'g') <> ''
			                THEN 'guest:phone:' || regexp_replace(md.phone_number, '[^0-9]', '', 'g')
			                ELSE 'manual:id:' || md.id::text END, 'GUESTS' AS customer_type
			    FROM manual_customer_debts md WHERE md.id = ?
				) customer_identity
				WHERE (? IS NULL OR customer_type = ?)
				ORDER BY CASE WHEN customer_type = 'ACCOUNTS' THEN 0 ELSE 1 END
				LIMIT 1
			""", id, id, id, type, type);
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
