-- Wallet ledger schema.
--
-- Money is always an integer number of paise (BIGINT). No floats, no numeric-with-scale, ever.
-- A CHECK enforces non-negative balances at the storage layer as a backstop to the application's
-- conditional-debit logic.

CREATE TABLE wallets (
    id            UUID PRIMARY KEY,
    user_id       TEXT   NOT NULL,
    balance_paise BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallets_user_id     UNIQUE (user_id),
    CONSTRAINT ck_wallets_non_negative CHECK (balance_paise >= 0)
);

CREATE TABLE transfers (
    id              UUID PRIMARY KEY,
    idempotency_key TEXT   NOT NULL,
    created_by      TEXT   NOT NULL,
    from_wallet     UUID   NOT NULL REFERENCES wallets (id),
    to_wallet       UUID   NOT NULL REFERENCES wallets (id),
    amount_paise    BIGINT NOT NULL,
    status          TEXT   NOT NULL,
    reason          TEXT,
    request_hash    TEXT   NOT NULL,
    reversal_of     UUID   REFERENCES transfers (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_key         UNIQUE (created_by, idempotency_key),
    CONSTRAINT ck_transfers_amount_pos  CHECK (amount_paise > 0),
    CONSTRAINT ck_transfers_status      CHECK (status IN ('pending', 'succeeded', 'declined')),
    CONSTRAINT ck_transfers_not_self    CHECK (from_wallet <> to_wallet)
);

-- A given transfer can be reversed at most once: the partial unique index rejects a second
-- reversal row (even one submitted with a different idempotency key) with a 23505.
CREATE UNIQUE INDEX uq_transfers_reversal_of
    ON transfers (reversal_of)
    WHERE reversal_of IS NOT NULL;

CREATE INDEX ix_transfers_from_wallet ON transfers (from_wallet);
CREATE INDEX ix_transfers_to_wallet   ON transfers (to_wallet);
