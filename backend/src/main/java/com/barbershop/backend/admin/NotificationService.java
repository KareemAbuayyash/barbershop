package com.barbershop.backend.admin;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Utils;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.barbershop.backend.admin.NotificationDtos.NotificationCenter;
import com.barbershop.backend.admin.NotificationDtos.NotificationItem;
import com.barbershop.backend.admin.NotificationDtos.NotificationPreferences;

@Service
public class NotificationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);
	private static final List<String> EVENT_TYPES = List.of(
			"BOOKING_CREATED",
			"BOOKING_CONFIRMED",
			"BOOKING_REJECTED",
			"BOOKING_CANCELLED",
			"BOOKING_RESCHEDULED",
			"BOOKING_PROPOSAL",
			"BOOKING_PROPOSAL_RESPONSE",
			"BOOKING_NOTE",
			"BOOKING_REMINDER",
			"QUEUE",
			"INVENTORY",
			"INVENTORY_LOW",
			"INVENTORY_OUT",
			"SALES",
			"PAYMENT",
			"PAYMENT_SETTLED",
			"PAYMENT_DUE",
			"FINANCE",
			"SERVICE",
			"SETTINGS",
			"DAILY_SUMMARY");
	private static final List<String> ALLOWED_PUSH_HOSTS = List.of(
			"fcm.googleapis.com",
			"updates.push.services.mozilla.com",
			"push.services.mozilla.com",
			"web.push.apple.com");
	private static final int MAX_DELIVERY_ATTEMPTS = 8;
	private static final int GUEST_ACCESS_TOKEN_BYTES = 32;
	private static final int GUEST_ACCESS_TOKEN_DAYS = 30;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final Set<String> CUSTOMER_PREFERENCE_TYPES = Set.of(
			"BOOKING_CREATED", "BOOKING_CONFIRMED", "BOOKING_REJECTED", "BOOKING_CANCELLED",
			"BOOKING_RESCHEDULED", "BOOKING_PROPOSAL", "BOOKING_PROPOSAL_RESPONSE", "BOOKING_NOTE", "BOOKING_REMINDER",
			"QUEUE", "PAYMENT_SETTLED", "PAYMENT_DUE");
	private static final Set<String> OWNER_PREFERENCE_TYPES = Set.copyOf(EVENT_TYPES);

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final String publicKey;
	private final String privateKey;
	private final String subject;
	private final boolean pushConfigured;

	public NotificationService(
			JdbcTemplate jdbc,
			ObjectMapper objectMapper,
			@Value("${barbershop.push.vapid-public-key:}") String publicKey,
			@Value("${barbershop.push.vapid-private-key:}") String privateKey,
			@Value("${barbershop.push.subject:mailto:owner@barberhouse.local}") String subject) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.publicKey = publicKey.trim();
		this.privateKey = privateKey.trim();
		this.subject = subject.trim();
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
		boolean validVapidKeys = !this.publicKey.isBlank() && !this.privateKey.isBlank();
		if (validVapidKeys) {
			try {
				validVapidKeys = Utils.verifyKeyPair(
						Utils.loadPrivateKey(this.privateKey), Utils.loadPublicKey(this.publicKey));
			} catch (Exception exception) {
				validVapidKeys = false;
				LOGGER.error("Web Push VAPID keys could not be loaded.", exception);
			}
			if (!validVapidKeys) {
				LOGGER.error("Web Push is disabled because the VAPID public and private keys do not match.");
			}
		}
		this.pushConfigured = validVapidKeys;
		if (this.publicKey.isBlank() || this.privateKey.isBlank()) {
			LOGGER.warn("Web Push is disabled because BARBERSHOP_PUSH_VAPID_PUBLIC_KEY and BARBERSHOP_PUSH_VAPID_PRIVATE_KEY are not configured.");
		} else if (!this.pushConfigured) {
			LOGGER.warn("Web Push is disabled because the configured VAPID key pair is invalid.");
		} else {
			LOGGER.info("Web Push is enabled; pending notifications will be delivered by the scheduler.");
		}
	}

	public Map<String, Object> publicConfiguration() {
		return Map.of("enabled", pushConfigured, "publicKey", pushConfigured ? publicKey : "");
	}

	@Transactional
	public void notifyOwner(String eventType, String eventKey, String title, String body, String path) {
		List<UUID> accountIds = jdbc.query("""
				SELECT id FROM management_accounts WHERE role = 'ROLE_OWNER' AND enabled = TRUE
				""", (rs, row) -> rs.getObject("id", UUID.class));
		String ownerTitle = "BOOKING_CREATED".equals(eventType) ? "حجز جديد" : title;
		for (UUID accountId : accountIds) {
			createNotification(new Recipient("OWNER", accountId), eventType, eventKey, ownerTitle, body, path);
		}
	}

	@Transactional
	public void notifyCustomer(UUID accountId, String eventType, String eventKey, String title, String body, String path) {
		if (accountId == null) return;
		String customerTitle = "BOOKING_CREATED".equals(eventType) ? "تم استلام طلب حجزك" : title;
		createNotification(new Recipient("CUSTOMER", accountId), eventType, eventKey, customerTitle, body, path);
	}

	@Transactional
	public void notifyGuest(UUID bookingId, String eventType, String eventKey, String title, String body) {
		if (bookingId == null) return;
		String guestTitle = "BOOKING_CREATED".equals(eventType) ? "تم استلام طلب حجزك" : title;
		createGuestNotification(bookingId, eventType, eventKey, guestTitle, body, "/guest-notifications");
	}

	@Transactional
	public void notifyDebtReminder(UUID bookingId, BigDecimal balance) {
		if (bookingId == null || balance == null || balance.signum() <= 0) return;
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "debt-reminder:" + bookingId + ":" + UUID.randomUUID();
		String body = "لديك مبلغ مستحق بقيمة " + balance.toPlainString() + " ₪ عن "
				+ booking.serviceName() + ". يرجى تسديده عند زيارتك القادمة.";
		notifyBookingCustomerOrGuest(booking, "PAYMENT_DUE", eventKey,
				"تذكير بمبلغ مستحق", body, "/my-bookings");
	}

	@Transactional
	public void notifyAccountDebtReminder(UUID accountId, BigDecimal balance) {
		if (accountId == null || balance == null || balance.signum() <= 0) return;
		String eventKey = "debt-reminder:" + accountId + ":" + UUID.randomUUID();
		String body = "إجمالي المبلغ المستحق عليك " + balance.toPlainString()
				+ " ₪. يرجى تسديده عند زيارتك القادمة.";
		notifyCustomer(accountId, "PAYMENT_DUE", eventKey,
				"تذكير بإجمالي المبلغ المستحق", body, "/my-bookings");
	}

	@Transactional
	public void notifyCustomerAggregatePayment(
			UUID accountId, UUID guestBookingId, BigDecimal paymentAmount, BigDecimal remainingBalance) {
		if (paymentAmount == null || paymentAmount.signum() <= 0 || remainingBalance == null) return;
		String eventType = remainingBalance.signum() == 0 ? "PAYMENT_SETTLED" : "PAYMENT_DUE";
		String title = remainingBalance.signum() == 0 ? "تم تسديد المبلغ بالكامل" : "تم تسجيل دفعة جزئية";
		String eventKey = "customer-payment:" + UUID.randomUUID();
		String body = "تم استلام دفعة " + paymentAmount.toPlainString() + " ₪. إجمالي المتبقي عليك "
				+ remainingBalance.toPlainString() + " ₪.";
		if (accountId != null) {
			notifyCustomer(accountId, eventType, eventKey, title, body, "/my-bookings");
		} else if (guestBookingId != null) {
			notifyGuest(guestBookingId, eventType, eventKey, title, body);
		}
	}

	@Transactional
	public void notifyBookingNote(UUID bookingId, String note) {
		if (bookingId == null || note == null || note.isBlank()) return;
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + ":owner-note:" + booking.updatedAt().toEpochMilli();
		notifyBookingCustomerOrGuest(booking, "BOOKING_NOTE", eventKey,
				"ملاحظة من المحل", note, "/my-bookings");
	}

	private void notifyBookingCustomerOrGuest(
			BookingNotificationDetails booking,
			String eventType,
			String eventKey,
			String title,
			String body,
			String customerPath) {
		if (booking.customerAccountId() == null) {
			notifyGuest(booking.id(), eventType, eventKey, title, body);
		} else {
			notifyCustomer(booking.customerAccountId(), eventType, eventKey, title, body, customerPath);
		}
	}

	@Transactional
	public String issueGuestAccessToken(UUID bookingId) {
		byte[] tokenBytes = new byte[GUEST_ACCESS_TOKEN_BYTES];
		SECURE_RANDOM.nextBytes(tokenBytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
		String tokenHash = hashGuestAccessToken(token);
		jdbc.update("""
				INSERT INTO guest_booking_access (booking_id, token_hash, expires_at)
				VALUES (?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 day'))
				""", bookingId, tokenHash, GUEST_ACCESS_TOKEN_DAYS);
		return token;
	}

	@Transactional(readOnly = true)
	public boolean isGuestBooking(UUID bookingId) {
		if (bookingId == null) return false;
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM bookings WHERE id = ? AND customer_account_id IS NULL)",
				Boolean.class, bookingId));
	}

	@Transactional
	public void notifyBookingCustomer(
			UUID bookingId,
			String eventType,
			String eventKey,
			String title,
			String body,
			String path) {
		List<UUID> accounts = jdbc.query("""
				SELECT customer_account_id FROM bookings
				WHERE id = ? AND customer_account_id IS NOT NULL
				""", (rs, row) -> rs.getObject("customer_account_id", UUID.class), bookingId);
		for (UUID accountId : accounts) {
			notifyCustomer(accountId, eventType, eventKey, title, body, path);
		}
	}

	public void notifyBookingCreated(UUID bookingId) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + ":created";
		String appointment = formatBookingTime(booking);
		notifyOwner("BOOKING_CREATED", eventKey, "طلب حجز جديد",
				booking.customerName() + " · " + booking.serviceName() + " · " + appointment
						+ customerNoteSummary(booking.customerNote()),
				"/owner/bookings");
		notifyBookingCustomerOrGuest(booking, "BOOKING_CREATED", eventKey,
				"تم استلام طلب حجزك",
				"رقم الحجز: " + booking.id() + " · " + booking.serviceName() + " · " + appointment,
				"/my-bookings");
	}

	private String customerNoteSummary(String note) {
		if (note == null || note.isBlank()) return "";
		String summary = note.trim();
		if (summary.length() > 160) summary = summary.substring(0, 157) + "…";
		return " · ملاحظة الزبون: " + summary;
	}

	public void notifyBookingConfirmed(UUID bookingId) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + ":confirmed:" + UUID.randomUUID();
		String body = booking.serviceName() + " · " + formatBookingTime(booking);
		notifyOwner("BOOKING_CONFIRMED", eventKey, "تم تأكيد الحجز",
				booking.customerName() + " · " + body, "/owner/bookings");
		notifyBookingCustomerOrGuest(booking, "BOOKING_CONFIRMED", eventKey,
				"تم تأكيد حجزك", body, "/my-bookings");
	}

	@Transactional
	public void notifyBookingIsApproaching(UUID bookingId) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found.");
		String eventKey = "booking:" + booking.id() + ":manual-reminder:" + UUID.randomUUID();
		String appointment = formatBookingTime(booking);
		String body = booking.serviceName() + (appointment.isBlank() ? "" : " · " + appointment)
				+ " · موعدك اقترب، بانتظار حضورك إلى المحل.";
		notifyBookingCustomerOrGuest(booking, "BOOKING_REMINDER", eventKey,
				"تذكير: موعدك اقترب", body, "/my-bookings");
	}

	@Transactional
	public void notifyUpcomingAppointmentReminder(UUID bookingId) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null || booking.startsAt() == null) return;
		String eventKey = "booking:" + booking.id() + ":reminder-30m:" + booking.startsAt().toEpochMilli();
		String body = booking.serviceName() + " · " + formatBookingTime(booking)
				+ " · موعدك بعد نصف ساعة.";
		notifyBookingCustomerOrGuest(booking, "BOOKING_REMINDER", eventKey,
				"تذكير: موعدك بعد نصف ساعة", body, "/my-bookings");
	}

	public void notifyBookingRejected(UUID bookingId) {
		notifyBookingLifecycle(bookingId, "BOOKING_REJECTED", "تم رفض الحجز",
				"تم رفض طلب الحجز.", ":rejected");
	}

	public void notifyBookingCancelled(UUID bookingId) {
		notifyBookingLifecycle(bookingId, "BOOKING_CANCELLED", "تم إلغاء الحجز",
				"تم إلغاء الحجز.", ":cancelled");
	}

	public void notifyBookingRescheduled(UUID bookingId) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + ":rescheduled:" + booking.updatedAt().toEpochMilli();
		String body = booking.serviceName() + " · " + formatBookingTime(booking);
		notifyOwner("BOOKING_RESCHEDULED", eventKey, "تم تعديل موعد الحجز",
				booking.customerName() + " · " + body, "/owner/bookings");
		notifyBookingCustomerOrGuest(booking, "BOOKING_RESCHEDULED", eventKey,
				"تم تعديل موعد حجزك", body, "/my-bookings");
	}

	@Transactional
	public void notifyCustomerRequestedReschedule(UUID bookingId, LocalDate preferredDate, Instant preferredTime, String reason) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + ":customer-reschedule:" + UUID.randomUUID();
		String requestedTime = preferredDate.toString()
				+ (preferredTime == null ? " (دون وقت محدد)" : " · "
				+ DateTimeFormatter.ofPattern("HH:mm").format(preferredTime.atZone(java.time.ZoneId.of("Asia/Hebron"))));
		String reasonText = reason == null || reason.isBlank() ? "" : " · السبب: " + reason.trim();
		notifyOwner("BOOKING_RESCHEDULED", eventKey, "طلب الزبون تأجيل الحجز",
				booking.customerName() + " طلب التأجيل إلى " + requestedTime + reasonText,
				"/owner/bookings");
		notifyBookingCustomerOrGuest(booking, "BOOKING_RESCHEDULED", eventKey,
				"تم استلام طلب التأجيل",
				"أصبح حجزك بانتظار التأكيد. طلبت الموعد " + requestedTime
						+ reasonText + ". سيؤكد المحل الموعد أو يقترح موعداً آخر.", "/my-bookings");
	}

	@Transactional
	public void notifyBookingAffectedByShopClosure(UUID bookingId, LocalDate closedDate) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + ":shop-closed:" + closedDate;
		String date = closedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
		notifyBookingCustomerOrGuest(booking, "BOOKING_RESCHEDULED", eventKey,
				"تغيير موعد الحجز",
				"المحل مغلق بتاريخ " + date + ". تم وضع حجزك بانتظار التأكيد، وسيحدد المالك موعداً جديداً.",
				"/my-bookings");
	}

	public void notifyBookingProposal(UUID bookingId) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + ":proposal:" + booking.updatedAt().toEpochMilli();
		notifyBookingCustomerOrGuest(booking, "BOOKING_PROPOSAL", eventKey,
				"يوجد موعد مقترح لحجزك",
				booking.serviceName() + " · " + formatBookingTime(booking) + " · يرجى قبول الموعد أو رفضه.",
				"/my-bookings");
	}

	public void notifyBookingProposalResponse(UUID bookingId, boolean accepted) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + ":proposal-response:" + booking.updatedAt().toEpochMilli();
		String title = accepted ? "وافق الزبون على الموعد المقترح" : "رفض الزبون الموعد المقترح";
		String body = booking.customerName() + " · " + booking.serviceName() + " · " + formatBookingTime(booking);
		notifyOwner("BOOKING_PROPOSAL_RESPONSE", eventKey, title, body, "/owner/bookings");
		notifyBookingCustomerOrGuest(booking, "BOOKING_PROPOSAL_RESPONSE", eventKey,
				accepted ? "تم تثبيت موعدك" : "تم رفض الموعد المقترح",
				accepted ? "تم تثبيت الموعد المقترح." : "تم رفض الموعد المقترح ويمكن للمالك اقتراح موعد آخر.",
				"/my-bookings");
	}

	public void notifyQueueUpdated(UUID bookingId) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + ":queue:" + booking.updatedAt().toEpochMilli();
		String body = booking.serviceName() + " · الدور " + booking.queuePosition() + " · " + formatBookingTime(booking);
		notifyOwner("QUEUE", eventKey, "تم تحديث قائمة الانتظار",
				booking.customerName() + " · " + body, "/owner/queue");
		notifyBookingCustomerOrGuest(booking, "QUEUE", eventKey,
				"تم تحديث دورك في الطابور", body, "/my-bookings");
	}

	public void notifyQueueApproaching(UUID bookingId) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null || booking.queuePosition() == null) return;
		String eventKey = "booking:" + booking.id() + ":queue-approaching:" + booking.queuePosition();
		String body = booking.serviceName() + " · الدور " + booking.queuePosition();
		notifyOwner("QUEUE", eventKey, "اقترب موعد أحد الحجوزات",
				booking.customerName() + " · " + body, "/owner/queue");
		notifyBookingCustomerOrGuest(booking, "QUEUE", eventKey,
				"اقترب دورك في الطابور", body + " · يرجى الاستعداد للحضور.", "/my-bookings");
	}

	public void notifyPaymentRecorded(UUID paymentId) {
		notifyPaymentRecorded(paymentId, true);
	}

	@Transactional
	public void notifyPaymentRecorded(UUID paymentId, boolean notifyCustomer) {
		PaymentNotificationDetails payment = findPaymentNotification(paymentId);
		if (payment == null) return;
		String eventKey = "payment:" + payment.id();
		String body = payment.customerName() + " · " + payment.amount().toPlainString() + " ₪ · المتبقي "
				+ payment.balance().toPlainString() + " ₪";
		notifyOwner("PAYMENT", eventKey, "تم تسجيل دفعة مالية", body, "/owner/payments");
		if (!notifyCustomer) return;
		String customerEvent = payment.balance().signum() == 0 ? "PAYMENT_SETTLED" : "PAYMENT_DUE";
		String customerTitle = payment.balance().signum() == 0 ? "تم تسديد الفاتورة بالكامل" : "تم تسجيل دفعتك";
		String customerBody = "الخدمة: " + payment.serviceName() + " · المتبقي " + payment.balance().toPlainString() + " ₪";
		if (payment.customerAccountId() == null) {
			notifyGuest(payment.bookingId(), customerEvent, eventKey, customerTitle, customerBody);
		} else {
			notifyCustomer(payment.customerAccountId(), customerEvent, eventKey, customerTitle, customerBody, "/my-bookings");
		}
	}

	public void notifyCheckoutCompleted(UUID bookingId) {
		InvoiceNotificationDetails invoice = findInvoiceNotification(bookingId);
		if (invoice == null) return;
		String eventKey = "invoice:" + bookingId + ":created";
		String body = invoice.customerName() + " · الإجمالي " + invoice.total().toPlainString()
				+ " ₪ · المدفوع " + invoice.paid().toPlainString() + " ₪";
		notifyOwner("SALES", eventKey, "تم إنشاء فاتورة جديدة", body, "/owner/reports");
		String customerEvent = invoice.balance().signum() == 0 ? "PAYMENT_SETTLED" : "PAYMENT_DUE";
		String customerTitle = invoice.balance().signum() == 0 ? "تم تسديد فاتورتك بالكامل" : "تم إنشاء فاتورتك";
		String customerBody = "الإجمالي " + invoice.total().toPlainString() + " ₪ · المتبقي " + invoice.balance().toPlainString() + " ₪";
		if (invoice.customerAccountId() == null) {
			notifyGuest(bookingId, customerEvent, eventKey, customerTitle, customerBody);
		} else {
			notifyCustomer(invoice.customerAccountId(), customerEvent, eventKey, customerTitle, customerBody, "/my-bookings");
		}
	}

	public void notifyExpenseCreated(UUID expenseId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT category, description, amount FROM shop_expenses WHERE id = ?
				""", expenseId);
		if (rows.isEmpty()) return;
		Map<String, Object> expense = rows.get(0);
		notifyOwner("FINANCE", "expense:" + expenseId, "تم تسجيل مصروف جديد",
				String.valueOf(expense.get("category")) + " · " + String.valueOf(expense.get("amount")) + " ₪ · "
						+ String.valueOf(expense.get("description")), "/owner/expenses");
	}

	public void notifyInventoryProductCreated(UUID productId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT name, quantity, unit FROM inventory_products WHERE id = ?
				""", productId);
		if (rows.isEmpty()) return;
		Map<String, Object> product = rows.get(0);
		notifyOwner("INVENTORY", "inventory-product:" + productId + ":created", "تمت إضافة منتج للمخزون",
				String.valueOf(product.get("name")) + " · الكمية " + formatQuantity(product.get("quantity")) + " "
						+ product.get("unit"), "/owner/inventory");
	}

	public void notifyInventoryProductChanged(UUID productId, String action) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT name, quantity, unit FROM inventory_products WHERE id = ?
				""", productId);
		if (rows.isEmpty()) return;
		Map<String, Object> product = rows.get(0);
		notifyOwner("INVENTORY", "inventory-product:" + productId + ":" + action + ":" + Instant.now().toEpochMilli(),
				"تم تحديث منتج في المخزون",
				String.valueOf(product.get("name")) + " · الكمية " + formatQuantity(product.get("quantity")) + " "
						+ product.get("unit") + " · " + action,
				"/owner/inventory");
	}

	public void notifyInventoryMovement(UUID productId, UUID movementId, String movementType) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT p.name, p.quantity, p.unit, m.quantity movement_quantity
				FROM inventory_products p JOIN inventory_movements m ON m.product_id = p.id
				WHERE p.id = ? AND m.id = ?
				""", productId, movementId);
		if (rows.isEmpty()) return;
		Map<String, Object> movement = rows.get(0);
		String eventType = "SALE".equals(movementType) ? "SALES" : "INVENTORY";
		String title = "SALE".equals(movementType) ? "تم تسجيل عملية بيع" : "تم تحديث كمية المخزون";
		notifyOwner(eventType, "inventory-movement:" + movementId, title,
				String.valueOf(movement.get("name")) + " · الكمية " + formatQuantity(movement.get("movement_quantity"))
						+ " · المتبقي " + formatQuantity(movement.get("quantity")) + " " + movement.get("unit"),
				"/owner/inventory");
	}

	public void notifyInventoryThreshold(UUID productId, UUID movementId, boolean outOfStock) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT name, quantity, unit FROM inventory_products WHERE id = ?
				""", productId);
		if (rows.isEmpty()) return;
		Map<String, Object> product = rows.get(0);
		String eventType = outOfStock ? "INVENTORY_OUT" : "INVENTORY_LOW";
		notifyOwner(eventType, "inventory-threshold:" + productId + ":" + movementId,
				outOfStock ? "نفد المنتج من المخزون" : "انخفاض مخزون المنتج",
				String.valueOf(product.get("name")) + " · المتبقي " + formatQuantity(product.get("quantity")) + " "
						+ product.get("unit"), "/owner/inventory");
	}

	private static String formatQuantity(Object value) {
		if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
		return String.valueOf(value);
	}

	public void notifyServiceChanged(UUID serviceId, String action) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT name, price FROM services WHERE id = ?
				""", serviceId);
		if (rows.isEmpty()) return;
		Map<String, Object> service = rows.get(0);
		notifyOwner("SERVICE", "service:" + serviceId + ":" + action + ":" + Instant.now().toEpochMilli(),
				"تم تحديث الخدمات والأسعار",
				String.valueOf(service.get("name")) + " · السعر " + service.get("price") + " ₪ · " + action,
				"/owner/services");
	}

	public void notifySettingsChanged() {
		notifyOwner("SETTINGS", "settings:" + Instant.now().toEpochMilli(), "تم تحديث إعدادات المحل",
				"تم تحديث إعدادات الحجوزات أو بيانات المحل.", "/owner/settings");
	}

	private void notifyBookingLifecycle(
			UUID bookingId,
			String eventType,
			String title,
			String customerBody,
			String keySuffix) {
		BookingNotificationDetails booking = findBookingNotification(bookingId);
		if (booking == null) return;
		String eventKey = "booking:" + booking.id() + keySuffix + ":" + booking.updatedAt().toEpochMilli();
		String body = booking.customerName() + " · " + booking.serviceName();
		notifyOwner(eventType, eventKey, title, body, "/owner/bookings");
		String customerTitle = switch (eventType) {
			case "BOOKING_REJECTED" -> "تم رفض طلب حجزك";
			case "BOOKING_CANCELLED" -> "تم إلغاء حجزك";
			default -> title;
		};
		notifyBookingCustomerOrGuest(booking, eventType, eventKey, customerTitle, customerBody, "/my-bookings");
	}

	private BookingNotificationDetails findBookingNotification(UUID bookingId) {
		return jdbc.query("""
				SELECT b.id, b.customer_account_id, b.notes,
				       coalesce(c.name, account.display_name, 'عميل') AS customer_name,
				       b.service_name_snapshot, b.starts_at, b.preferred_date,
				       b.queue_date, b.queue_position, b.updated_at
				FROM bookings b
				LEFT JOIN customers c ON c.id = b.customer_id
				LEFT JOIN customer_accounts account ON account.id = b.customer_account_id
				WHERE b.id = ?
				""", (rs, row) -> new BookingNotificationDetails(
				rs.getObject("id", UUID.class),
				rs.getObject("customer_account_id", UUID.class),
					rs.getString("notes"),
				rs.getString("customer_name"),
				rs.getString("service_name_snapshot"),
				rs.getTimestamp("starts_at") == null ? null : rs.getTimestamp("starts_at").toInstant(),
				rs.getObject("preferred_date", LocalDate.class),
				rs.getObject("queue_date", LocalDate.class),
				rs.getObject("queue_position", Integer.class),
				rs.getTimestamp("updated_at").toInstant()), bookingId)
			.stream()
			.findFirst()
			.orElse(null);
	}

	private PaymentNotificationDetails findPaymentNotification(UUID paymentId) {
		return jdbc.query("""
				SELECT p.id, p.amount, b.id AS booking_id, b.customer_account_id,
				       coalesce(c.name, account.display_name, 'عميل') AS customer_name,
				       b.service_name_snapshot,
				       greatest(
				           b.service_price_snapshot + coalesce(ri.retail_total, 0)
				           - coalesce((SELECT sum(amount) FROM booking_payments WHERE booking_id = b.id), 0),
				           0) AS balance
				FROM booking_payments p
				JOIN bookings b ON b.id = p.booking_id
				LEFT JOIN customers c ON c.id = b.customer_id
				LEFT JOIN customer_accounts account ON account.id = b.customer_account_id
				LEFT JOIN (
				    SELECT booking_id, sum(line_total) retail_total
				    FROM booking_retail_items GROUP BY booking_id
				) ri ON ri.booking_id = b.id
				WHERE p.id = ?
				""", (rs, row) -> new PaymentNotificationDetails(
				rs.getObject("id", UUID.class),
				rs.getBigDecimal("amount"),
				rs.getObject("booking_id", UUID.class),
				rs.getObject("customer_account_id", UUID.class),
				rs.getString("customer_name"),
				rs.getString("service_name_snapshot"),
				rs.getBigDecimal("balance")), paymentId)
			.stream()
			.findFirst()
			.orElse(null);
	}

	private InvoiceNotificationDetails findInvoiceNotification(UUID bookingId) {
		return jdbc.query("""
				SELECT b.customer_account_id,
				       coalesce(c.name, account.display_name, 'عميل') AS customer_name,
				       b.service_price_snapshot + coalesce(ri.retail_total, 0) AS total,
				       coalesce(p.paid, 0) AS paid,
				       greatest(
				           b.service_price_snapshot + coalesce(ri.retail_total, 0) - coalesce(p.paid, 0),
				           0) AS balance
				FROM bookings b
				LEFT JOIN customers c ON c.id = b.customer_id
				LEFT JOIN customer_accounts account ON account.id = b.customer_account_id
				LEFT JOIN (
				    SELECT booking_id, sum(line_total) retail_total
				    FROM booking_retail_items GROUP BY booking_id
				) ri ON ri.booking_id = b.id
				LEFT JOIN (
				    SELECT booking_id, sum(amount) paid
				    FROM booking_payments GROUP BY booking_id
				) p ON p.booking_id = b.id
				WHERE b.id = ?
				""", (rs, row) -> new InvoiceNotificationDetails(
				rs.getObject("customer_account_id", UUID.class),
				rs.getString("customer_name"),
				rs.getBigDecimal("total"),
				rs.getBigDecimal("paid"),
				rs.getBigDecimal("balance")), bookingId)
			.stream()
			.findFirst()
			.orElse(null);
	}

	private String formatBookingTime(BookingNotificationDetails booking) {
		if (booking.queueDate() != null) {
			return "الدور " + booking.queuePosition() + " بتاريخ " + booking.queueDate();
		}
		if (booking.startsAt() != null) {
			return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
					.format(booking.startsAt().atZone(java.time.ZoneId.of("Asia/Hebron")));
		}
		if (booking.preferredDate() != null) {
			return "التاريخ المقترح " + booking.preferredDate();
		}
		return "بانتظار تحديد الموعد";
	}

	private void createGuestNotification(
			UUID bookingId,
			String eventType,
			String eventKey,
			String title,
			String body,
			String path) {
		if (!EVENT_TYPES.contains(eventType)) {
			throw new IllegalArgumentException("Unsupported notification event type.");
		}
		if (eventKey == null || eventKey.isBlank() || eventKey.length() > 180) {
			throw new IllegalArgumentException("Notification event key must contain 1-180 characters.");
		}
		UUID notificationId = UUID.randomUUID();
		int inserted = jdbc.update("""
				INSERT INTO app_notifications (
				    id, recipient_type, owner_account_id, customer_account_id, guest_booking_id,
				    event_type, event_key, title, body, target_path
				)
				VALUES (?, 'GUEST', NULL, NULL, ?, ?, ?, ?, ?, ?)
				ON CONFLICT DO NOTHING
				""", notificationId, bookingId, eventType, eventKey, limited(title, 150), limited(body, 500), normalizePath(path));
		if (inserted == 0) return;
		jdbc.update("""
				INSERT INTO notification_push_deliveries (id, notification_id, subscription_id)
				SELECT gen_random_uuid(), ?, subscription.id
				FROM notification_subscriptions subscription
				WHERE subscription.guest_booking_id = ? AND subscription.enabled = TRUE
				ON CONFLICT (notification_id, subscription_id) DO NOTHING
				""", notificationId, bookingId);
	}

	private void createNotification(
			Recipient recipient,
			String eventType,
			String eventKey,
			String title,
			String body,
			String path) {
		if (!EVENT_TYPES.contains(eventType)) {
			throw new IllegalArgumentException("Unsupported notification event type.");
		}
		if (eventKey == null || eventKey.isBlank() || eventKey.length() > 180) {
			throw new IllegalArgumentException("Notification event key must contain 1-180 characters.");
		}
		String safeTitle = limited(title, 150);
		String safeBody = limited(body, 500);
		String safePath = normalizePath(path);
		UUID notificationId = UUID.randomUUID();
		int inserted = jdbc.update("""
				INSERT INTO app_notifications (
				    id, recipient_type, owner_account_id, customer_account_id, guest_booking_id,
				    event_type, event_key, title, body, target_path
				)
				VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
				ON CONFLICT DO NOTHING
				""", notificationId, recipient.type(),
				recipient.type().equals("OWNER") ? recipient.id() : null,
				recipient.type().equals("CUSTOMER") ? recipient.id() : null,
				eventType, eventKey, safeTitle, safeBody, safePath);
		if (inserted == 0) return;

		jdbc.update("""
				INSERT INTO notification_push_deliveries (id, notification_id, subscription_id)
				SELECT gen_random_uuid(), ?, subscription.id
				FROM notification_subscriptions subscription
				LEFT JOIN notification_preferences preference
				  ON preference.event_type = ?
				 AND ((? = 'OWNER' AND preference.owner_account_id = ?)
				      OR (? = 'CUSTOMER' AND preference.customer_account_id = ?))
				WHERE subscription.enabled = TRUE
				  AND ((? = 'OWNER' AND subscription.owner_account_id = ?)
				       OR (? = 'CUSTOMER' AND subscription.customer_account_id = ?))
				  AND coalesce(preference.push_enabled, TRUE) = TRUE
				ON CONFLICT (notification_id, subscription_id) DO NOTHING
				""", notificationId, eventType, recipient.type(), recipient.id(), recipient.type(), recipient.id(),
				recipient.type(), recipient.id(), recipient.type(), recipient.id());
	}

	@Transactional(readOnly = true)
	public NotificationCenter listNotifications(String username, boolean owner, int page, int requestedSize) {
		int safePage = Math.max(page, 0);
		int size = Math.max(1, Math.min(requestedSize, 50));
		Recipient recipient = resolveRecipient(username, owner);
		String predicate = owner ? "owner_account_id = ?" : "customer_account_id = ?";
		long total = jdbc.queryForObject(
				"SELECT count(*) FROM app_notifications WHERE " + predicate,
				Long.class, recipient.id());
		long unread = jdbc.queryForObject(
				"SELECT count(*) FROM app_notifications WHERE " + predicate + " AND read_at IS NULL",
				Long.class, recipient.id());
		List<NotificationItem> content = jdbc.query("""
				SELECT id, event_type, title, body, target_path, created_at, read_at
				FROM app_notifications WHERE %s
				ORDER BY created_at DESC, id DESC
				OFFSET ? LIMIT ?
				""".formatted(predicate), (rs, row) -> new NotificationItem(
				rs.getObject("id", UUID.class),
				rs.getString("event_type"),
				rs.getString("title"),
				rs.getString("body"),
				rs.getString("target_path"),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()),
				recipient.id(), (long) safePage * size, size);
		return new NotificationCenter(content, unread, safePage, size, total,
				(int) Math.ceil((double) total / size));
	}

	@Transactional
	public void markRead(String username, boolean owner, UUID notificationId) {
		Recipient recipient = resolveRecipient(username, owner);
		String predicate = owner ? "owner_account_id = ?" : "customer_account_id = ?";
		int updated = jdbc.update("""
				UPDATE app_notifications SET read_at = coalesce(read_at, CURRENT_TIMESTAMP)
				WHERE id = ? AND %s
				""".formatted(predicate), notificationId, recipient.id());
		if (updated == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found.");
		}
	}

	@Transactional
	public void markAllRead(String username, boolean owner) {
		Recipient recipient = resolveRecipient(username, owner);
		String predicate = owner ? "owner_account_id = ?" : "customer_account_id = ?";
		jdbc.update("""
				UPDATE app_notifications SET read_at = CURRENT_TIMESTAMP
				WHERE %s AND read_at IS NULL
				""".formatted(predicate), recipient.id());
	}

	@Transactional(readOnly = true)
	public NotificationPreferences preferences(String username, boolean owner) {
		Recipient recipient = resolveRecipient(username, owner);
		Map<String, Boolean> preferences = new LinkedHashMap<>();
		preferenceTypes(owner).forEach(type -> preferences.put(type, Boolean.TRUE));
		String predicate = owner ? "owner_account_id = ?" : "customer_account_id = ?";
		jdbc.query("""
				SELECT event_type, push_enabled FROM notification_preferences WHERE %s
				""".formatted(predicate),
				(rs, row) -> Map.entry(rs.getString("event_type"), rs.getBoolean("push_enabled")),
				recipient.id())
				.forEach(entry -> preferences.put(entry.getKey(), entry.getValue()));
		return new NotificationPreferences(preferences);
	}

	@Transactional
	public NotificationPreferences updatePreferences(
			String username,
			boolean owner,
			NotificationPreferences request) {
		if (request == null || request.preferences() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notification preferences are required.");
		}
		Recipient recipient = resolveRecipient(username, owner);
		for (Map.Entry<String, Boolean> preference : request.preferences().entrySet()) {
			if (!preferenceTypes(owner).contains(preference.getKey()) || preference.getValue() == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification preference.");
			}
			if (owner) {
				jdbc.update("""
						INSERT INTO notification_preferences (owner_account_id, event_type, push_enabled)
						VALUES (?, ?, ?)
						ON CONFLICT (owner_account_id, event_type) WHERE owner_account_id IS NOT NULL
						DO UPDATE SET push_enabled = EXCLUDED.push_enabled, updated_at = CURRENT_TIMESTAMP
						""", recipient.id(), preference.getKey(), preference.getValue());
			} else {
				jdbc.update("""
						INSERT INTO notification_preferences (customer_account_id, event_type, push_enabled)
						VALUES (?, ?, ?)
						ON CONFLICT (customer_account_id, event_type) WHERE customer_account_id IS NOT NULL
						DO UPDATE SET push_enabled = EXCLUDED.push_enabled, updated_at = CURRENT_TIMESTAMP
						""", recipient.id(), preference.getKey(), preference.getValue());
			}
		}
		return preferences(username, owner);
	}

	@Transactional
	public void saveSubscription(String username, boolean owner, String endpoint, String p256dh, String auth) {
		validateEndpointAndKeys(endpoint, p256dh, auth);
		Recipient recipient = resolveRecipient(username, owner);
		if (owner) {
			jdbc.update("""
					INSERT INTO notification_subscriptions (
					    id, endpoint, p256dh_key, auth_key, owner_account_id, enabled
					)
					VALUES (?, ?, ?, ?, ?, TRUE)
					ON CONFLICT (owner_account_id, endpoint) WHERE owner_account_id IS NOT NULL
					DO UPDATE SET p256dh_key = EXCLUDED.p256dh_key,
					              auth_key = EXCLUDED.auth_key,
					              enabled = TRUE,
					              updated_at = CURRENT_TIMESTAMP
					""", UUID.randomUUID(), endpoint, p256dh, auth, recipient.id());
		} else {
			jdbc.update("""
					INSERT INTO notification_subscriptions (
					    id, endpoint, p256dh_key, auth_key, customer_account_id, enabled
					)
					VALUES (?, ?, ?, ?, ?, TRUE)
					ON CONFLICT (customer_account_id, endpoint) WHERE customer_account_id IS NOT NULL
					DO UPDATE SET p256dh_key = EXCLUDED.p256dh_key,
					              auth_key = EXCLUDED.auth_key,
					              enabled = TRUE,
					              updated_at = CURRENT_TIMESTAMP
					""", UUID.randomUUID(), endpoint, p256dh, auth, recipient.id());
		}
	}

	@Transactional
	public void removeSubscription(String username, boolean owner, String endpoint) {
		validateEndpoint(endpoint);
		Recipient recipient = resolveRecipient(username, owner);
		int updated = jdbc.update("""
				DELETE FROM notification_subscriptions
				WHERE endpoint = ?
				  AND ((? = TRUE AND owner_account_id = ?)
				       OR (? = FALSE AND customer_account_id = ?))
				""", endpoint, owner, recipient.id(), owner, recipient.id());
		if (updated == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Push subscription not found for this account.");
		}
	}

	@Transactional
	public void removeDeviceSubscription(String endpoint) {
		validateEndpoint(endpoint);
		// Logout removes account subscriptions from this browser, while preserving
		// guest booking subscriptions attached to the same browser endpoint.
		jdbc.update("""
				DELETE FROM notification_subscriptions
				WHERE endpoint = ? AND (owner_account_id IS NOT NULL OR customer_account_id IS NOT NULL)
				""", endpoint);
	}

	@Transactional
	public void setSubscriptionEnabled(String username, boolean owner, String endpoint, boolean enabled) {
		validateEndpoint(endpoint);
		Recipient recipient = resolveRecipient(username, owner);
		int updated = jdbc.update("""
				UPDATE notification_subscriptions
				SET enabled = ?, updated_at = CURRENT_TIMESTAMP
				WHERE endpoint = ?
				  AND ((? = TRUE AND owner_account_id = ?)
				       OR (? = FALSE AND customer_account_id = ?))
				""", enabled, endpoint, owner, recipient.id(), owner, recipient.id());
		if (updated == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Push subscription not found for this account.");
		}
		if (!enabled) {
			jdbc.update("""
					UPDATE notification_push_deliveries delivery
					SET delivered_at = CURRENT_TIMESTAMP, claimed_until = NULL
					FROM notification_subscriptions subscription
					WHERE delivery.subscription_id = subscription.id
					  AND subscription.endpoint = ?
					  AND ((? = TRUE AND subscription.owner_account_id = ?)
					       OR (? = FALSE AND subscription.customer_account_id = ?))
					  AND delivery.delivered_at IS NULL
					""", endpoint, owner, recipient.id(), owner, recipient.id());
		}
	}

	@Transactional(readOnly = true)
	public boolean subscriptionEnabled(String username, boolean owner, String endpoint) {
		validateEndpoint(endpoint);
		Recipient recipient = resolveRecipient(username, owner);
		Boolean enabled = jdbc.query("""
				SELECT enabled FROM notification_subscriptions
				WHERE endpoint = ?
				  AND ((? = TRUE AND owner_account_id = ?)
				       OR (? = FALSE AND customer_account_id = ?))
				""", (rs, row) -> rs.getBoolean("enabled"), endpoint, owner, recipient.id(), owner, recipient.id())
				.stream()
				.findFirst()
				.orElse(false);
		return enabled;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> guestConfiguration(String token) {
		UUID bookingId = resolveGuestBooking(token);
		Map<String, Object> configuration = new LinkedHashMap<>(publicConfiguration());
		configuration.put("bookingId", bookingId.toString());
		return configuration;
	}

	@Transactional(readOnly = true)
	public NotificationCenter listGuestNotifications(String token, int page, int requestedSize) {
		UUID bookingId = resolveGuestBooking(token);
		int safePage = Math.max(page, 0);
		int size = Math.max(1, Math.min(requestedSize, 50));
		long total = jdbc.queryForObject(
				"SELECT count(*) FROM app_notifications WHERE recipient_type = 'GUEST' AND guest_booking_id = ?",
				Long.class, bookingId);
		long unread = jdbc.queryForObject(
				"SELECT count(*) FROM app_notifications WHERE recipient_type = 'GUEST' AND guest_booking_id = ? AND read_at IS NULL",
				Long.class, bookingId);
		List<NotificationItem> content = jdbc.query("""
				SELECT id, event_type, title, body, target_path, created_at, read_at
				FROM app_notifications
				WHERE recipient_type = 'GUEST' AND guest_booking_id = ?
				ORDER BY created_at DESC, id DESC
				OFFSET ? LIMIT ?
				""", (rs, row) -> new NotificationItem(
				rs.getObject("id", UUID.class),
				rs.getString("event_type"),
				rs.getString("title"),
				rs.getString("body"),
				rs.getString("target_path"),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()),
				bookingId, (long) safePage * size, size);
		return new NotificationCenter(content, unread, safePage, size, total,
				(int) Math.ceil((double) total / size));
	}

	@Transactional
	public void markGuestRead(String token, UUID notificationId) {
		UUID bookingId = resolveGuestBooking(token);
		int updated = jdbc.update("""
				UPDATE app_notifications SET read_at = coalesce(read_at, CURRENT_TIMESTAMP)
				WHERE id = ? AND recipient_type = 'GUEST' AND guest_booking_id = ?
				""", notificationId, bookingId);
		if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found.");
	}

	@Transactional
	public void markAllGuestRead(String token) {
		UUID bookingId = resolveGuestBooking(token);
		jdbc.update("""
				UPDATE app_notifications SET read_at = CURRENT_TIMESTAMP
				WHERE recipient_type = 'GUEST' AND guest_booking_id = ? AND read_at IS NULL
				""", bookingId);
	}

	@Transactional
	public void saveGuestSubscription(String token, String endpoint, String p256dh, String auth) {
		validateEndpointAndKeys(endpoint, p256dh, auth);
		UUID bookingId = resolveGuestBooking(token);
		jdbc.update("""
				INSERT INTO notification_subscriptions (
				    id, endpoint, p256dh_key, auth_key, guest_booking_id, enabled
				)
				VALUES (?, ?, ?, ?, ?, TRUE)
				ON CONFLICT (guest_booking_id, endpoint) WHERE guest_booking_id IS NOT NULL
				DO UPDATE SET
				    p256dh_key = EXCLUDED.p256dh_key,
				    auth_key = EXCLUDED.auth_key,
				    enabled = TRUE,
				    updated_at = CURRENT_TIMESTAMP
				""", UUID.randomUUID(), endpoint, p256dh, auth, bookingId);
		queueExistingGuestNotifications(bookingId, endpoint);
	}

	@Transactional
	public void removeGuestSubscription(String token, String endpoint) {
		validateEndpoint(endpoint);
		UUID bookingId = resolveGuestBooking(token);
		int updated = jdbc.update("""
				DELETE FROM notification_subscriptions
				WHERE endpoint = ? AND guest_booking_id = ?
				""", endpoint, bookingId);
		if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Push subscription not found.");
	}

	@Transactional
	public void setGuestSubscriptionEnabled(String token, String endpoint, boolean enabled) {
		validateEndpoint(endpoint);
		UUID bookingId = resolveGuestBooking(token);
		int updated = jdbc.update("""
				UPDATE notification_subscriptions
				SET enabled = ?, updated_at = CURRENT_TIMESTAMP
				WHERE endpoint = ? AND guest_booking_id = ?
				""", enabled, endpoint, bookingId);
		if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Push subscription not found.");
		if (!enabled) {
			jdbc.update("""
					UPDATE notification_push_deliveries delivery
					SET delivered_at = CURRENT_TIMESTAMP, claimed_until = NULL
					FROM notification_subscriptions subscription
					WHERE delivery.subscription_id = subscription.id
					  AND subscription.endpoint = ?
					  AND subscription.guest_booking_id = ?
					  AND delivery.delivered_at IS NULL
					""", endpoint, bookingId);
		}
	}

	@Transactional(readOnly = true)
	public boolean guestSubscriptionEnabled(String token, String endpoint) {
		validateEndpoint(endpoint);
		UUID bookingId = resolveGuestBooking(token);
		return jdbc.query("""
				SELECT enabled FROM notification_subscriptions
				WHERE endpoint = ? AND guest_booking_id = ?
				""", (rs, row) -> rs.getBoolean("enabled"), endpoint, bookingId)
				.stream().findFirst().orElse(false);
	}

	private void queueExistingGuestNotifications(UUID bookingId, String endpoint) {
		jdbc.update("""
				INSERT INTO notification_push_deliveries (id, notification_id, subscription_id)
				SELECT gen_random_uuid(), notification.id, subscription.id
				FROM app_notifications notification
				JOIN notification_subscriptions subscription
				  ON subscription.guest_booking_id = notification.guest_booking_id
				WHERE notification.recipient_type = 'GUEST'
				  AND notification.guest_booking_id = ?
				  AND subscription.endpoint = ?
				  AND subscription.enabled = TRUE
				ON CONFLICT (notification_id, subscription_id) DO NOTHING
				""", bookingId, endpoint);
	}

	private UUID resolveGuestBooking(String token) {
		if (token == null || !token.matches("[A-Za-z0-9_-]{40,100}")) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Guest booking access token is invalid.");
		}
		UUID bookingId = jdbc.query("""
				SELECT booking_id FROM guest_booking_access
				WHERE token_hash = ? AND expires_at > CURRENT_TIMESTAMP
				""", (rs, row) -> rs.getObject("booking_id", UUID.class), hashGuestAccessToken(token))
				.stream().findFirst().orElse(null);
		if (bookingId == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Guest booking access token is expired or invalid.");
		}
		return bookingId;
	}

	private String hashGuestAccessToken(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	@Scheduled(fixedDelay = 30_000)
	@Transactional
	public void notifyUpcomingBookings() {
		List<UUID> bookingIds = jdbc.query("""
				SELECT id FROM bookings
				WHERE status = 'CONFIRMED'
				  AND starts_at > CURRENT_TIMESTAMP + INTERVAL '29 minutes'
				  AND starts_at <= CURRENT_TIMESTAMP + INTERVAL '30 minutes'
				ORDER BY starts_at
				""", (rs, row) -> rs.getObject("id", UUID.class));
		bookingIds.forEach(this::notifyUpcomingAppointmentReminder);
	}

	@Scheduled(cron = "0 5 0 * * *", zone = "Asia/Hebron")
	@Transactional
	public void sendDailyOwnerSummary() {
		java.time.LocalDate yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Hebron")).minusDays(1);
		java.time.Instant start = yesterday.atStartOfDay(java.time.ZoneId.of("Asia/Hebron")).toInstant();
		java.time.Instant end = yesterday.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Hebron")).toInstant();
		Long bookings = jdbc.queryForObject("""
				SELECT count(*) FROM bookings
				WHERE created_at >= ? AND created_at < ?
				""", Long.class, start, end);
		java.math.BigDecimal collected = jdbc.queryForObject("""
				SELECT coalesce(sum(amount), 0) FROM booking_payments
				WHERE paid_at >= ? AND paid_at < ?
				""", java.math.BigDecimal.class, start, end);
		notifyOwner("DAILY_SUMMARY", "daily-summary:" + yesterday,
				"ملخص المحل · " + yesterday,
				"الحجوزات " + bookings + " · التحصيل " + collected.toPlainString() + " ₪",
				"/owner/reports");
	}

	@Scheduled(fixedDelay = 60_000)
	@Transactional
	public void notifyApproachingQueue() {
		List<UUID> bookingIds = jdbc.query("""
				SELECT id FROM bookings
				WHERE status IN ('PENDING', 'CONFIRMED')
				  AND queue_date = CURRENT_DATE
				  AND queue_position <= 2
				""", (rs, row) -> rs.getObject("id", UUID.class));
		bookingIds.forEach(this::notifyQueueApproaching);
	}

	@Scheduled(cron = "0 20 3 * * *", zone = "Asia/Hebron")
	@Transactional
	public void cleanupNotificationData() {
		jdbc.update("DELETE FROM app_notifications WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '180 days'");
		jdbc.update("DELETE FROM guest_booking_access WHERE expires_at < CURRENT_TIMESTAMP");
		jdbc.update("""
				DELETE FROM notification_subscriptions
				WHERE enabled = FALSE
				  AND updated_at < CURRENT_TIMESTAMP - INTERVAL '90 days'
				""");
	}

	@Scheduled(fixedDelay = 5_000)
	public void deliverPendingPushNotifications() {
		if (!pushConfigured) return;
		List<Delivery> pending = claimDueDeliveries();
		if (!pending.isEmpty()) {
			LOGGER.info("Delivering {} pending Web Push notification(s).", pending.size());
		}
		for (Delivery delivery : pending) {
			deliver(delivery);
		}
	}

	private List<Delivery> claimDueDeliveries() {
		List<UUID> ids = jdbc.query("""
				SELECT id FROM notification_push_deliveries
				WHERE delivered_at IS NULL
				  AND attempts < ?
				  AND next_attempt_at <= CURRENT_TIMESTAMP
				  AND (claimed_until IS NULL OR claimed_until < CURRENT_TIMESTAMP)
				ORDER BY next_attempt_at, created_at
				LIMIT 20
				""", (rs, row) -> rs.getObject("id", UUID.class), MAX_DELIVERY_ATTEMPTS);
		java.util.ArrayList<Delivery> claimed = new java.util.ArrayList<>();
		for (UUID id : ids) {
			int updated = jdbc.update("""
					UPDATE notification_push_deliveries
					SET claimed_until = CURRENT_TIMESTAMP + INTERVAL '2 minutes',
					    attempts = attempts + 1
					WHERE id = ? AND delivered_at IS NULL
					  AND (claimed_until IS NULL OR claimed_until < CURRENT_TIMESTAMP)
					""", id);
			if (updated == 0) continue;
			List<Delivery> rows = jdbc.query("""
					SELECT delivery.id, delivery.notification_id, notification.guest_booking_id,
					       delivery.attempts, notification.title, notification.body,
					       notification.target_path, subscription.endpoint,
					       subscription.p256dh_key, subscription.auth_key
					FROM notification_push_deliveries delivery
					JOIN app_notifications notification ON notification.id = delivery.notification_id
					JOIN notification_subscriptions subscription ON subscription.id = delivery.subscription_id
					WHERE delivery.id = ? AND subscription.enabled = TRUE
					""", (rs, row) -> new Delivery(
					rs.getObject("id", UUID.class),
					rs.getObject("notification_id", UUID.class),
					rs.getObject("guest_booking_id", UUID.class),
					rs.getInt("attempts"),
					rs.getString("title"),
					rs.getString("body"),
					rs.getString("target_path"),
					rs.getString("endpoint"),
					rs.getString("p256dh_key"),
					rs.getString("auth_key")), id);
			if (rows.isEmpty()) {
				jdbc.update("UPDATE notification_push_deliveries SET delivered_at = CURRENT_TIMESTAMP WHERE id = ?", id);
			} else {
				claimed.add(rows.get(0));
			}
		}
		return claimed;
	}

	private void deliver(Delivery delivery) {
		try {
			Map<String, Object> payloadData = new LinkedHashMap<>();
			payloadData.put("id", delivery.notificationId().toString());
			payloadData.put("title", delivery.title());
			payloadData.put("body", delivery.body());
			payloadData.put("path", delivery.targetPath());
			if (delivery.guestBookingId() != null) {
				payloadData.put("guestBookingId", delivery.guestBookingId().toString());
			}
			String payload = objectMapper.writeValueAsString(payloadData);
			PushService pushService = new PushService(publicKey, privateKey, subject);
			HttpResponse response = pushService.send(new Notification(
					delivery.endpoint(), delivery.p256dh(), delivery.auth(),
					payload.getBytes(StandardCharsets.UTF_8), 3600));
			int status = response.getStatusLine().getStatusCode();
			String responseDetail = "";
			if (status < 200 || status >= 300) {
				try {
					responseDetail = response.getEntity() == null
							? ""
							: EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
					if (responseDetail.length() > 300) responseDetail = responseDetail.substring(0, 300);
				} catch (Exception ignored) {
					responseDetail = "unreadable response body";
				}
			}
			if (status >= 200 && status < 300) {
				jdbc.update("""
						UPDATE notification_push_deliveries
						SET delivered_at = CURRENT_TIMESTAMP, claimed_until = NULL, last_error = NULL
						WHERE id = ?
						""", delivery.id());
				LOGGER.info("Web Push notification {} accepted by {}.", delivery.notificationId(),
						URI.create(delivery.endpoint()).getHost());
			} else if (status == 403 && isVapidSubscriptionMismatch(responseDetail)) {
				jdbc.update("""
						UPDATE notification_subscriptions
						SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP
						WHERE endpoint = ?
						""", delivery.endpoint());
				jdbc.update("""
						UPDATE notification_push_deliveries delivery
						SET delivered_at = CURRENT_TIMESTAMP, claimed_until = NULL, last_error = 'VAPID_MISMATCH'
						FROM notification_subscriptions subscription
						WHERE delivery.subscription_id = subscription.id
						  AND subscription.endpoint = ?
						  AND delivery.delivered_at IS NULL
						""", delivery.endpoint());
				LOGGER.warn("Disabled a Web Push subscription that was created with different VAPID credentials.");
			} else if (status == 404 || status == 410) {
				jdbc.update("DELETE FROM notification_subscriptions WHERE endpoint = ?", delivery.endpoint());
				LOGGER.warn("Web Push subscription for notification {} was rejected and removed (HTTP {}): {}.",
						delivery.notificationId(), status, responseDetail);
			} else {
				retry(delivery, "HTTP_" + status);
				LOGGER.warn("Web Push notification {} returned HTTP {} from {}: {}.", delivery.notificationId(), status,
						URI.create(delivery.endpoint()).getHost(), responseDetail);
			}
		} catch (Exception exception) {
			String error = exception instanceof JacksonException
					? "PAYLOAD_SERIALIZATION"
					: exception.getClass().getSimpleName();
			retry(delivery, error);
			LOGGER.warn("Push delivery {} failed: {}.", delivery.id(), error);
		}
	}

	private void retry(Delivery delivery, String error) {
		if (delivery.attempts() >= MAX_DELIVERY_ATTEMPTS) {
			jdbc.update("""
					UPDATE notification_push_deliveries
					SET claimed_until = NULL, last_error = ?
					WHERE id = ?
					""", error, delivery.id());
			return;
		}
		long delaySeconds = Math.min(3600, 30L << Math.min(delivery.attempts() - 1, 7));
		jdbc.update("""
				UPDATE notification_push_deliveries
				SET claimed_until = NULL,
				    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
				    last_error = ?
				WHERE id = ?
				""", delaySeconds, error, delivery.id());
	}

	private static boolean isVapidSubscriptionMismatch(String responseDetail) {
		String normalized = responseDetail.toLowerCase(Locale.ROOT);
		return normalized.contains("vapid credentials")
				&& normalized.contains("do not correspond to the credentials used to create the subscription");
	}

	private Recipient resolveRecipient(String username, boolean owner) {
		String table = owner ? "management_accounts" : "customer_accounts";
		String roleClause = owner ? " AND role = 'ROLE_OWNER'" : "";
		List<UUID> ids = jdbc.query(
				"SELECT id FROM " + table + " WHERE username_normalized = ? AND enabled = TRUE" + roleClause,
				(rs, row) -> rs.getObject("id", UUID.class), username);
		if (ids.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Notification account is unavailable.");
		}
		return new Recipient(owner ? "OWNER" : "CUSTOMER", ids.get(0));
	}

	private Set<String> preferenceTypes(boolean owner) {
		return owner ? OWNER_PREFERENCE_TYPES : CUSTOMER_PREFERENCE_TYPES;
	}

	private void validateEndpointAndKeys(String endpoint, String p256dh, String auth) {
		validateEndpoint(endpoint);
		try {
			byte[] publicKeyBytes = Base64.getUrlDecoder().decode(p256dh);
			byte[] authBytes = Base64.getUrlDecoder().decode(auth);
			if (publicKeyBytes.length != 65 || authBytes.length != 16) {
				throw new IllegalArgumentException("Unexpected push key length.");
			}
		} catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid push subscription keys.", exception);
		}
	}

	private void validateEndpoint(String endpoint) {
		try {
			URI uri = URI.create(endpoint);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			boolean allowedHost = ALLOWED_PUSH_HOSTS.contains(host) || host.endsWith(".notify.windows.com");
			if (!"https".equalsIgnoreCase(uri.getScheme())
					|| !allowedHost
					|| uri.getUserInfo() != null
					|| (uri.getPort() != -1 && uri.getPort() != 443)
					|| uri.getRawPath() == null
					|| uri.getRawPath().isBlank()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid push subscription endpoint.");
			}
		} catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid push subscription endpoint.", exception);
		}
	}

	private String normalizePath(String path) {
		if (path == null || !path.startsWith("/") || path.startsWith("//")
				|| path.contains("\\") || path.length() > 300) {
			throw new IllegalArgumentException("Notification target must be an internal path.");
		}
		return path;
	}

	private String limited(String value, int maxLength) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Notification text is required.");
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

	private record Recipient(String type, UUID id) {
	}

	private record BookingNotificationDetails(
			UUID id,
			UUID customerAccountId,
			String customerNote,
			String customerName,
			String serviceName,
			Instant startsAt,
			LocalDate preferredDate,
			LocalDate queueDate,
			Integer queuePosition,
			Instant updatedAt) {
	}

	private record PaymentNotificationDetails(
			UUID id,
			BigDecimal amount,
			UUID bookingId,
			UUID customerAccountId,
			String customerName,
			String serviceName,
			BigDecimal balance) {
	}

	private record InvoiceNotificationDetails(
			UUID customerAccountId,
			String customerName,
			BigDecimal total,
			BigDecimal paid,
			BigDecimal balance) {
	}

	private record Delivery(
			UUID id,
			UUID notificationId,
			UUID guestBookingId,
			int attempts,
			String title,
			String body,
			String targetPath,
			String endpoint,
			String p256dh,
			String auth) {
	}

	private record Reminder(UUID id, String customerName, String serviceName, java.time.Instant startsAt) {
	}
}

