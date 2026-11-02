CREATE TABLE app_notifications (
    id UUID PRIMARY KEY,
    recipient_type VARCHAR(16) NOT NULL CHECK (recipient_type IN ('OWNER', 'CUSTOMER')),
    owner_account_id UUID REFERENCES management_accounts (id) ON DELETE CASCADE,
    customer_account_id UUID REFERENCES customer_accounts (id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    event_key VARCHAR(180) NOT NULL,
    title VARCHAR(150) NOT NULL,
    body VARCHAR(500) NOT NULL,
    target_path VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,
    CONSTRAINT chk_notification_recipient CHECK (
        (recipient_type = 'OWNER' AND owner_account_id IS NOT NULL AND customer_account_id IS NULL)
        OR (recipient_type = 'CUSTOMER' AND customer_account_id IS NOT NULL AND owner_account_id IS NULL)
    )
);

CREATE UNIQUE INDEX uq_owner_notification_event
    ON app_notifications (owner_account_id, event_key)
    WHERE owner_account_id IS NOT NULL;
CREATE UNIQUE INDEX uq_customer_notification_event
    ON app_notifications (customer_account_id, event_key)
    WHERE customer_account_id IS NOT NULL;
CREATE INDEX idx_owner_notifications_unread
    ON app_notifications (owner_account_id, created_at DESC)
    WHERE owner_account_id IS NOT NULL;
CREATE INDEX idx_customer_notifications_unread
    ON app_notifications (customer_account_id, created_at DESC)
    WHERE customer_account_id IS NOT NULL;

CREATE TABLE notification_subscriptions (
    id UUID PRIMARY KEY,
    endpoint TEXT NOT NULL UNIQUE,
    p256dh_key VARCHAR(200) NOT NULL,
    auth_key VARCHAR(100) NOT NULL,
    owner_account_id UUID REFERENCES management_accounts (id) ON DELETE CASCADE,
    customer_account_id UUID REFERENCES customer_accounts (id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_notification_subscription_owner CHECK (
        (owner_account_id IS NOT NULL AND customer_account_id IS NULL)
        OR (owner_account_id IS NULL AND customer_account_id IS NOT NULL)
    )
);

INSERT INTO notification_subscriptions (
    id, endpoint, p256dh_key, auth_key, owner_account_id
)
SELECT gen_random_uuid(), legacy.endpoint, legacy.p256dh_key, legacy.auth_key, owner.id
FROM owner_push_subscriptions legacy
CROSS JOIN management_accounts owner
WHERE owner.role = 'ROLE_OWNER'
ON CONFLICT (endpoint) DO NOTHING;

CREATE TABLE notification_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_account_id UUID REFERENCES management_accounts (id) ON DELETE CASCADE,
    customer_account_id UUID REFERENCES customer_accounts (id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_notification_preference_owner CHECK (
        (owner_account_id IS NOT NULL AND customer_account_id IS NULL)
        OR (owner_account_id IS NULL AND customer_account_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_owner_notification_preference
    ON notification_preferences (owner_account_id, event_type)
    WHERE owner_account_id IS NOT NULL;
CREATE UNIQUE INDEX uq_customer_notification_preference
    ON notification_preferences (customer_account_id, event_type)
    WHERE customer_account_id IS NOT NULL;

CREATE TABLE notification_push_deliveries (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES app_notifications (id) ON DELETE CASCADE,
    subscription_id UUID NOT NULL REFERENCES notification_subscriptions (id) ON DELETE CASCADE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_until TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_error VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notification_subscription_delivery UNIQUE (notification_id, subscription_id)
);

CREATE INDEX idx_notification_push_delivery_due
    ON notification_push_deliveries (next_attempt_at)
    WHERE delivered_at IS NULL;

DROP TABLE owner_push_subscriptions;

