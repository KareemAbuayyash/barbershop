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
            'serviceDurationMinutes',
            'guestAccessToken'
        ]::TEXT[] = '{}'::JSONB
    );
