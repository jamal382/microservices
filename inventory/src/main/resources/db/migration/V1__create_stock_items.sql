CREATE TABLE stock_items (
    id                 BIGSERIAL PRIMARY KEY,
    product_id         BIGINT      NOT NULL UNIQUE,
    quantity_available INTEGER     NOT NULL DEFAULT 0 CHECK (quantity_available >= 0),
    quantity_reserved  INTEGER     NOT NULL DEFAULT 0 CHECK (quantity_reserved  >= 0),
    version            BIGINT      NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
