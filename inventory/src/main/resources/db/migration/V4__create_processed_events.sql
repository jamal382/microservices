-- The inbox. One row per event already acted on; the primary key is what makes a
-- redelivered event a no-op instead of a second order cancellation.
CREATE TABLE processed_events (
    event_id     UUID         PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_events_topic ON processed_events (topic, processed_at);
