-- The public guest token must never be stored in the booking replay payload.
UPDATE booking_idempotency
SET response_body = response_body - 'guestAccessToken'
WHERE response_body ? 'guestAccessToken';

ALTER TABLE booking_idempotency
    DROP CONSTRAINT IF EXISTS booking_idempotency_response_body_check;

ALTER TABLE booking_idempotency
    ADD CONSTRAINT booking_idempotency_response_body_check CHECK (
        jsonb_typeof(response_body) = 'object'
        AND response_body - ARRAY[
            'bookingId',
            'status',
            'bookingMode',
            'startsAt',
            'queueDate',
            'queuePosition',
            'createdAt',
            'serviceId',
            'serviceName',
            'servicePrice',
            'serviceDurationMinutes'
        ]::TEXT[] = '{}'::JSONB
    );

-- Keep old links valid while safely issuing a fresh token on idempotent replays.
ALTER TABLE guest_booking_access
    ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE guest_booking_access
    DROP CONSTRAINT guest_booking_access_pkey;

ALTER TABLE guest_booking_access
    ADD CONSTRAINT guest_booking_access_pkey PRIMARY KEY (id);

CREATE INDEX idx_guest_booking_access_booking
    ON guest_booking_access (booking_id);
