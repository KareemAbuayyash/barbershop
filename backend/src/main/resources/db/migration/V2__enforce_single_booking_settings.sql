ALTER TABLE booking_settings
    ADD COLUMN singleton_key BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE booking_settings
    ADD CONSTRAINT uq_booking_settings_singleton_key UNIQUE (singleton_key);
