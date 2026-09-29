# Build log

A dated engineering journal: what was built, what was verified, what went wrong and why each decision was made. Newest entries at the bottom.

## 2026-09-29: project init

The goal: a multi-currency wallet ledger where money is never lost or duplicated under retries, concurrent requests, crashes or deploys. Double-entry bookkeeping enforced by PostgreSQL itself, idempotent money movement, and events between services.

Stack decisions made up front:
- **Java 21 (Temurin)**, pinned per project with `.sdkmanrc`. The machine's default JDK stays as it is; `sdk env` switches inside this folder.
- **Spring Boot + jOOQ + Flyway + PostgreSQL.** SQL stays explicit (jOOQ) because the ledger's correctness lives in the database: constraints, triggers, locks.
- **Maven** with the wrapper committed, so a laptop and CI build with the same Maven version.
