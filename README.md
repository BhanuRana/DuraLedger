# DuraLedger

A multi-currency wallet ledger built with Java 21, Spring Boot, jOOQ, PostgreSQL and GCP Pub/Sub. Money moves through double-entry bookkeeping, every money-moving request is idempotent, the core rules are enforced by the database itself, and a second service follows the ledger through events.

## What it does

- **Accounts:** one wallet per user per currency (HKD, USD, EUR, GBP).
- **Deposits and transfers,** each posted as legs that must net to zero per currency. Deposits post against a per-currency clearing account, so money entering the ledger is double-entry too.
- **Idempotency:** every money-moving request needs an `Idempotency-Key`. A retry with the same key replays the original response (`Idempotent-Replayed: true`) and never moves money twice, even when duplicates arrive at the same instant. Refusals are replayed too, so a key never flips from "declined" to "done". A key reused for a different request is rejected.
- **Concurrency:** transfers from the same account are serialized, so an account can't be overdrawn. Accounts are locked in id order, so transfers in opposite directions can't deadlock.
- **Stored balances:** each wallet's balance is kept on the account row, updated in the same transaction as its ledger entries, so reading it is O(1) whatever the history. The ledger entries remain the source of truth.
- **Events:** each money movement writes its event to an outbox table in the same transaction as its ledger legs, so an event can't be lost and can't announce a change that rolled back. A relay publishes them to Pub/Sub (at-least-once, `FOR UPDATE SKIP LOCKED` so replicas split the work).
- **Activity feed:** `notification-service` builds each wallet's feed from those events alone, with its own schema. It applies a redelivered event once and skips event types it doesn't know.
- **Errors** are RFC 9457 `application/problem+json` with stable types, e.g. `urn:duraledger:problem:insufficient-funds`.

Postgres enforces the ledger invariants itself: zero-sum per currency at commit, append-only entries, an entry's currency matching its account's, and no negative balance.

## API

| Endpoint | |
|---|---|
| `POST /accounts` | Create a wallet |
| `GET /accounts/{id}` · `GET /accounts/{id}/balance` | Read a wallet and its balance |
| `POST /deposits` | Money in (needs `Idempotency-Key`) |
| `POST /transfers` | Same-currency transfer (needs `Idempotency-Key`) |
| `GET /transactions/{id}` | A transaction and its ledger legs |
| `GET /accounts/{id}/activity` | The wallet's activity feed (notification-service, port 8081) |

## Locking modes

`duraledger.transfer.locking` picks how concurrent debits of one account are serialized:

- `pessimistic` (default): `SELECT … FOR UPDATE` on the accounts involved.
- `optimistic`: read without locks, commit only if the account's version is unchanged, retry with jittered backoff, and give up with 409 after 16 attempts.

Benchmark (32 concurrent clients, 2,000 transfers, real HTTP against Postgres 16):

| Mode | Scenario | req/s | p99 | Gave up |
|---|---|---:|---:|---:|
| pessimistic | one hot account | 399 | 100 ms | 0 |
| pessimistic | one hot account with 50,000 entries of history | 434 | 91 ms | 0 |
| pessimistic | spread over many accounts | 1,567 | 43 ms | 0 |
| optimistic | one hot account | 254 | 444 ms | 12% |
| optimistic | spread over many accounts | 1,551 | 55 ms | 0 |

Before balances were stored, the account with 50,000 entries managed only 104 req/s: the balance was a `SUM` over its history, computed while the row was locked. Run it with `./mvnw test -Dtest='*LockingBenchmark'`.

## Tests

Everything runs against a real Postgres and the real Pub/Sub emulator through Testcontainers; there are no in-memory fakes, because the guarantees depend on real locking and trigger behaviour.

| Suite | What it proves |
|---|---|
| `LedgerSchemaSpec` (Spock) | Each database invariant, by trying to break it in SQL |
| `MoneyMovementApiTests` | Transfers, deposits, idempotency and the concurrency cases over real HTTP: 20 identical requests → 1 transaction; 20 transfers draining one account → exactly the affordable 10 succeed; opposite transfers don't deadlock |
| `OptimisticMoneyMovementApiTests` | The same suite under optimistic locking |
| `AccountApiTests` | Wallet creation, lookup and error types |
| `OutboxRelayTests` | A committed deposit reaches Pub/Sub (the emulator) and is marked published |
| `ActivityFeedTests` (notification-service) | Both sides of a transfer in the right feeds, a redelivered event applied once, unknown event types skipped, newest first |

## Run it

Requires JDK 21 and Docker (the build generates jOOQ classes from a real, migrated Postgres container).

```bash
(cd ledger-service && ./mvnw verify) && (cd notification-service && ./mvnw verify)   # all tests

(cd ledger-service && ./mvnw spring-boot:run)        # :8080, starts Postgres + Pub/Sub emulator
(cd notification-service && ./mvnw spring-boot:run)  # :8081
./scripts/demo.sh                                    # the whole story end to end (needs curl, jq)
```

## Docs

- [`docs/decisions/`](docs/decisions): architecture decision records
    - [0001](docs/decisions/0001-enforce-ledger-invariants-in-postgres.md) Enforce ledger invariants in PostgreSQL
    - [0002](docs/decisions/0002-fx-through-per-currency-pools.md) Currency conversion through per-currency FX pools
    - [0003](docs/decisions/0003-idempotency-key-claimed-inside-the-transaction.md) Claim the idempotency key inside the transaction
    - [0004](docs/decisions/0004-pessimistic-locking-by-default.md) Pessimistic locking by default
    - [0005](docs/decisions/0005-materialize-account-balances.md) Store balances on the account row
- [`BUILD_LOG.md`](BUILD_LOG.md): dated engineering journal

## License

Apache 2.0
