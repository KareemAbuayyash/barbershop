CREATE TABLE services (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    description TEXT NOT NULL,
    price NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_services_active ON services (active);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    phone_number VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_customers_phone_number ON customers (phone_number);

CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    customer_id UUID REFERENCES customers (id) ON DELETE SET NULL,
    service_id UUID NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
    starts_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'CANCELLED')),
    notes TEXT,
    service_name_snapshot VARCHAR(150) NOT NULL,
    service_price_snapshot NUMERIC(10, 2) NOT NULL CHECK (service_price_snapshot >= 0),
    service_duration_snapshot_minutes INTEGER NOT NULL
        CHECK (service_duration_snapshot_minutes > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_bookings_customer_id ON bookings (customer_id);
CREATE INDEX idx_bookings_service_id ON bookings (service_id);
CREATE INDEX idx_bookings_status_starts_at ON bookings (status, starts_at);

CREATE TABLE booking_settings (
    id UUID PRIMARY KEY,
    exact_appointment_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    confirmation_request_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    day_queue_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    same_day_queue_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    new_bookings_disabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Start closed with all booking modes disabled until the shop configures its availability.
INSERT INTO booking_settings (id)
VALUES ('00000000-0000-0000-0000-000000000001');
