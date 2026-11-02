ALTER TABLE notification_subscriptions
    DROP CONSTRAINT IF EXISTS notification_subscriptions_endpoint_key;

CREATE UNIQUE INDEX uq_owner_notification_subscription_endpoint
    ON notification_subscriptions (owner_account_id, endpoint)
    WHERE owner_account_id IS NOT NULL;

CREATE UNIQUE INDEX uq_customer_notification_subscription_endpoint
    ON notification_subscriptions (customer_account_id, endpoint)
    WHERE customer_account_id IS NOT NULL;

CREATE UNIQUE INDEX uq_guest_notification_subscription_endpoint
    ON notification_subscriptions (guest_booking_id, endpoint)
    WHERE guest_booking_id IS NOT NULL;

CREATE INDEX idx_notification_subscriptions_endpoint
    ON notification_subscriptions (endpoint);

-- Earlier versions moved a device from one guest booking to the next. Recover
-- prior guest associations from the delivery history before future pushes run.
INSERT INTO notification_subscriptions (
    id, endpoint, p256dh_key, auth_key, guest_booking_id, enabled
)
SELECT gen_random_uuid(), subscription.endpoint, subscription.p256dh_key, subscription.auth_key,
       notification.guest_booking_id, TRUE
FROM notification_subscriptions subscription
JOIN notification_push_deliveries delivery
  ON delivery.subscription_id = subscription.id
JOIN app_notifications notification
  ON notification.id = delivery.notification_id
WHERE subscription.guest_booking_id IS NOT NULL
  AND notification.recipient_type = 'GUEST'
  AND notification.guest_booking_id IS NOT NULL
  AND notification.guest_booking_id <> subscription.guest_booking_id
GROUP BY subscription.endpoint, subscription.p256dh_key, subscription.auth_key,
         notification.guest_booking_id
ON CONFLICT (guest_booking_id, endpoint) WHERE guest_booking_id IS NOT NULL DO NOTHING;
