ALTER TABLE inventory_movements
    ADD COLUMN unit_price NUMERIC(10, 2) CHECK (unit_price >= 0),
    ADD COLUMN line_total NUMERIC(10, 2) CHECK (line_total >= 0);
