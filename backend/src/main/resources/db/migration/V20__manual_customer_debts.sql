CREATE TABLE manual_customer_debts (
    id UUID PRIMARY KEY,
    customer_account_id UUID REFERENCES customer_accounts (id) ON DELETE SET NULL,
    customer_name VARCHAR(150) NOT NULL,
    phone_number VARCHAR(32) NOT NULL,
    description VARCHAR(500) NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL CHECK (total_amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(120) NOT NULL
);

CREATE INDEX idx_manual_customer_debts_account
    ON manual_customer_debts (customer_account_id)
    WHERE customer_account_id IS NOT NULL;
CREATE INDEX idx_manual_customer_debts_phone
    ON manual_customer_debts (phone_number);

CREATE TABLE manual_customer_debt_payments (
    id UUID PRIMARY KEY,
    manual_debt_id UUID NOT NULL REFERENCES manual_customer_debts (id) ON DELETE CASCADE,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    method VARCHAR(20) NOT NULL CHECK (method IN ('CASH', 'CARD', 'TRANSFER', 'OTHER')),
    notes VARCHAR(1000),
    paid_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_manual_debt_payments_debt
    ON manual_customer_debt_payments (manual_debt_id, paid_at DESC);
