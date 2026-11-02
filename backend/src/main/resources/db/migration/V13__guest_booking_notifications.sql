ALTER TABLE app_notifications
    ADD COLUMN guest_booking_id UUID REFERENCES bookings (id) ON DELETE CASCADE;

ALTER TABLE app_notifications
    DROP CONSTRAINT chk_notification_recipient;

ALTER TABLE app_notifications
    ADD CONSTRAINT chk_notification_recipient CHECK (
        (recipient_type = 'OWNER' AND owner_account_id IS NOT NULL AND customer_account_id IS NULL AND guest_booking_id IS NULL)
        OR (recipient_type = 'CUSTOMER' AND owner_account_id IS NULL AND customer_account_id IS NOT NULL AND guest_booking_id IS NULL)
        OR (recipient_type = 'GUEST' AND owner_account_id IS NULL AND customer_account_id IS NULL AND guest_booking_id IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_guest_notification_event
    ON app_notifications (guest_booking_id, event_key)
    WHERE guest_booking_id IS NOT NULL;

CREATE INDEX idx_guest_notifications_created
    ON app_notifications (guest_booking_id, created_at DESC)
    WHERE guest_booking_id IS NOT NULL;

ALTER TABLE notification_subscriptions
    ADD COLUMN guest_booking_id UUID REFERENCES bookings (id) ON DELETE CASCADE;

ALTER TABLE notification_subscriptions
    DROP CONSTRAINT chk_notification_subscription_owner;

ALTER TABLE notification_subscriptions
    ADD CONSTRAINT chk_notification_subscription_recipient CHECK (
        (owner_account_id IS NOT NULL AND customer_account_id IS NULL AND guest_booking_id IS NULL)
        OR (owner_account_id IS NULL AND customer_account_id IS NOT NULL AND guest_booking_id IS NULL)
        OR (owner_account_id IS NULL AND customer_account_id IS NULL AND guest_booking_id IS NOT NULL)
    );

CREATE INDEX idx_guest_notification_subscriptions
    ON notification_subscriptions (guest_booking_id)
    WHERE guest_booking_id IS NOT NULL;

CREATE TABLE guest_booking_access (
    booking_id UUID PRIMARY KEY REFERENCES bookings (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_guest_booking_access_expiry
    ON guest_booking_access (expires_at);
