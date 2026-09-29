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
