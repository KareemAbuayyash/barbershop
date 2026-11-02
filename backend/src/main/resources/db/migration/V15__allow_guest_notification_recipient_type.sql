ALTER TABLE app_notifications
    DROP CONSTRAINT IF EXISTS app_notifications_recipient_type_check;

ALTER TABLE app_notifications
    ADD CONSTRAINT app_notifications_recipient_type_check
    CHECK (recipient_type IN ('OWNER', 'CUSTOMER', 'GUEST'));
