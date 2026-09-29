# 4. Pessimistic locking is the default for debits

- **Status:** accepted
- **Date:** 2026-09-30

## Context

Two transfers debiting the same account at the same moment must be serialized, or both can pass the funds check and overdraw it. Both standard strategies are implemented behind `duraledger.transfer.locking`, and the whole API suite runs under each:

- **Pessimistic:** `SELECT … FOR UPDATE` on the source account, so concurrent debits queue on the row lock.
- **Optimistic:** read without a lock, then commit only if a version compare-and-set on the account succeeds; on conflict, retry the whole transaction with jittered backoff, and give up with 409 after 16 attempts.

Measured with `LockingBenchmark` (32 concurrent clients, 2,000 transfers, real HTTP against Postgres 16, on 2026-09-30):

| Mode | Scenario | req/s | p99 | Gave up |
|---|---|---:|---:|---:|
| pessimistic | one hot account | 320 | 152 ms | 0 |
| optimistic | one hot account | 242 | 469 ms | 10% |
| pessimistic | spread over accounts | 1,429 | 52 ms | 0 |
| optimistic | spread over accounts | 1,561 | 56 ms | 0 |

## Decision

Pessimistic locking is the default.

On a hot account (a merchant or payroll account, which is exactly where payment volume concentrates), optimistic makes 31 of 32 clients do the work and throw it away each round: about 6 wasted attempts per success, a tripled p99, and one payment in ten refused. Pessimistic makes those clients wait instead, which is cheap. When requests don't conflict, the two are within 10% of each other, so optimistic's advantage never matters where it counts.

## Consequences

- Debits of one account are strictly serialized; hot-account throughput is bounded by how long the lock is held, so everything done under the lock must be fast.
- Optimistic mode stays in the code and in CI, because the benchmark keeps it honest and it would suit a read-heavy, low-contention workload.
- The version column is bumped in both modes, so they're safe to run side by side during a rolling deploy that switches modes.
