ALTER TABLE inventory_products
    ADD COLUMN purchase_price NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (purchase_price >= 0),
    ADD COLUMN sale_price NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (sale_price >= 0),
    ADD COLUMN image_data BYTEA,
    ADD COLUMN image_content_type VARCHAR(30);
