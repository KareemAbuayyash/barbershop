CREATE TABLE customer_accounts (
    id UUID PRIMARY KEY,
    username_normalized VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(150) NOT NULL,
    phone_number VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_customer_accounts_username UNIQUE (username_normalized)
);

ALTER TABLE bookings
    ADD COLUMN customer_account_id UUID REFERENCES customer_accounts (id) ON DELETE SET NULL;

CREATE INDEX idx_bookings_customer_account_id ON bookings (customer_account_id);
CREATE INDEX idx_customer_accounts_enabled ON customer_accounts (enabled);
