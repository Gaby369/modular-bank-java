CREATE TABLE IF NOT EXISTS accounts.outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    routing_key VARCHAR(150) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ NULL,
    last_error TEXT NULL,

    CONSTRAINT chk_accounts_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),

    CONSTRAINT chk_accounts_outbox_attempts
        CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS idx_accounts_outbox_status_created_at
    ON accounts.outbox_events (status, created_at);

CREATE INDEX IF NOT EXISTS idx_accounts_outbox_aggregate
    ON accounts.outbox_events (aggregate_type, aggregate_id);
