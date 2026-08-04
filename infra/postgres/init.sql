CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS sales;
CREATE SCHEMA IF NOT EXISTS payment;

CREATE USER catalog_user WITH PASSWORD 'catalog_pass';
CREATE USER inventory_user WITH PASSWORD 'inventory_pass';
CREATE USER sales_user WITH PASSWORD 'sales_pass';
CREATE USER payment_user WITH PASSWORD 'payment_pass';

GRANT ALL PRIVILEGES ON SCHEMA catalog TO catalog_user;
GRANT ALL PRIVILEGES ON SCHEMA inventory TO inventory_user;
GRANT ALL PRIVILEGES ON SCHEMA sales TO sales_user;
GRANT ALL PRIVILEGES ON SCHEMA payment TO payment_user;

-- Isolation: each user may touch only its own schema.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
