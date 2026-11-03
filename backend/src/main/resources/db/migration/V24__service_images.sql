ALTER TABLE services
    ADD COLUMN image_content_type VARCHAR(100),
    ADD COLUMN image_data BYTEA;
