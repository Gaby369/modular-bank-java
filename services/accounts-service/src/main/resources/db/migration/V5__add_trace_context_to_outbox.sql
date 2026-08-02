ALTER TABLE accounts.outbox_events
    ADD COLUMN IF NOT EXISTS traceparent VARCHAR(255);

ALTER TABLE accounts.outbox_events
    ADD COLUMN IF NOT EXISTS tracestate VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_accounts_outbox_traceparent
    ON accounts.outbox_events (traceparent);
