DROP TABLE IF EXISTS employee_commissions;
DROP TABLE IF EXISTS employee_payments;

ALTER TABLE bookings DROP COLUMN IF EXISTS employee_id;

DROP TABLE IF EXISTS shop_employees;
