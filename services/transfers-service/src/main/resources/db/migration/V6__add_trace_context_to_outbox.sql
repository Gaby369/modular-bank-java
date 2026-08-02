ALTER TABLE transfers.outbox_events
    ADD COLUMN IF NOT EXISTS traceparent VARCHAR(255);

ALTER TABLE transfers.outbox_events
    ADD COLUMN IF NOT EXISTS tracestate VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_transfers_outbox_traceparent
    ON transfers.outbox_events (traceparent);
