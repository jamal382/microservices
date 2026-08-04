CREATE TABLE stock_movements (
    id            BIGSERIAL PRIMARY KEY,
    stock_item_id BIGINT      NOT NULL REFERENCES stock_items(id),
    order_id      BIGINT,
    movement_type VARCHAR(20) NOT NULL
                  CHECK (movement_type IN ('RESERVE','RELEASE','CONFIRM','RESTOCK')),
    quantity      INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_movements_stock_item ON stock_movements(stock_item_id);
CREATE INDEX idx_movements_order      ON stock_movements(order_id);
