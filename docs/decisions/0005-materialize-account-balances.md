# 5. Store user balances on the account row, derived from the ledger

- **Status:** accepted
- **Date:** 2026-09-30

## Context

The ledger's first rule was "balance is a query, not a value": a `SUM` over the account's entries ([ADR 0001](0001-enforce-ledger-invariants-in-postgres.md)). The locking benchmark showed its cost. That `SUM` is linear in history (0.24 ms at 1,000 entries, 24 ms at 100,000), and the transfer runs it *while holding the account's row lock*. So every debit of a busy account waits behind everyone else's `SUM`, and the account gets slower every day it's used: a hot account with 50,000 entries managed only 104 transfers/s, against 320 for a fresh one.

## Decision

User accounts carry `balance_minor`, updated by `LedgerPoster` in the **same transaction** as the ledger entries (V5 migration). The funds check reads that column.

- The ledger entries remain the source of truth; the column is a projection of them and must always equal their sum. Reconciliation will verify it.
- Because the balance is now a column, Postgres enforces `CHECK (balance_minor >= 0)`: a last line of defence against an overdraft that a derived `SUM` could never have.
- System accounts (clearing, FX pools) keep `NULL`. Every deposit in a currency touches its clearing account, so storing that balance would serialize all deposits on one row.

## Consequences

Measured with the same benchmark (32 clients, pessimistic locking):

| Scenario | Before | After |
|---|---:|---:|
| hot account | 320 req/s · p99 152 ms | 399 req/s · p99 100 ms |
| hot account, 50,000 entries | 104 req/s · p99 367 ms | **434 req/s · p99 91 ms** |

- Throughput no longer depends on history, and reading a balance is O(1).
- A transfer now writes two account rows, which made deadlocks possible: rows are locked and updated in Postgres uuid order, and the optimistic version check is folded into the same `UPDATE`.
- There are now two representations of a balance that can drift apart if something bypasses `LedgerPoster`. Detecting that is reconciliation's job.
- The remaining ceiling (about 2.5 ms of lock hold per transfer) is round trips and the commit's WAL flush, not queries.
