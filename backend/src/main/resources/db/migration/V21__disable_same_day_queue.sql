UPDATE booking_settings
SET same_day_queue_enabled = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE same_day_queue_enabled = TRUE;
