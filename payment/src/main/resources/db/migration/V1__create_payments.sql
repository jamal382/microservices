CREATE TABLE payments (
    id             BIGSERIAL PRIMARY KEY,
    payment_ref    VARCHAR(40)  NOT NULL UNIQUE,
    order_id       BIGINT       NOT NULL,
    amount         NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','COMPLETED','FAILED','REFUNDED')),
    method         VARCHAR(20)  NOT NULL
                   CHECK (method IN ('CARD','BANK_TRANSFER','WALLET')),
    failure_reason VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_payments_order ON payments(order_id);
