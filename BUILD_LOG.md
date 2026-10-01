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

## 2026-09-30: idempotency hardened, and a second locking strategy

Two follow-ups to the key: a refusal (e.g. insufficient funds) rolled back the key claim, so a retry after funds arrived succeeded (one key, two outcomes); rejections are now stored and replayed like successes. And a key reused for a different body replayed the old response, a 201 for a transfer that never happened; the stored request hash is now compared, and a mismatch is a 422.

Then optimistic locking behind `duraledger.transfer.locking`: `V4__account_version.sql` adds `accounts.version`; the transfer reads the source without a lock and commits only if `UPDATE … SET version = version + 1 WHERE id = ? AND version = ?` hits one row. A conflict rolls back everything (the key claim too), and `TransferRetrier`, outside the transaction, retries with full-jitter backoff; after 16 attempts it gives up with 409 `concurrent-modification`. The version is bumped under both modes, so they're safe side by side. The whole API suite now runs under both.

Mutation check: with the compare-and-set disabled, the optimistic overdraw test drove the account to −300 in 3 of 3 runs.

## 2026-09-30: benchmark, pessimistic vs optimistic

`LockingBenchmark` (on demand, not in CI: `./mvnw test -Dtest='*LockingBenchmark'`): 32 clients, 2,000 transfers per scenario after a warmup, real HTTP against Postgres 16. Scenarios: **hot** (every transfer debits one account), **hot-deep** (the same account with 50,000 entries of history), **spread** (each client its own account).

| Mode | Scenario | req/s | p50 | p99 | Gave up (409) | Wasted attempts |
|---|---|---:|---:|---:|---:|---:|
| pessimistic | hot | 320 | 97 ms | 152 ms | 0 | 0 |
| pessimistic | hot-deep | 104 | 304 ms | 367 ms | 0 | 0 |
| pessimistic | spread | 1,429 | 21 ms | 52 ms | 0 | 0 |
| optimistic | hot | 242 | 68 ms | 469 ms | 199 (10%) | 12,527 |
| optimistic | hot-deep | 104 | 234 ms | 787 ms | 334 (17%) | 14,694 |
| optimistic | spread | 1,561 | 19 ms | 56 ms | 0 | 0 |

Pessimistic stays the default ([ADR 0004](docs/decisions/0004-pessimistic-locking-by-default.md)): on a hot account optimistic wastes about 6 attempts per success and refuses 10–17% of payments.

**The bigger finding is history.** The same hot account with 50,000 past entries drops from 320 to 104 req/s. `EXPLAIN ANALYZE` of the balance query (a `SUM` over the account's entries) on a bare Postgres 16: **0.24 ms at 1k entries, 2.0 ms at 10k, 24 ms at 100k.** Linear in history, and it runs while the row lock is held, so a busy account gets slower every day it's used.

## 2026-09-30: stored balances, and the deadlock they caused

`V5__materialized_balance.sql` stores `balance_minor` on user accounts, updated by `LedgerPoster` in the same transaction as the entries, with `CHECK (balance_minor >= 0)` as a last line of defence ([ADR 0005](docs/decisions/0005-materialize-account-balances.md)). The funds check now reads one column instead of summing history. System accounts stay `NULL`: every deposit touches its currency's clearing account, so storing its balance would make it one hot row.

**The deadlock.** A transfer now writes both account rows, and a new test moving money both ways between two accounts at once deadlocked: Postgres reported 110 `deadlock detected` across both locking modes. A→B held A and waited for B while B→A held B and waited for A.

Fix, in two parts, because the modes lock differently:
- **Pessimistic:** lock both accounts up front with one `SELECT … WHERE id IN (…) ORDER BY id FOR UPDATE`.
- **Optimistic:** there are no up-front locks, so `LedgerPoster` applies the balance updates in id order. The version compare-and-set can't stay a separate statement run first (it would lock the source out of order again), so it's folded into the source's balance `UPDATE`.

The order must be *Postgres's* order: Java's `UUID.compareTo` compares signed 64-bit halves, Postgres compares unsigned bytes, and they disagree for about half of all ids. The poster sorts by the canonical string, which matches, so the two modes lock in the same sequence and stay safe side by side.

Verified: 0 deadlocks in 5 of 5 repeated runs. Mutation check: insertion-order locking brings back 30–46 deadlocks per run, 3 of 3.

## 2026-09-30: the benchmark after storing balances

Same benchmark, balances now stored:

| Mode | Scenario | req/s | p50 | p99 | Gave up (409) |
|---|---|---:|---:|---:|---:|
| pessimistic | hot | 399 | 79 ms | 100 ms | 0 |
| pessimistic | hot-deep (50k entries) | **434** | 72 ms | **91 ms** | 0 |
| pessimistic | spread | 1,567 | 19 ms | 43 ms | 0 |
| optimistic | hot | 254 | 57 ms | 444 ms | 245 (12%) |
| optimistic | hot-deep | 242 | 59 ms | 496 ms | 236 (12%) |
| optimistic | spread | 1,551 | 19 ms | 55 ms | 0 |

The deep-history hot account went from **104 to 434 req/s (4.2×), p99 367 → 91 ms**, and history stopped mattering (hot ≈ hot-deep). Optimistic still refuses about 12% of hot-account payments, so pessimistic stays the default. The remaining per-account ceiling, about 2.5 ms of lock hold per transfer, is round trips plus the commit's WAL flush; the next levers would be one statement per transfer or batching commits.

## 2026-09-30: events leave through a transactional outbox

The dual-write problem: "commit, then publish to the broker" is two steps, and a crash between them loses the event. So each deposit and transfer writes its event to an `outbox` table (`V6__outbox.sql`) **in the same transaction** as its ledger legs: the event commits with the money or not at all, and a refused or replayed request writes none.

`OutboxRelay` publishes committed rows to Pub/Sub every 200 ms and marks them published only after Pub/Sub acknowledges. Delivery is therefore at-least-once (a crash between the ack and the commit re-publishes), and consumers must deduplicate on the `eventId` attribute. `FOR UPDATE SKIP LOCKED` lets several replicas relay at once, each taking a batch nobody else holds. Gauges `duraledger.outbox.pending` and `…oldest.pending.seconds` show relay lag. Locally the Pub/Sub emulator runs in docker-compose; tests start their own emulator container.

**Green suite, noisy shutdown.** The log showed 114 `Unexpected error occurred in scheduled task`: 109 × `Cannot publish on a shut-down publisher` and 4 × `Could not open JDBC Connection`. When a context shuts down, the scheduler kept ticking after the Pub/Sub publisher and the connection pool were closed. Fix: a `running` flag cleared on `ContextClosedEvent` (which fires before any bean is destroyed), plus `spring.task.scheduling.shutdown.await-termination` so an in-flight tick finishes. After: 0.

## 2026-09-30: a second service, fed only by events

`notification-service` builds each wallet's activity feed from `ledger.events` alone. It owns a `notification` schema with its own Flyway history and never reads the ledger's tables, so the event payload is the only contract between the two services. A streaming-pull subscriber feeds `ActivityProjector`: a transfer becomes a DEBIT row for the sender and a CREDIT row for the receiver. It's idempotent on `(event_id, account_id)` with `ON CONFLICT DO NOTHING`, because at-least-once delivery makes duplicates normal; a redelivered event is applied once (tested). Unknown event types are counted and *acknowledged*: throwing would nack them, and Pub/Sub would redeliver forever a message this service can never handle.

`scripts/demo.sh` runs the story against both services: deposit, a transfer as two legs, an idempotent retry (`Idempotent-Replayed: true`, balance unchanged), key reuse and an overdraft refused, 10 simultaneous duplicates producing one transaction, and both feeds built from events. Relay backlog: 0.

**Stopping one service took the database from the other.** After the demo, Postgres was stopped while the emulator kept running. Reproduced: stopping only ledger-service stopped Postgres, and notification-service went `DOWN (db: DOWN)` with 500s. Spring Boot's Docker Compose support stops the containers it started when the app stops, which is wrong once two services share them. Fix: `lifecycle-management=start-only` in ledger-service too (notification-service already had it). After: Postgres stays up and notification-service stays `UP`.

## 2026-10-01: the ledger checks itself

`ReconciliationJob` runs every 60 s in one read-only `REPEATABLE READ` snapshot, so a commit between its two queries can't make them disagree (MVCC means it never blocks writers). Check 1: every currency nets to zero across **all** entries. That's stronger than the per-transaction trigger, because it doesn't care how an entry got there. Check 2: every stored balance equals the sum of its entries. Violations become gauges and an ERROR log; `POST /actuator/reconciliation` runs one on demand. It is deliberately not a readiness probe: failing readiness would pull every replica out of service and halt all payments. It's scheduled from a separate bean, because a `@Scheduled` method calling `run()` on its own class would bypass the transaction proxy and silently lose the snapshot.

The tests corrupt the ledger on purpose. A balance edited by +1 is reported as `materialized=2501, ledger=2500`. A one-sided +999 USD entry written **with triggers disabled** (`session_replication_role = replica`, which some restore and replication tools use), with the stored balance quietly fixed up to match, passes check 2 and is caught by check 1 with USD net exactly 999. Mutation check: with check 1 disabled, exactly that test fails.

**Two more shutdown problems**, surfaced because this test class uses `@DirtiesContext` and closes a context mid-run:
1. 8 × `HikariDataSource has been closed`, thrown inside the relay's *proxy*. The `running` check sat inside a `@Transactional` method, and the proxy borrows a connection before the method body runs. The check now comes first, and a `TransactionTemplate` opens the transaction after it. 8 → 0.
2. A publish timeout, handled correctly, still surfaced as an ERROR stack trace; during a Pub/Sub outage that's 5 per second. Now one WARN plus a `duraledger.outbox.publish.failures` counter.

## 2026-10-02: images, and the database version production actually runs

Both services now ship as layered, non-root images (`MaxRAMPercentage=75`: a 576 MiB heap under a 768 MiB limit; the application layer is 135 kB) and run from them with `docker compose --profile app up --build`. The demo passes against the containers. The deployment design is [ADR 0006](docs/decisions/0006-cloud-run-neon-scale-to-zero.md): Cloud Run and Neon, scaling to zero.

**Version drift.** The production database, a Neon project, runs **PostgreSQL 18.6**: that's what Flyway reported when it migrated it. Every test here ran on 16, so production would have run a version no test had touched. Compose, both services' test containers, the schema spec and jOOQ codegen all move to `postgres:18-alpine`, and codegen regenerated from 18. The 18 image keeps data in a versioned subdirectory and can't open a 16 data directory, so compose mounts a new volume at `/var/lib/postgresql`. All 62 tests pass on 18.6. Lesson: pin the test database to production's version, and read the version production reports rather than assuming it.

## 2026-10-02: the same code in a scale-to-zero mode

Cloud Run throttles CPU between requests and removes idle instances, so nothing that relies on a background thread can be trusted there. Three things relied on one, and each now has a second mode chosen by configuration, so the images stay identical:

| Was | Local (default) | Cloud Run (`gcp` profile) |
|---|---|---|
| relay and reconciliation timers | `duraledger.tasks.trigger=internal`: in-process `@Scheduled` | `external`: `POST /internal/tasks/outbox-sweep` and `/reconcile`, called by Cloud Scheduler |
| relay polling every 200 ms | poller | `publish-after-commit=true`: an `afterCommit` hook runs the relay once; the hourly sweep is the safety net |
| streaming-pull subscriber | `duraledger.events.delivery=pull` | `push`: Pub/Sub POSTs each event to `/pubsub/push`; the status code is the ack |

The service URLs are public, so the task and push endpoints check the `Authorization` header themselves: a Google-signed OIDC token, issued for this service's URL as audience, from one allow-listed service account. Anything else is a 403, including an `alg=none` token (unit-tested). The integration tests replace the verifier with a stub and run the whole context with no scheduler bean at all, so an event can only arrive through publish-after-commit, and a row inserted behind the API can only leave through the sweep.

**Joining a finished transaction.** Inside `afterCommit` the committed transaction's connection is still bound to the thread, and Spring's documentation says code running there still participates in it. The relay's template used the default `REQUIRED` propagation, so it joined. The test passed anyway: its UPDATEs were committed only because Spring resets autocommit afterwards, and setting autocommit to true commits anything pending. That's correct by accident, so the relay now opens its own transaction with `REQUIRES_NEW`.

The `gcp` profiles turn off Flyway on startup. Migrations ship as a separate Flyway image per service (`flyway/flyway:12.4.0`, matching the flyway-core the tests run) and run as Cloud Run Jobs before a new revision takes traffic. Hikari pools are 4 and 3 connections for Neon's free tier, the connection timeout is 10 s to cover Neon waking a suspended compute, and only `health` and `info` are exposed. `infra/gcp/` holds the one-time setup and the deploy script, which nothing runs yet.

## 2026-10-02: withdrawals, FX, and a key on the door

**Withdrawals** (`V7`) mirror deposits: debit the wallet, credit the currency's EXTERNAL_CLEARING account. A withdrawal is a debit, so it takes the same lock (or version guard) as a transfer's source. A test races 20 withdrawals and transfers against one account; under pessimistic locking exactly the affordable 10 succeed. Mutation check: dropping only the lock changes nothing, because the version guard catches the race and the retrier recovers. Dropping the lock and the guard leaves the `balance >= 0` CHECK as the last layer: no overdraft, but clients get 500s, and the test fails on them. Three layers, and the test sees past the first two.

**FX conversion** (`V8` adds `transactions.metadata`) posts four legs through the per-currency FX_POOL accounts from ADR 0002, so each currency nets to zero by itself and the reconciliation needs no FX awareness. Rates are static units-per-USD in `BigDecimal`. The converted amount rounds *down*: 10.00 HKD becomes 128 cents, not 129, and the house keeps the remainder, so rounding can't drain a pool. An amount that converts to less than one minor unit is refused rather than posted as zero. The rate used is stored on the transaction and returned as a decimal string, never a double. notification-service shows a conversion as two rows in two currencies, one per wallet.

**API key and rate limit.** The services will have public URLs, so every business endpoint now needs `X-Api-Key`. A servlet filter runs before anything else: a stranger costs one 401 and no database work. The compare is constant-time and loops over every configured key, so timing doesn't reveal a partial match; several keys can be valid at once for rotation; with no key configured it refuses everything. Exempt: health, the task and push endpoints (they have OIDC), and the error page. Each key then gets a fixed one-minute window, held in memory per instance (429 with `Retry-After`). Two Cloud Run instances at most means a leaked key gets at most 240 a minute, which is enough to protect a free-tier database. Both rejections are counted in `duraledger.api.rejected`, because the filter runs ahead of Spring's HTTP metrics and they'd otherwise be invisible.

The demo now runs the API key refusal, a withdrawal and an FX conversion; against the containers, reconciliation reports 0 violations after it. Ledger tests: 79; notification-service: 10.

## 2026-10-02: Kubernetes, locally

The same images on a local kind cluster (no paid GKE): Postgres as a StatefulSet, the Pub/Sub emulator, Flyway Jobs that must complete before the apps start, and two ledger replicas (their relays share the outbox through `SKIP LOCKED`). Services are NodePorts mapped to the host, so requests are balanced by kube-proxy across ready pods; a `kubectl port-forward` pins one pod and would hide what a rollout does. Requests equal limits for memory, and the heap is 75% of it; there's no CPU limit, because CFS throttling stalls a JVM in GC and JIT while the request still guarantees a fair share. `k8s/up.sh` brings it all up in order.

**A startup race.** On the first bring-up notification-service restarted once: `ALREADY_EXISTS: Topic already exists`. Both services created the topic with "if missing, create", and ledger-service created it in the gap between notification-service's check and its create. A test that has 8 threads create the topology at once failed 3 runs out of 3, in both services. The fix is to create and tolerate `ALREADY_EXISTS`, which is the same lesson as the idempotency keys: check-then-act is a race unless the check and the act are one operation. After it, a fresh cluster comes up with 0 restarts.

**Rollouts dropped requests.** `k8s/rollout-test.sh` fires transfers from 8 workers while `kubectl rollout restart` replaces every ledger pod, then requires every response to be a 201. With `maxUnavailable: 0`, readiness probes and graceful shutdown, it still failed: 130 of 3,527 and 112 of 3,576 requests got connection errors. Removing a pod from the Service's endpoints and sending it SIGTERM happen in parallel, so for a moment kube-proxy still routes to a pod that has stopped accepting connections. A 5-second `preStop` sleep lets routing catch up before shutdown begins: 3 runs, 0 failures out of 3,386, 3,608 and 3,566 requests. In every run, with or without the fix, the receiver's balance equalled the number of 201s: a failed request never moved money.

## 2026-10-02: metrics, alerts, and making every alert fire

Cloud Run scales to zero, so there's nothing to scrape: both services push metrics over OTLP. Locally they go to `grafana/otel-lgtm` (a collector, Prometheus and Grafana in one container, started by the compose `app` profile); in production they go to Grafana Cloud. Each process sends a random `service.instance.id` so replicas don't overwrite each other's series, and request latency uses six SLO buckets instead of a full histogram, to stay inside the free tier's series budget.

**Green tests, dead app.** With export switched on in compose, both services exited at startup: `ProtobufRuntimeVersionException`, generated code 4.34.0 against runtime 4.33.2. The GCP BOM pins the protobuf runtime, and the OTLP registry was generated with a newer one. Every test had passed because export is off by default. A `protobuf-bom` 4.34.0 imported ahead of the GCP BOM fixes it, and `MetricsExportTests` now boots each service with export on.

Nine alerts live in `observability/` as Prometheus rules (one file for the alerts that mean the same everywhere, plus schedule-dependent thresholds for local and Cloud Run), each with `promtool` unit tests: a firing case and a near-miss. `prove-alerts.sh` then breaks the running stack once per alert and waits for Prometheus to report it FIRING. The first full run found three real problems:

1. **HighServerErrorRate never fired.** With Postgres stopped, every request waited Hikari's default 30 s for a connection before its 500 (30.03 to 30.16 s measured), holding a server thread throughout; the alert sat in pending until the proof timed out. A 3 s connection timeout makes an outage fail in 3.0 s, and the alert fired 211 s into a clean outage. The gcp profile keeps 10 s for Neon waking a suspended compute.
2. **TransferRetriesExhausted never fired**, although 17 transfers had just exhausted their retries. The chaos config restarts ledger-service, and the new instance counted all 17 before its first push: its series was born at 17, and `increase()` only sees change between samples, so it read 0. A Cloud Run instance that cold-starts, handles a burst and scales away would lose the whole burst the same way. Both counter alerts now add the birth value of series that didn't exist 10 minutes earlier. A promtool case with a series born at 17 failed on the old rule; the live proof now fires.
3. **A database outage paged as a Pub/Sub failure.** With Pub/Sub healthy and Postgres stopped, `OutboxPublishFailing` fired after 21 s: relay ticks fail when they can't read the outbox too, and one counter couldn't tell the causes apart. Failures are now tagged `cause=pubsub` or `cause=database` and the alert counts only Pub/Sub. Live: a database outage counted 22 database and 0 Pub/Sub failures with the alert inactive, and pausing Pub/Sub fired it in 41 s.

After the fixes all nine alerts reached FIRING against the running stack. CI now also runs the rule tests and fails if the committed dashboard JSON drifts from its generator.

## 2026-10-02: continuous delivery, ready for GCP

The workflow now has a deploy job after both test jobs: on `main` it authenticates to GCP through Workload Identity Federation, so no service-account key exists anywhere, and the provider (`infra/gcp/setup-ci.sh`) accepts only tokens minted for this repository's `main` branch. The deployer account gets exactly the roles `deploy.sh` needs, plus `actAs` on each runtime account individually, so it can't run anything as, say, the Compute default account. The job runs the same `deploy.sh` as a manual deploy (build, push, migrate as Cloud Run Jobs, deploy) and smoke-tests both services' health. It stays skipped until the two repository variables exist, so CI is green without a cloud project.

Production metrics go to Grafana Cloud once its token is in Secret Manager (`scripts/store-grafana-secret.sh` stores it as the ready-made `Authorization` header, because `--set-secrets` can only inject a value verbatim); until then export stays off.

The README is now the full write-up: what's proven and how, the money-movement postings, the measured numbers, and every bug above with its evidence.
