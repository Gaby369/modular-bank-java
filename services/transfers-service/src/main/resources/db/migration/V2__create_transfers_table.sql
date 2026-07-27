CREATE TABLE transfers.transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id UUID NOT NULL,
    target_account_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    reference VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_amount_positive
        CHECK (amount > 0),

    CONSTRAINT chk_transfer_status
        CHECK (
            status IN (
                'PENDING',
                'COMPLETED',
                'FAILED',
                'REVERSED'
            )
        ),

    CONSTRAINT chk_different_accounts
        CHECK (source_account_id <> target_account_id)
);

CREATE INDEX idx_transfers_source
    ON transfers.transfers(source_account_id);

CREATE INDEX idx_transfers_target
    ON transfers.transfers(target_account_id);

CREATE INDEX idx_transfers_created_at
    ON transfers.transfers(created_at DESC);