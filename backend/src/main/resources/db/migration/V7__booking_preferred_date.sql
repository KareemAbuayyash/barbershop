ALTER TABLE bookings
    ADD COLUMN preferred_date DATE;

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_preferred_date_mode
        CHECK (preferred_date IS NULL OR booking_mode = 'CONFIRMATION_REQUEST');

CREATE INDEX idx_bookings_preferred_date
    ON bookings (preferred_date)
    WHERE preferred_date IS NOT NULL;
