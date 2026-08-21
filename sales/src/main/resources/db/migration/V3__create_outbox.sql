-- The transactional outbox. Written in the same transaction as the business change,
-- drained by a relay, never deleted -- see OutboxEvent for why each of those matters.
CREATE TABLE outbox (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(50)  NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- Partial index: the relay only ever asks for unpublished rows, and the table is kept
-- as a permanent audit log. A plain index on published_at would grow with every event
-- ever emitted; this one stays the size of the backlog, which is normally zero.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at, id) WHERE published_at IS NULL;
