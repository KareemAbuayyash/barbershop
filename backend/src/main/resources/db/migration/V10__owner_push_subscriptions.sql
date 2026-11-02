CREATE TABLE owner_push_subscriptions (
    endpoint TEXT PRIMARY KEY,
    p256dh_key VARCHAR(200) NOT NULL,
    auth_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
