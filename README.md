# DuraLedger

A multi-currency wallet ledger built with Java 21, Spring Boot, jOOQ and PostgreSQL. Money moves through double-entry bookkeeping, every money-moving request is idempotent, and the core rules are enforced by the database itself.

## What it does

- **Accounts:** one wallet per user per currency (HKD, USD, EUR, GBP).
- **Deposits and transfers,** each posted as legs that must net to zero per currency.
- **Idempotency:** every money-moving request needs an `Idempotency-Key`. A retry with the same key replays the original response and never moves money twice; a key reused for a different request is rejected.
- **Concurrency:** transfers from the same account are serialized, so an account can't be overdrawn. Pessimistic locking by default, optimistic as an alternative.
- **Errors** are RFC 9457 `application/problem+json` with stable types, e.g. `urn:duraledger:problem:insufficient-funds`.

Postgres enforces the ledger invariants: zero-sum per currency at commit, append-only entries, currency matching the account, and no negative balance.

## API

| Endpoint | |
|---|---|
| `POST /accounts` | Create a wallet |
| `GET /accounts/{id}` · `GET /accounts/{id}/balance` | Read a wallet and its balance |
| `POST /deposits` | Money in (needs `Idempotency-Key`) |
| `POST /transfers` | Same-currency transfer (needs `Idempotency-Key`) |
| `GET /transactions/{id}` | A transaction and its ledger legs |

## Run it

Requires JDK 21 and Docker (the build generates jOOQ classes from a real, migrated Postgres container).

```bash
cd ledger-service
./mvnw verify            # tests against real Postgres via Testcontainers
./mvnw spring-boot:run   # starts Postgres from ../docker-compose.yml, app on :8080
```

## Docs

- [`docs/decisions/`](docs/decisions): architecture decision records
- [`BUILD_LOG.md`](BUILD_LOG.md): dated engineering journal

## License

Apache 2.0
