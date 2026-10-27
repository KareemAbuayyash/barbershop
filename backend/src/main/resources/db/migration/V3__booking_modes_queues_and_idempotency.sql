ALTER TABLE bookings
    ADD COLUMN booking_mode VARCHAR(32);

UPDATE bookings
SET booking_mode = 'LEGACY';

ALTER TABLE bookings
    ALTER COLUMN booking_mode SET NOT NULL,
    ALTER COLUMN starts_at DROP NOT NULL,
    ADD COLUMN queue_date DATE,
    ADD COLUMN queue_position INTEGER,
    ADD COLUMN appointment_end_at TIMESTAMPTZ;

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_booking_mode
        CHECK (booking_mode IN (
            'LEGACY',
            'EXACT_APPOINTMENT',
            'CONFIRMATION_REQUEST',
            'DAY_QUEUE',
            'SAME_DAY_QUEUE'
        )),
    ADD CONSTRAINT chk_bookings_mode_fields
        CHECK (
            (
                booking_mode IN ('LEGACY', 'EXACT_APPOINTMENT')
                AND starts_at IS NOT NULL
                AND queue_date IS NULL
                AND queue_position IS NULL
            )
            OR (
                booking_mode = 'CONFIRMATION_REQUEST'
                AND queue_date IS NULL
                AND queue_position IS NULL
                AND (status NOT IN ('CONFIRMED', 'COMPLETED') OR starts_at IS NOT NULL)
            )
            OR (
                booking_mode IN ('DAY_QUEUE', 'SAME_DAY_QUEUE')
                AND starts_at IS NULL
                AND queue_date IS NOT NULL
                AND queue_position IS NOT NULL
                AND queue_position > 0
            )
        );

CREATE FUNCTION set_booking_appointment_end_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'CONFIRMED' AND NEW.starts_at IS NOT NULL THEN
        NEW.appointment_end_at :=
            NEW.starts_at + make_interval(mins => NEW.service_duration_snapshot_minutes);
    ELSE
        NEW.appointment_end_at := NULL;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bookings_set_appointment_end_at
BEFORE INSERT OR UPDATE ON bookings
FOR EACH ROW
EXECUTE FUNCTION set_booking_appointment_end_at();

-- Backfill the trigger-maintained interval without changing booking history fields.
UPDATE bookings
SET status = status;

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_appointment_end_at
        CHECK (
            (
                status = 'CONFIRMED'
                AND starts_at IS NOT NULL
                AND appointment_end_at IS NOT NULL
                AND appointment_end_at > starts_at
            )
            OR (
                (status <> 'CONFIRMED' OR starts_at IS NULL)
                AND appointment_end_at IS NULL
            )
        );

ALTER TABLE bookings
    ADD CONSTRAINT uq_bookings_queue_date_position
        UNIQUE (queue_date, queue_position)
        DEFERRABLE INITIALLY IMMEDIATE;

CREATE TABLE booking_queue_counters (
    queue_date DATE PRIMARY KEY,
    last_position INTEGER NOT NULL DEFAULT 0 CHECK (last_position >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE booking_queue_counters IS
    'Atomic allocation counters for the shared daily DAY_QUEUE and SAME_DAY_QUEUE.';

-- Lock out writes between the overlap check and installing the exclusion constraint.
LOCK TABLE bookings IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    conflicting_booking_ids TEXT;
BEGIN
    SELECT format('%s and %s', first_booking.id, second_booking.id)
    INTO conflicting_booking_ids
    FROM bookings first_booking
    JOIN bookings second_booking
      ON first_booking.id < second_booking.id
    WHERE first_booking.appointment_end_at IS NOT NULL
      AND second_booking.appointment_end_at IS NOT NULL
      AND tstzrange(
            first_booking.starts_at,
            first_booking.appointment_end_at,
            '[)'
          )
          && tstzrange(
            second_booking.starts_at,
            second_booking.appointment_end_at,
            '[)'
          )
    LIMIT 1;

    IF conflicting_booking_ids IS NOT NULL THEN
        RAISE EXCEPTION
            USING ERRCODE = '23P01',
                  MESSAGE = 'V3 cannot enforce confirmed appointment exclusivity because existing bookings overlap.',
                  DETAIL = format('Conflicting booking IDs: %s', conflicting_booking_ids),
                  HINT = 'Resolve the conflicting appointments without deleting booking history, then retry the migration.';
    END IF;
END;
$$;

ALTER TABLE bookings
    ADD CONSTRAINT ex_bookings_confirmed_appointment_no_overlap
        EXCLUDE USING gist (
            tstzrange(starts_at, appointment_end_at, '[)') WITH &&
        )
        WHERE (appointment_end_at IS NOT NULL);

CREATE TABLE booking_idempotency (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_scope VARCHAR(100) NOT NULL
        CHECK (operation_scope = 'POST:/api/bookings:v1'),
    idempotency_key VARCHAR(255) NOT NULL
        CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    request_fingerprint BYTEA NOT NULL
        CHECK (octet_length(request_fingerprint) = 32),
    response_status SMALLINT NOT NULL
        CHECK (response_status BETWEEN 200 AND 299),
    response_body JSONB NOT NULL
        CHECK (
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
        ),
    response_location TEXT,
    booking_id UUID REFERENCES bookings (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_booking_idempotency_scope_key
        UNIQUE (operation_scope, idempotency_key)
);

COMMENT ON TABLE booking_idempotency IS
    'Successful guest booking creation replays; response_body is restricted to customer-PII-free fields.';
