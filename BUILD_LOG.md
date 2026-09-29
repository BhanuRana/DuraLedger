# Build log

A dated engineering journal: what was built, what was verified, what went wrong and why each decision was made. Newest entries at the bottom.

## 2026-09-29: project init

The goal: a multi-currency wallet ledger where money is never lost or duplicated under retries, concurrent requests, crashes or deploys. Double-entry bookkeeping enforced by PostgreSQL itself, idempotent money movement, and events between services.

Stack decisions made up front:
- **Java 21 (Temurin)**, pinned per project with `.sdkmanrc`. The machine's default JDK stays as it is; `sdk env` switches inside this folder.
- **Spring Boot + jOOQ + Flyway + PostgreSQL.** SQL stays explicit (jOOQ) because the ledger's correctness lives in the database: constraints, triggers, locks.
- **Maven** with the wrapper committed, so a laptop and CI build with the same Maven version.

## 2026-09-29: ledger-service skeleton, and a version that doesn't exist

Generated `ledger-service` from Spring Initializr (Boot 4.1, Java 21, Maven): Web, Actuator, jOOQ, Flyway, PostgreSQL, Validation, Testcontainers, Docker Compose support.

**The generated project didn't build.** `Non-resolvable parent POM … spring-boot-starter-parent:4.1.1.RELEASE (absent)`. Initializr's API lists each Boot version under an internal id with a status suffix (`.RELEASE`, `.M2`, `.BUILD-SNAPSHOT`). The web UI strips it before generating; calling `/starter.zip` with the raw id does not. Maven Central's `maven-metadata.xml` confirms the real version is plain `4.1.1`. One-line fix in `pom.xml`.

Verified: `./mvnw verify` passes, including the generated context test, which boots the app against a real Postgres container through Testcontainers.

## 2026-09-29: Postgres for local development

A root `docker-compose.yml` with Postgres 16, shared by every service to come. Spring Boot's Docker Compose support starts it on `./mvnw spring-boot:run` and builds the `DataSource` from the running container, so there is no datasource URL or password in `application.properties`.

How that works: the auto-configured `DataSource` asks for a `JdbcConnectionDetails` bean instead of reading properties directly. Docker Compose support supplies one from live container state (mapped port, `POSTGRES_USER`); in tests, `@ServiceConnection` on a Testcontainers bean supplies the same thing from a throwaway container. Two separate mechanisms on purpose: dev containers keep data between runs, test containers start empty every time. Both are pinned to `postgres:16-alpine`.

Verified: `spring-boot:run` → compose container healthy → Flyway connected (PostgreSQL 16.15) → `/actuator/health` shows `db: UP`. `./mvnw verify` green.

## 2026-09-29: the double-entry schema

`V1__ledger_core.sql`: accounts, transactions and ledger entries, with the money rules enforced by PostgreSQL itself ([ADR 0001](docs/decisions/0001-enforce-ledger-invariants-in-postgres.md)). Each transaction's legs must net to zero per currency (a deferred constraint trigger, checked at `COMMIT`); entries are append-only; an entry's currency must match its account's (composite foreign key); amounts are positive integer cents with the sign in `direction`. Balances are a view over the entries, not a stored value.

The zero-sum trigger is deferred because legs are inserted one row at a time: after the first leg, every valid transaction is momentarily unbalanced.

## 2026-09-29: specs that try to break the ledger

`LedgerSchemaSpec` (Spock) runs the real Flyway migrations on a real Postgres 16 container and attacks each invariant directly in SQL: an unbalanced posting, a one-sided entry, `UPDATE`/`DELETE`/`TRUNCATE` on entries, USD legs on an HKD account, negative amounts, a second wallet in the same currency, an ownerless user account. Postgres refuses every one. 14 cases.

Mutation check: with the zero-sum trigger's `RAISE` removed, 3 specs fail (the unbalanced posting, the one-sided entry, the whole-ledger sum). Restored, all pass. A test that stays green when its protection is removed proves nothing.

## 2026-09-29: FX broke the model before any Java existed

Writing a spec for a currency conversion exposed a design bug. The obvious posting, "debit 100.00 USD, credit 780.00 HKD", has one leg per currency, so neither nets to zero and the trigger rejects it (correctly). The fix is four legs through the house's per-currency FX pools ([ADR 0002](docs/decisions/0002-fx-through-per-currency-pools.md)). A single `is_system_account` flag can't tell the outside world from the house's inventory, so `V2__account_kinds.sql` replaces it with `kind`: `USER`, `EXTERNAL_CLEARING`, `FX_POOL`.

Done as a new migration rather than an edit to V1: it rebuilds the dependent view, constraints and partial indexes around the new column, and seeds the FX pools. Specs: the four-leg conversion commits; the naive two-leg one is rejected. 16 cases.

## 2026-09-29: jOOQ classes from the real schema

`codegen/jooq-codegen.groovy`, run by gmavenplus in `generate-sources`: start a throwaway Postgres 16 container, run the Flyway migrations, generate jOOQ classes over JDBC. It's skipped when no migration changed (a stamp file), so normal builds don't start a container. The build now needs Docker running.

Two alternatives didn't work. jOOQ's `DDLDatabase` replays migrations on an embedded H2, which can't parse the PL/pgSQL trigger functions; hiding them from the parser meant hiding real schema from codegen. The official `testcontainers-jooq-codegen-maven-plugin` pins a Docker client too old for the Docker 29 engine. About 50 lines of Groovy, pinned to Spring Boot's own managed versions of jOOQ, Flyway and Testcontainers, avoids both problems.

## 2026-09-29: the overdraft race

Accounts, deposits and transfers now work over HTTP, with every refusal as RFC 9457 `problem+json` carrying a stable type (`urn:duraledger:problem:insufficient-funds`, ...).

A concurrency test released 20 transfers of 100 from a balance of 1,000 at the same instant. Only 10 are affordable, yet two of three runs let **13 and 18** through: each request read the balance before any of them had written, so all saw 1,000. The third run passed by luck, which is how a race hides in an ordinary suite.

Note that the zero-sum trigger can't catch this: an overdraft is perfectly balanced double-entry (Alice −1,300, Bob +1,300). "Money is never created" and "a balance never goes negative" are different rules, and the second needs a lock.

Fix: `SELECT … FOR UPDATE` on the account being debited, before reading its balance. A concurrent transfer from the same account now waits for the first to commit and then sees the reduced balance. After the fix: exactly 10 succeed and the balance ends at 0, in 5 of 5 runs.

## 2026-09-30: exactly once per Idempotency-Key

A test for a client retry (same key, same body) failed first: the retry created a second transaction and debited again. `V3__idempotency_keys.sql` adds the keys table and a `UNIQUE` `transactions.idempotency_key`. The key is claimed with `INSERT … ON CONFLICT DO NOTHING` inside the same database transaction as the transfer ([ADR 0003](docs/decisions/0003-idempotency-key-claimed-inside-the-transaction.md)).

How a concurrent duplicate behaves: its `INSERT` hits the winner's uncommitted index entry and waits on Postgres's lock; once the winner commits, the duplicate claims nothing and replays the stored response. So there is no polling loop, and a crash can't leave a key stuck, because the claim rolls back with everything else.

Verified: 20 identical requests released at once produce one transaction and 20 identical 201 bodies; the retry replays with `Idempotent-Replayed: true`; a missing key is a 400. 32 tests.
