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
