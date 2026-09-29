# 1. Enforce ledger invariants in PostgreSQL, not only in Java

- **Status:** accepted
- **Date:** 2026-09-29

## Context

A ledger is only as trustworthy as its weakest write path. Money-moving code in the service will be tested, but it is not the only thing that can write to the database: a future feature, a migration, a manual SQL fix during an incident, or a bug in retry logic can all insert rows. If the rules that keep money correct live only in Java, any of those paths can commit a ledger that is silently wrong.

The rules that must never break:
1. Every transaction nets to zero, per currency.
2. Ledger entries are never edited or deleted; mistakes are corrected with new, compensating entries.
3. An entry's currency matches its account's currency.
4. Amounts are positive integers in minor units, with the sign carried by `DEBIT` / `CREDIT`.

## Decision

Each rule is enforced by PostgreSQL itself:

| Rule | Mechanism |
|---|---|
| Zero-sum per currency | A `DEFERRABLE INITIALLY DEFERRED` constraint trigger, checked at `COMMIT` |
| Append-only entries | Triggers that reject `UPDATE`, `DELETE` and `TRUNCATE` |
| Currency matches account | A composite foreign key `(account_id, currency) → accounts(id, currency)` |
| Positive integer amounts | `bigint` with `CHECK (amount_minor > 0)`, sign in `direction` |

The service still validates everything first, so clients get precise error messages. The database is the backstop that makes a wrong ledger impossible to commit, not the first line of validation.

The zero-sum check is deferred because a transaction's legs are inserted one row at a time: after the first leg, every valid transaction is momentarily unbalanced. Checking at `COMMIT` sees all legs together, and a failure rolls back the whole transaction, so nothing half-written is ever visible.

## Consequences

- A bug in Java can produce an error, but not a corrupt ledger.
- Each rule gets a test that tries to break it against a real PostgreSQL, not a mock.
- Some logic lives in PL/pgSQL, which is less familiar than Java and is invisible to Java-only tooling such as jOOQ's SQL parser. That is accepted: these few functions are small and change rarely.
- Business rules that aren't structural (for example "a user balance never goes negative") are not covered by this ADR; they need locking and service logic.
