-- Materialized balance on USER accounts (docs/decisions/0005-materialize-account-balances.md).
--
-- Why: the derived balance, a SUM over ledger_entries, is O(history): 0.24 ms at 1k entries,
-- 24 ms at 100k. It runs while the account row is locked, so a busy account got slower as it aged
-- (320 -> 104 transfers/s with 50k entries of history).
--
-- ledger_entries stays the source of truth. balance_minor is a projection updated in the SAME
-- transaction as the entries (by LedgerPoster), and must always equal the SUM of the account's entries.
--
-- System accounts (EXTERNAL_CLEARING, FX_POOL) keep NULL: every deposit in a currency touches its
-- clearing account, so materializing it would turn it into one hot row serializing all deposits.
ALTER TABLE accounts ADD COLUMN balance_minor bigint DEFAULT 0;

UPDATE accounts a
SET balance_minor = CASE WHEN a.kind = 'USER' THEN
        COALESCE((SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END)
                  FROM ledger_entries e WHERE e.account_id = a.id), 0)
    END;

ALTER TABLE accounts
    ADD CONSTRAINT accounts_balance_materialized_chk CHECK ((kind = 'USER') = (balance_minor IS NOT NULL)),
    -- The last line of defence against overdraft: now that the balance is a column, Postgres can refuse
    -- a negative one outright, even if a locking bug lets two debits past the service-level check.
    ADD CONSTRAINT accounts_balance_non_negative_chk CHECK (balance_minor >= 0);
