ALTER TABLE accounts.outbox_events
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_accounts_outbox_correlation_id
    ON accounts.outbox_events (correlation_id);
