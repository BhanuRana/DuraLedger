-- Idempotency keys (docs/decisions/0003-idempotency-key-claimed-inside-the-transaction.md).
--
-- The primary key is the whole double-submit defence: INSERT ... ON CONFLICT DO NOTHING lets exactly
-- one request claim a key. A concurrent duplicate's INSERT waits on the winner's uncommitted index
-- entry until the winner commits, then finds the key taken and replays the stored response.
CREATE TABLE idempotency_keys (
    key                text        PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 255),
    request_hash       text        NOT NULL,
    processing_status  text        NOT NULL CHECK (processing_status IN ('PENDING', 'COMPLETED')),
    response_body      jsonb,
    status_code        int,
    created_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT idempotency_completed_has_response_chk
        CHECK (processing_status = 'PENDING' OR (response_body IS NOT NULL AND status_code IS NOT NULL))
);

-- Which request created a transaction; UNIQUE, so one key can never produce two transactions.
ALTER TABLE transactions ADD COLUMN idempotency_key text UNIQUE REFERENCES idempotency_keys (key);
