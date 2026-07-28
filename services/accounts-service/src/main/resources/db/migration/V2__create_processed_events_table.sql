CREATE TABLE IF NOT EXISTS accounts.processed_events (
    event_id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_processed_events_transfer_id
    ON accounts.processed_events (transfer_id);

CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON accounts.processed_events (processed_at);
