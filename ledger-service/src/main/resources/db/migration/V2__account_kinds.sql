-- Account kinds, so FX can balance (docs/decisions/0002-fx-through-per-currency-pools.md).
--
-- A conversion posted as "debit 100 USD, credit 780 HKD" can never pass the zero-sum trigger: each
-- currency must net to zero on its own. It needs the house's own inventory in each currency to trade
-- against, which a single is_system_account flag can't tell apart from the outside world:
--   USER              - a customer's wallet in one currency
--   EXTERNAL_CLEARING - the outside world for one currency; deposits and withdrawals post against it
--   FX_POOL           - the house's inventory in one currency; a conversion is 4 legs through two pools

DROP VIEW account_balances;   -- depends on is_system_account; recreated below

ALTER TABLE accounts ADD COLUMN kind text NOT NULL DEFAULT 'USER'
    CONSTRAINT accounts_kind_chk CHECK (kind IN ('USER', 'EXTERNAL_CLEARING', 'FX_POOL'));
UPDATE accounts SET kind = 'EXTERNAL_CLEARING' WHERE is_system_account;

ALTER TABLE accounts DROP CONSTRAINT accounts_owner_chk;
DROP INDEX accounts_user_currency_uq;
DROP INDEX accounts_system_currency_uq;
ALTER TABLE accounts DROP COLUMN is_system_account;

-- user accounts have an owner, system accounts never do
ALTER TABLE accounts ADD CONSTRAINT accounts_owner_chk CHECK ((kind = 'USER') = (user_id IS NOT NULL));
-- one wallet per user per currency; one account per system kind per currency
CREATE UNIQUE INDEX accounts_user_currency_uq   ON accounts (user_id, currency) WHERE kind = 'USER';
CREATE UNIQUE INDEX accounts_system_currency_uq ON accounts (kind, currency)    WHERE kind <> 'USER';

ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('TRANSFER', 'DEPOSIT', 'FX_CONVERT'));

INSERT INTO accounts (currency, kind)
SELECT c.currency, 'FX_POOL'
FROM (VALUES ('HKD'), ('USD'), ('EUR'), ('GBP')) AS c (currency);

CREATE VIEW account_balances AS
SELECT a.id       AS account_id,
       a.currency,
       a.kind,
       COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)::bigint
                  AS balance_minor
FROM accounts a
LEFT JOIN ledger_entries e ON e.account_id = a.id
GROUP BY a.id, a.currency, a.kind;
