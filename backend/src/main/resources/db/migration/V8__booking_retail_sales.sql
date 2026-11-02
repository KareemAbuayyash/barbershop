CREATE TABLE booking_retail_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings (id) ON DELETE RESTRICT,
    product_id UUID NOT NULL REFERENCES inventory_products (id) ON DELETE RESTRICT,
    product_name_snapshot VARCHAR(150) NOT NULL,
    product_unit_snapshot VARCHAR(30) NOT NULL,
    quantity NUMERIC(12, 3) NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10, 2) NOT NULL CHECK (unit_price > 0),
    line_total NUMERIC(10, 2) NOT NULL CHECK (line_total >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_booking_retail_items_booking_id ON booking_retail_items (booking_id);
