SET CONSTRAINTS uq_bookings_queue_date_position DEFERRED;

ALTER TABLE bookings DROP CONSTRAINT chk_bookings_mode_fields;
ALTER TABLE bookings ADD CONSTRAINT chk_bookings_mode_fields CHECK (
    (booking_mode IN ('LEGACY', 'EXACT_APPOINTMENT')
        AND starts_at IS NOT NULL AND queue_date IS NULL AND queue_position IS NULL)
    OR (booking_mode = 'CONFIRMATION_REQUEST'
        AND queue_date IS NULL AND queue_position IS NULL
        AND (status NOT IN ('CONFIRMED', 'COMPLETED') OR starts_at IS NOT NULL))
    OR (booking_mode IN ('DAY_QUEUE', 'SAME_DAY_QUEUE')
        AND starts_at IS NULL AND queue_date IS NOT NULL
        AND ((status IN ('CANCELLED', 'COMPLETED') AND queue_position IS NULL)
             OR (queue_position IS NOT NULL AND queue_position > 0)))
);

UPDATE bookings
SET queue_position = NULL
WHERE status IN ('CANCELLED', 'COMPLETED')
  AND queue_date IS NOT NULL;

WITH numbered AS (
    SELECT id,
           row_number() OVER (PARTITION BY queue_date ORDER BY queue_position, created_at, id)::INTEGER AS position
    FROM bookings
    WHERE status IN ('PENDING', 'CONFIRMED')
      AND queue_date IS NOT NULL
)
UPDATE bookings booking
SET queue_position = numbered.position
FROM numbered
WHERE booking.id = numbered.id;

INSERT INTO booking_queue_counters (queue_date, last_position)
SELECT queue_date, count(*)::INTEGER
FROM bookings
WHERE status IN ('PENDING', 'CONFIRMED')
  AND queue_date IS NOT NULL
GROUP BY queue_date
ON CONFLICT (queue_date) DO UPDATE
SET last_position = EXCLUDED.last_position,
    updated_at = CURRENT_TIMESTAMP;

UPDATE booking_queue_counters counter
SET last_position = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM bookings booking
    WHERE booking.queue_date = counter.queue_date
      AND booking.status IN ('PENDING', 'CONFIRMED')
);
