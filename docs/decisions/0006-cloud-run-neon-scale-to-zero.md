# 6. Deploy on Cloud Run and Neon, scaling to zero

- **Status:** accepted
- **Date:** 2026-10-02

## Context

The service needs a public deployment that costs close to nothing while idle, because it's a demonstration with bursts of traffic and long quiet periods. The textbook managed setup on GCP (GKE Autopilot, Cloud SQL, Memorystore) bills around the clock: roughly $60–150 a month with no traffic at all.

## Decision

| Piece | Choice | Idle cost |
|---|---|---|
| Both services | **Cloud Run**, 0–2 instances each | $0 (free tier: 2M requests, 180k vCPU-seconds a month) |
| Database | **Neon** serverless Postgres, in the same city as Cloud Run (Singapore) | $0 (free tier; compute suspends when idle) |
| Events | **Pub/Sub** | $0 (first 10 GB a month) |
| Scheduled work | **Cloud Scheduler** calling authenticated endpoints | $0 (3 jobs free) |
| Migrations | Flyway image run as a **Cloud Run Job** before each rollout | covered by the free tier |
| Secrets, images | Secret Manager, Artifact Registry (keep the 2 newest images) | about $0 |

A budget alert is set before anything billable exists.

**What scale-to-zero changes in the design:**
- **No background CPU.** Cloud Run throttles CPU between requests and removes idle instances, so `@Scheduled` timers can't be relied on. The relay and reconciliation become HTTP task endpoints that Cloud Scheduler calls, protected by Google-signed OIDC tokens from one allow-listed service account. Locally the same code still runs on timers, chosen by configuration.
- **Events publish right after commit.** With no poller running, each money movement publishes its outbox rows in an after-commit hook; an hourly sweep is the safety net for a crash between commit and publish.
- **Push, not pull.** A streaming-pull consumer needs an always-on process, so Pub/Sub *pushes* each event to notification-service over HTTPS (OIDC-signed), waking it from zero. The HTTP status is the acknowledgement.
- **Migrations never run on startup.** A Cloud Run Job runs Flyway before the new revision takes traffic, so cold starts stay short and several instances never race to migrate.
- **The database is in the same city.** A transfer makes about 8 round trips to Postgres, so the region must match, not just the continent.

## Consequences

- About $0 a month while idle. The first request after idle pays a cold start of a few seconds.
- The same images run in two modes, chosen by Spring profile and properties; there's no Cloud Run–specific build.
- Scheduled work is sparse on purpose: every scheduler call wakes both Cloud Run and Neon's compute, so an hourly sweep and a reconciliation every 6 hours keep both mostly asleep.
- Kubernetes is still worth showing; it can run the same images on a free local cluster rather than a paid GKE one.
