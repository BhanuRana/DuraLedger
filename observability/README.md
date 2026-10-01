# Observability

Metrics are **pushed** over OTLP by both services (Cloud Run scales to zero, so there is nothing to scrape). Locally they go to `grafana/otel-lgtm` (OTel collector + Prometheus + Grafana in one container); in production to Grafana Cloud. Same protocol, same rules, same dashboard.

```
docker compose --profile app up -d --build    # from duraledger/, after building both jars
open http://localhost:3000/d/duraledger       # dashboard
open http://localhost:9090/alerts             # alert rules and their state
```

| Path | What |
|---|---|
| `prometheus/rules/common.yaml` | Alerts that mean the same everywhere |
| `prometheus/rules/local.yaml` · `gcp.yaml` | Schedule-dependent thresholds (reconciliation every 60 s locally, every 6 h on Cloud Run) |
| `prometheus/rules/alerts.test.yaml` | `promtool` unit tests: a firing case and a near-miss per alert, including scale-to-zero edge cases |
| `grafana/build_dashboard.py` → `dashboards/duraledger.json` | Dashboard as code. Edit the script, regenerate, commit both (CI checks they match) |
| `prove-alerts.sh` + `chaos.compose.yml` | Makes every alert fire for real against the running stack |

## The alerts

| Alert | Fires when | Proven live by |
|---|---|---|
| **LedgerInvariantViolated** (critical) | Reconciliation finds the ledger not netting to zero per currency, or a stored balance ≠ its entries | `UPDATE accounts SET balance_minor = balance_minor + 1` behind the ledger's back |
| **ReconciliationStale** (critical) | The check itself stopped running (> 5 min local, > 7 h on Cloud Run, or no data at all) | Initial delay set to 24 h |
| **OutboxBacklogStuck** (critical) | Oldest unpublished event > 60 s (local) / > 65 min (Cloud Run, past one hourly sweep) | `docker compose pause pubsub` |
| **OutboxPublishFailing** (warning) | Any outbox publish failure in 10 min | `docker compose pause pubsub` |
| **HighServerErrorRate** (critical) | > 5% 5xx for 2 min, above a traffic floor | `docker compose stop postgres` under load |
| **MoneyMovementSlow** (warning) | > 1% of money-moving POSTs over 1 s | Another transaction holding the account's row lock for 1.5 s |
| **TransferRetriesExhausted** (warning) | Optimistic locking refused a valid transfer | Optimistic mode, 1 attempt, 20 concurrent transfers |
| **ApiKeyProbing** (warning) | > 20/min wrong-key requests for 5 min | 1 request/s with a guessed key |
| **ClientsRateLimited** (info) | > 5/min 429s | 3 requests/s against a 60/min limit |

```
docker run --rm -v "$PWD/observability/prometheus/rules:/r" -w /r --entrypoint promtool \
  prom/prometheus:v3.14.0 test rules alerts.test.yaml     # unit tests (also in CI)
./observability/prove-alerts.sh                           # live, ~20 min; FROM=<phase> to resume
```

## Things that aren't obvious

- **Timers arrive in milliseconds.** The OTLP registry's base unit is ms, and Prometheus appends the unit: `http_server_requests_milliseconds_bucket`, not `_seconds_`.
- **Latency SLO without `histogram_quantile`.** There are only 6 SLO buckets (to stay inside the free tier's series budget), so a p99 would be an interpolated guess. "Share of requests over 1 s" reads the `le="1000"` bucket directly and is exact.
- **Counters can be born non-zero.** An instance counts from process start but first pushes up to 30 s later. `increase()` only sees change *between* samples, so a Cloud Run instance that cold-starts, handles a burst and scales away can report its whole burst as zero. The "did X happen" alerts add the birth value of series that didn't exist 10 minutes earlier. Found when the live proof didn't fire.
- **Everything aggregates `by (job)`.** Every process pushes its own series (`service.instance.id`), and every cold start is a new instance.
- **Scale-to-zero means no data is normal.** Gauges are read with `last_over_time` over the schedule window, and `ReconciliationStale` in production treats *no data at all* as stale.
