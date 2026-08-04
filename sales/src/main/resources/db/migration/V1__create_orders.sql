CREATE TABLE orders (
    id           BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(40)  NOT NULL UNIQUE,
    customer_id  BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','STOCK_RESERVED','CONFIRMED',
                                   'REJECTED','PAYMENT_FAILED','CANCELLED')),
    total_amount NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status   ON orders(status);
