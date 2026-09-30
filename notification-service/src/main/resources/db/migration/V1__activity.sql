-- The activity-feed read model, built only from ledger events. Lives in the `notification` schema
-- (spring.flyway.default-schema): this service never reads ledger-service's tables.
CREATE TABLE activity (
    -- ledger-service's outbox id. Pub/Sub delivery is at-least-once, so the same event can arrive
    -- twice: (event_id, account_id) as the key + ON CONFLICT DO NOTHING makes applying it idempotent.
    event_id                 bigint      NOT NULL,
    account_id               uuid        NOT NULL,
    transaction_id           uuid        NOT NULL,
    event_type               text        NOT NULL,
    direction                text        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor             bigint      NOT NULL CHECK (amount_minor > 0),
    currency                 char(3)     NOT NULL,
    counterparty_account_id  uuid,
    occurred_at              timestamptz NOT NULL,
    received_at              timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (event_id, account_id)
);

-- "latest N items for an account" is the only query; serve it straight from the index
CREATE INDEX activity_feed_idx ON activity (account_id, occurred_at DESC, event_id DESC);
