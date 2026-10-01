#!/usr/bin/env python3
"""Generates dashboards/duraledger.json. Edit the panels here, run `python3 build_dashboard.py`, and
commit both files. It's a script rather than hand-edited JSON because the JSON is ~1k lines of layout
noise, and the queries are what gets reviewed.

The datasource is a variable, so the same JSON imports into Grafana Cloud unchanged. Every query
aggregates `by (job)` or across instances: each process pushes its own series and Cloud Run cold starts
create new ones, so per-instance lines would just be clutter.
"""
import json
import pathlib

DS = {"type": "prometheus", "uid": "${datasource}"}
MONEY = 'method="POST", uri=~"/transfers|/deposits|/withdrawals|/fx-convert"'

panels, y = [], 0


def row(title):
    global y
    panels.append({"type": "row", "title": title, "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})
    y += 1


def panel(kind, title, targets, w, h=7, x=0, unit=None, desc=None, thresholds=None, extra=None):
    p = {
        "type": kind, "title": title, "datasource": DS,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [{"datasource": DS, "refId": chr(65 + i), "expr": e, "legendFormat": l}
                    for i, (e, l) in enumerate(targets)],
        "fieldConfig": {"defaults": {}, "overrides": []},
        "options": {},
    }
    d = p["fieldConfig"]["defaults"]
    if unit:
        d["unit"] = unit
    if desc:
        p["description"] = desc
    if thresholds:
        d["color"] = {"mode": "thresholds"}
        d["thresholds"] = {"mode": "absolute",
                           "steps": [{"color": c, "value": v} for v, c in thresholds]}
    if kind == "timeseries":
        d["custom"] = {"lineWidth": 2, "fillOpacity": 8, "showPoints": "never", "spanNulls": True}
        p["options"] = {"legend": {"displayMode": "list", "placement": "bottom"},
                        "tooltip": {"mode": "multi", "sort": "desc"}}
    if kind == "stat":
        p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"], "values": False},
                        "colorMode": "background", "graphMode": "none", "textMode": "value"}
    if extra:
        extra(p)
    panels.append(p)
    return p


GREEN, AMBER, RED = "green", "orange", "red"

# --- 1. Is the money right? ---------------------------------------------------------------------
row("Correctness: is the money right?")
panel("stat", "Invariant violations", [
    ('sum(last_over_time(duraledger_reconciliation_violations[7h]))', "")],
    w=6, unit="none", thresholds=[(None, GREEN), (1, RED)],
    desc="Latest reconciliation result: ledger not netting to zero per currency, or a stored balance "
         "that no longer equals its entries. Must be 0. Alert: LedgerInvariantViolated.")
panel("stat", "Last reconciliation", [
    ('time() - max(last_over_time(duraledger_reconciliation_last_run_timestamp_seconds[14h]))', "")],
    w=6, x=6, unit="s", thresholds=[(None, GREEN), (300, AMBER), (7 * 3600, RED)],
    desc="Seconds since any instance last ran the full-ledger check. Local: every 60s. Cloud Run: every "
         "6h via Cloud Scheduler. Alert: ReconciliationStale.")
panel("timeseries", "Violations by check", [
    ('max by (check) (duraledger_reconciliation_violations)', "{{check}}")],
    w=6, x=12, unit="none")
panel("timeseries", "Reconciliation duration", [
    ('sum(rate(duraledger_reconciliation_duration_milliseconds_sum[$__rate_interval])) '
     '/ sum(rate(duraledger_reconciliation_duration_milliseconds_count[$__rate_interval]))', "avg"),
    ('max(duraledger_reconciliation_duration_max_milliseconds)', "max")],
    w=6, x=18, unit="ms",
    desc="Full scan of ledger_entries in one REPEATABLE READ snapshot. Grows with the ledger: the signal "
         "for when to move to incremental (checkpointed) reconciliation.")
y += 7

# --- 2. Money movement --------------------------------------------------------------------------
row("Money movement")
panel("timeseries", "Requests/s by endpoint", [
    (f'sum by (uri) (rate(http_server_requests_milliseconds_count{{{MONEY}}}[$__rate_interval]))', "{{uri}}")],
    w=8, unit="reqps")
panel("timeseries", "Share slower than 1s (SLO: < 1%)", [
    (f'1 - sum(rate(http_server_requests_milliseconds_bucket{{{MONEY}, le="1000"}}[$__rate_interval])) '
     f'/ sum(rate(http_server_requests_milliseconds_count{{{MONEY}}}[$__rate_interval]))', "over 1s"),
    (f'1 - sum(rate(http_server_requests_milliseconds_bucket{{{MONEY}, le="250"}}[$__rate_interval])) '
     f'/ sum(rate(http_server_requests_milliseconds_count{{{MONEY}}}[$__rate_interval]))', "over 250ms")],
    w=8, x=8, unit="percentunit", thresholds=[(None, GREEN), (0.01, RED)],
    desc="Read straight off the SLO buckets (le=250/1000 are boundaries), so it's exact, unlike a p99 "
         "interpolated from 6 buckets. Alert: MoneyMovementSlow.",
    extra=lambda p: p["fieldConfig"]["defaults"]["custom"].update(
        {"thresholdsStyle": {"mode": "line"}, "axisSoftMin": 0, "axisSoftMax": 0.02}))
panel("timeseries", "Responses by status", [
    (f'sum by (status) (rate(http_server_requests_milliseconds_count{{{MONEY}}}[$__rate_interval]))', "{{status}}")],
    w=8, x=16, unit="reqps",
    desc="201 created (an idempotent replay returns the stored original, so also 201) · 409 conflict · "
         "422 business rule (insufficient funds). 5xx should be flat zero.")
y += 7
panel("timeseries", "5xx share by service", [
    # `or ... * 0`: until the first 5xx there is no 5xx series at all, and an empty numerator would make
    # the panel say "No data" instead of a reassuring 0%.
    ('(sum by (job) (rate(http_server_requests_milliseconds_count{status=~"5.."}[$__rate_interval])) '
     'or 0 * sum by (job) (rate(http_server_requests_milliseconds_count[$__rate_interval]))) '
     '/ sum by (job) (rate(http_server_requests_milliseconds_count[$__rate_interval]))', "{{job}}")],
    w=8, unit="percentunit", thresholds=[(None, GREEN), (0.05, RED)],
    desc="Alert: HighServerErrorRate (> 5% for 2m, with a traffic floor).",
    extra=lambda p: p["fieldConfig"]["defaults"]["custom"].update(
        {"thresholdsStyle": {"mode": "line"}, "axisSoftMin": 0, "axisSoftMax": 0.1}))
panel("timeseries", "Optimistic locking", [
    ('sum(rate(duraledger_transfers_optimistic_conflicts_total[$__rate_interval]))', "conflicts (retried)"),
    ('sum(rate(duraledger_transfers_optimistic_exhausted_total[$__rate_interval]))', "exhausted (409)")],
    w=8, x=8, unit="ops",
    desc="Only moves with duraledger.transfer.locking=optimistic. Conflicts are normal; exhausted means "
         "a valid transfer was refused. Alert: TransferRetriesExhausted.")
panel("timeseries", "DB connection pool", [
    ('sum by (job) (hikaricp_connections_active)', "{{job}} active"),
    ('sum by (job) (hikaricp_connections_pending)', "{{job}} waiting"),
    ('sum by (job) (hikaricp_connections_max)', "{{job}} max")],
    w=8, x=16, unit="none",
    desc="Waiting > 0 means requests queue for a connection: the first place slow money movement shows up. "
         "Cloud Run caps it at 4 per instance for Neon's free tier.")
y += 7

# --- 3. Events ----------------------------------------------------------------------------------
row("Events: outbox → Pub/Sub → activity feed")
panel("stat", "Oldest unpublished event", [
    ('max(duraledger_outbox_oldest_pending_seconds)', "")],
    w=6, unit="s", thresholds=[(None, GREEN), (5, AMBER), (60, RED)],
    desc="Age of the oldest committed-but-unpublished outbox row: how far behind the activity feed is. "
         "Alert: OutboxBacklogStuck.")
panel("timeseries", "Outbox", [
    ('sum(duraledger_outbox_pending)', "pending rows"),
    ('max(duraledger_outbox_oldest_pending_seconds)', "oldest pending (s)")],
    w=6, x=6)
panel("timeseries", "Relay failures", [
    ('sum(rate(duraledger_outbox_publish_failures_total[$__rate_interval]))', "failures")],
    w=6, x=12, unit="ops",
    desc="Relay ticks that failed (alert: OutboxPublishFailing). Nothing is lost: rows stay and are retried.")
panel("timeseries", "Events consumed", [
    ('sum by (result) (rate(duraledger_notifications_events_total[$__rate_interval]))', "{{result}}")],
    w=6, x=18, unit="ops",
    desc="applied · duplicate (at-least-once redelivery, absorbed by the idempotent consumer) · ignored.")
y += 7

# --- 4. Edge ------------------------------------------------------------------------------------
row("API edge + runtime")
panel("timeseries", "Rejected before the app", [
    ('sum by (job, reason) (rate(duraledger_api_rejected_total[$__rate_interval]))', "{{job}} {{reason}}")],
    w=8, unit="ops",
    desc="401/429 from ApiKeyFilter, which runs before Spring's HTTP metrics, so they never appear in "
         "http_server_requests. Alerts: ApiKeyProbing, ClientsRateLimited.")
panel("timeseries", "Heap used", [
    ('sum by (job) (jvm_memory_used_bytes{area="heap"})', "{{job}}")],
    w=8, x=8, unit="bytes")
panel("timeseries", "CPU", [
    ('avg by (job) (process_cpu_usage)', "{{job}}")],
    w=8, x=16, unit="percentunit")
y += 7

dashboard = {
    "uid": "duraledger",
    "title": "DuraLedger",
    "description": "Ledger correctness, money movement, event pipeline. Generated by build_dashboard.py.",
    "tags": ["duraledger"],
    "timezone": "browser",
    "schemaVersion": 39,
    "refresh": "30s",
    "time": {"from": "now-1h", "to": "now"},
    "templating": {"list": [{
        "name": "datasource", "label": "Data source", "type": "datasource", "query": "prometheus",
        "current": {"text": "Prometheus", "value": "prometheus"}, "hide": 0,
    }]},
    "annotations": {"list": []},
    "panels": panels,
}

out = pathlib.Path(__file__).with_name("dashboards") / "duraledger.json"
out.write_text(json.dumps(dashboard, indent=2) + "\n")
print(f"wrote {out} ({len(panels)} panels)")
