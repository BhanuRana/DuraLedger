-- The outbox: the event side of each money movement, written in the SAME transaction as its ledger
-- legs, so an event can't be lost and can't announce a change that rolled back. A relay publishes
-- committed rows to the message broker and marks them published.
CREATE TABLE outbox (
    -- bigint identity (not uuid) so the relay gets a cheap, roughly insertion-ordered cursor
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type  text        NOT NULL,
    aggregate_id    uuid        NOT NULL,
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz
);

-- the relay only ever scans unpublished rows; keep that index tiny
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;
