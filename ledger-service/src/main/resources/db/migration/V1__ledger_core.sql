-- The double-entry core. Rules this schema enforces *in the database*, not just in Java
-- (docs/decisions/0001-enforce-ledger-invariants-in-postgres.md):
--   1. Balances are never stored. They are derived from ledger_entries (account_balances view).
--   2. ledger_entries is append-only: UPDATE / DELETE / TRUNCATE are rejected by trigger.
--   3. Every transaction nets to zero per currency, checked at COMMIT by a deferred constraint trigger.
--   4. An entry's currency must match its account's currency (composite foreign key).
--   5. Money is bigint minor units (cents), always positive; the sign lives in `direction`.

-- ---------------------------------------------------------------------------
-- accounts: a user wallet has an owner; a system account (money entering or
-- leaving the ledger) has none
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            uuid,
    currency           char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    is_system_account  boolean     NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT accounts_owner_chk CHECK (is_system_account = (user_id IS NULL)),
    -- target for ledger_entries' composite foreign key (account_id, currency)
    CONSTRAINT accounts_id_currency_uq UNIQUE (id, currency)
);

-- one wallet per user per currency, one system account per currency
CREATE UNIQUE INDEX accounts_user_currency_uq   ON accounts (user_id, currency) WHERE NOT is_system_account;
CREATE UNIQUE INDEX accounts_system_currency_uq ON accounts (currency)          WHERE is_system_account;

-- ---------------------------------------------------------------------------
-- transactions: groups the legs of one business operation
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    type        text        NOT NULL CHECK (type IN ('TRANSFER', 'DEPOSIT')),
    status      text        NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- ledger_entries: immutable, append-only source of truth
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  uuid        NOT NULL REFERENCES transactions (id),
    account_id      uuid        NOT NULL,
    currency        char(3)     NOT NULL,
    amount_minor    bigint      NOT NULL CHECK (amount_minor > 0),
    direction       text        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ledger_entries_account_currency_fk
        FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX ledger_entries_account_idx     ON ledger_entries (account_id, created_at);
CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);

-- Rule 2: append-only. Corrections are new compensating entries, never edits.
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

-- Rule 3: per-transaction, per-currency zero-sum. DEFERRED so the legs can be inserted one by one
-- inside a DB transaction and are only checked together at COMMIT. A half-written transfer can
-- never become visible, even if the Java code has a bug.
CREATE FUNCTION check_transaction_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    unbalanced_currency char(3);
BEGIN
    SELECT currency INTO unbalanced_currency
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id
    GROUP BY currency
    HAVING SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END) <> 0
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'transaction % does not net to zero in %', NEW.transaction_id, unbalanced_currency
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();

-- Rule 1: balance is a query, not a value.
CREATE VIEW account_balances AS
SELECT a.id       AS account_id,
       a.currency,
       a.is_system_account,
       COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)::bigint
                  AS balance_minor
FROM accounts a
LEFT JOIN ledger_entries e ON e.account_id = a.id
GROUP BY a.id, a.currency, a.is_system_account;

-- ---------------------------------------------------------------------------
-- seed: one system account per supported currency (money entering / leaving the ledger)
-- ---------------------------------------------------------------------------
INSERT INTO accounts (currency, is_system_account)
SELECT c.currency, true
FROM (VALUES ('HKD'), ('USD'), ('EUR'), ('GBP')) AS c (currency);
