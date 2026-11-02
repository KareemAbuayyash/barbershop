CREATE TABLE booking_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings (id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    method VARCHAR(20) NOT NULL CHECK (method IN ('CASH', 'CARD', 'TRANSFER', 'OTHER')),
    notes TEXT,
    paid_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_booking_payments_paid_at ON booking_payments (paid_at);
CREATE INDEX idx_booking_payments_customer_id ON booking_payments (customer_id, paid_at DESC);
CREATE INDEX idx_booking_payments_booking_id ON booking_payments (booking_id, paid_at DESC);

CREATE TABLE shop_expenses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    expense_date DATE NOT NULL,
    method VARCHAR(20) NOT NULL CHECK (method IN ('CASH', 'CARD', 'TRANSFER', 'OTHER')),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) NOT NULL
);

CREATE INDEX idx_shop_expenses_date ON shop_expenses (expense_date DESC);

CREATE TABLE inventory_products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    unit VARCHAR(30) NOT NULL,
    quantity NUMERIC(12, 3) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    low_stock_threshold NUMERIC(12, 3) NOT NULL DEFAULT 0 CHECK (low_stock_threshold >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inventory_products_active ON inventory_products (active, name);

CREATE TABLE inventory_movements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES inventory_products (id) ON DELETE RESTRICT,
    movement_type VARCHAR(20) NOT NULL CHECK (movement_type IN ('PURCHASE', 'CONSUMPTION', 'SALE', 'ADJUSTMENT')),
    quantity NUMERIC(12, 3) NOT NULL CHECK (quantity > 0),
    unit_cost NUMERIC(10, 2) CHECK (unit_cost >= 0),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) NOT NULL
);

CREATE INDEX idx_inventory_movements_product_date ON inventory_movements (product_id, created_at DESC);

CREATE TABLE shop_employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    phone_number VARCHAR(32),
    job_title VARCHAR(100) NOT NULL,
    base_salary NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (base_salary >= 0),
    commission_percent NUMERIC(5, 2) NOT NULL DEFAULT 0 CHECK (commission_percent BETWEEN 0 AND 100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE bookings
    ADD COLUMN employee_id UUID REFERENCES shop_employees (id) ON DELETE SET NULL;

CREATE INDEX idx_bookings_employee_id ON bookings (employee_id);

CREATE TABLE employee_commissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES shop_employees (id) ON DELETE RESTRICT,
    payment_id UUID NOT NULL UNIQUE REFERENCES booking_payments (id) ON DELETE RESTRICT,
    amount NUMERIC(10, 2) NOT NULL CHECK (amount >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_employee_commissions_employee ON employee_commissions (employee_id, created_at DESC);

CREATE TABLE employee_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES shop_employees (id) ON DELETE RESTRICT,
    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    payment_type VARCHAR(20) NOT NULL CHECK (payment_type IN ('SALARY', 'COMMISSION', 'ADVANCE', 'OTHER')),
    period_start DATE,
    period_end DATE,
    notes TEXT,
    paid_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) NOT NULL
);

CREATE INDEX idx_employee_payments_employee_date ON employee_payments (employee_id, paid_at DESC);

CREATE TABLE shop_profile (
    singleton_key BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton_key),
    phone_number VARCHAR(32),
    address VARCHAR(300),
    opening_hours JSONB NOT NULL DEFAULT '{}'::JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) NOT NULL
);

INSERT INTO shop_profile (singleton_key, updated_by) VALUES (TRUE, 'system');
