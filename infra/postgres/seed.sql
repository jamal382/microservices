-- Seed Categories
INSERT INTO catalog.categories (id, name, description) VALUES
(1, 'Electronics', 'Gadgets, devices, and accessories'),
(2, 'Books', 'Technical and non-technical books'),
(3, 'Clothing', 'Apparel and garments')
ON CONFLICT (id) DO NOTHING;

SELECT setval('catalog.categories_id_seq', (SELECT MAX(id) FROM catalog.categories));

-- Seed Products
INSERT INTO catalog.products (id, sku, name, description, price, category_id, active) VALUES
(1, 'ELEC-001', 'ThinkPad X1 Carbon', '14 inch business laptop', 1499.99, 1, true),
(2, 'ELEC-002', 'Wireless Mechanical Keyboard', 'RGB compact keyboard', 99.99, 1, true),
(3, 'ELEC-003', 'Ergonomic Mouse', 'Wireless ergonomic mouse', 49.99, 1, true),
(4, 'ELEC-004', '4K Monitor 27-inch', 'IPS display monitor', 349.99, 1, true),
(5, 'BOOK-001', 'Designing Data-Intensive Applications', 'OReilly systems book', 45.00, 2, true),
(6, 'BOOK-002', 'Clean Code', 'Software craftsmanship guide', 35.50, 2, true),
(7, 'BOOK-003', 'Spring Microservices in Action', 'Spring Boot & Cloud guide', 42.00, 2, true),
(8, 'CLOT-001', 'Developer Hoodie', 'Cotton black hoodie', 59.99, 3, true),
(9, 'CLOT-002', 'Tech Conference T-Shirt', '100% cotton tee', 25.00, 3, true),
(10, 'CLOT-003', 'Out of Stock Special Shirt', 'Limited edition item - zero stock', 13.13, 3, true),
-- Deliberately has NO row in inventory.stock_items below. GET /api/products/11/stock
-- therefore makes catalog call inventory and receive a 404 -- a *business* answer from
-- a *healthy* service. Used in Lab 04 to show that such answers are ignored by the
-- circuit breaker rather than counted as failures.
(11, 'MISC-001', 'Unstocked Curiosity', 'Exists in catalog, unknown to inventory', 19.99, 3, true)
ON CONFLICT (id) DO NOTHING;

SELECT setval('catalog.products_id_seq', (SELECT MAX(id) FROM catalog.products));

-- Seed Stock Items in Inventory
-- Note: product_id 10 has quantity_available 0 (out of stock test)
-- Note: product_id 9 has quantity_available 1 (low stock / race test)
INSERT INTO inventory.stock_items (id, product_id, quantity_available, quantity_reserved, version) VALUES
(1, 1, 10, 0, 0),
(2, 2, 25, 0, 0),
(3, 3, 50, 0, 0),
(4, 4, 15, 0, 0),
(5, 5, 30, 0, 0),
(6, 6, 20, 0, 0),
(7, 7, 40, 0, 0),
(8, 8, 100, 0, 0),
(9, 9, 1, 0, 0),
(10, 10, 0, 0, 0)
ON CONFLICT (id) DO NOTHING;

SELECT setval('inventory.stock_items_id_seq', (SELECT MAX(id) FROM inventory.stock_items));
