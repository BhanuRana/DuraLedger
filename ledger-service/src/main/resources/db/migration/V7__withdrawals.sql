-- Withdrawals: money leaving to the outside world. The mirror of a deposit: debit the user's wallet,
-- credit the currency's EXTERNAL_CLEARING account, so it's double-entry and nets to zero like any other.

ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'FX_CONVERT'));
