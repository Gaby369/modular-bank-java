CREATE TABLE IF NOT EXISTS transfers.processed_events (
    event_id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_transfers_processed_events_transfer_id
    ON transfers.processed_events (transfer_id);

CREATE INDEX IF NOT EXISTS idx_transfers_processed_events_processed_at
    ON transfers.processed_events (processed_at);
