#!/usr/bin/env bash
# Makes every DuraLedger alert fire for real against the local stack, and waits for Prometheus to
# report it FIRING. The unit tests (rules/alerts.test.yaml) prove the PromQL; this proves the whole
# path: app -> OTLP push -> Prometheus -> rule -> alert, with the real metric names and labels.
#
#   docker compose --profile app up -d --build     # from duraledger/, jars built first
#   ./observability/prove-alerts.sh                # ~20 min; Prometheus Alerts tab: http://localhost:9090/alerts
#   FROM=errors ./observability/prove-alerts.sh    # resume at a phase: invariant | outbox | slow | errors | chaos
#
# Leaves the stack as it found it (normal config, data corrected). Needs curl, jq, uuidgen.
set -euo pipefail
cd "$(dirname "$0")/.."

LEDGER=http://localhost:8080
PROM=http://localhost:9090
KEY=local-dev-key
BG=()   # background load generators, killed on exit

say()  { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m   %s\033[0m\n' "$*"; }
json() { curl -s -H 'Content-Type: application/json' -H "X-Api-Key: $KEY" "$@"; }
uuid() { uuidgen | tr 'A-Z' 'a-z'; }
psql() { docker compose exec -T postgres psql -U duraledger -d duraledger -qtAc "$1"; }
cleanup() { for p in "${BG[@]:-}"; do [[ -n $p ]] && kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

# state of an alert: inactive | pending | firing
state() {
  curl -s "$PROM/api/v1/rules?type=alert" |
    jq -r --arg a "$1" '[.data.groups[].rules[] | select(.name == $a) | .state][0] // "missing"'
}
# wait_for ALERT STATE TIMEOUT_S
wait_for() {
  local start=$SECONDS
  while [[ $(state "$1") != "$2" ]]; do
    (( SECONDS - start > $3 )) && { echo "   TIMEOUT: $1 never became $2 (is $(state "$1"))"; exit 1; }
    sleep 5
  done
  ok "$1 -> $2 after $((SECONDS - start))s"
}
wait_healthy() { until curl -sf "$LEDGER/actuator/health" >/dev/null; do sleep 2; done; }

account() { # currency -> id of a new funded user account
  local id; id=$(json -X POST "$LEDGER/accounts" -d "{\"userId\":\"$(uuid)\",\"currency\":\"$1\"}" | jq -r .id)
  json -X POST "$LEDGER/deposits" -H "Idempotency-Key: $(uuid)" \
    -d "{\"accountId\":\"$id\",\"amountMinor\":1000000,\"currency\":\"$1\"}" >/dev/null
  echo "$id"
}
transfer() { # from to [amount]
  json -o /dev/null -w '%{http_code}\n' -X POST "$LEDGER/transfers" -H "Idempotency-Key: $(uuid)" \
    -d "{\"fromAccountId\":\"$1\",\"toAccountId\":\"$2\",\"amountMinor\":${3:-1},\"currency\":\"USD\"}"
}

FROM=${FROM:-invariant}
PHASES=(invariant outbox slow errors chaos)
run() { # true if phase $1 is at or after $FROM
  local seen=0 p; for p in "${PHASES[@]}"; do [[ $p == "$FROM" ]] && seen=1; [[ $p == "$1" ]] && return $((1 - seen)); done
}

wait_healthy
A=$(account USD); B=$(account USD)

if run invariant; then
# Runs through the first phases so the 5-minute `for` is served while the rest happens.
say "ApiKeyProbing: 1 request/s with a wrong key (alert: > 20/min for 5m)"
( while :; do curl -s -o /dev/null -H 'X-Api-Key: guessed' "$LEDGER/accounts/$A"; sleep 1; done ) & BG+=($!)

say "LedgerInvariantViolated: corrupt a stored balance behind the ledger's back"
psql "UPDATE accounts SET balance_minor = balance_minor + 1 WHERE id = '$A'"
curl -s -X POST "$LEDGER/actuator/reconciliation" | jq -c '{balanceMismatches}'
wait_for LedgerInvariantViolated firing 90
psql "UPDATE accounts SET balance_minor = balance_minor - 1 WHERE id = '$A'"
curl -s -X POST "$LEDGER/actuator/reconciliation" | jq -c '{balanceMismatches}'
wait_for LedgerInvariantViolated inactive 90
fi

if run outbox; then
say "OutboxPublishFailing + OutboxBacklogStuck: freeze Pub/Sub, keep moving money"
docker compose pause pubsub >/dev/null
transfer "$A" "$B" >/dev/null
wait_for OutboxPublishFailing firing 180
wait_for OutboxBacklogStuck firing 240
docker compose unpause pubsub >/dev/null
wait_for OutboxBacklogStuck inactive 120
ok "outbox drained: $(psql "SELECT count(*) FROM outbox WHERE published_at IS NULL") unpublished"
fi

if run slow; then
say "MoneyMovementSlow: another transaction holds B's row lock for 1.5s at a time"
( while :; do psql "BEGIN; SELECT 1 FROM accounts WHERE id = '$B' FOR UPDATE; SELECT pg_sleep(1.5); COMMIT;" >/dev/null; done ) & LOCKER=$!
( while :; do transfer "$A" "$B" >/dev/null; done ) & MOVER=$!
wait_for MoneyMovementSlow firing 360
kill $LOCKER $MOVER 2>/dev/null; wait $LOCKER $MOVER 2>/dev/null || true
fi

if run invariant; then
wait_for ApiKeyProbing firing 420
fi
cleanup; BG=()

if run errors; then
say "HighServerErrorRate: stop Postgres under read traffic from 8 clients"
for _ in $(seq 8); do ( while :; do json -o /dev/null "$LEDGER/accounts/$A/balance"; sleep 0.5; done ) & BG+=($!); done
sleep 20
docker compose stop postgres >/dev/null
wait_for HighServerErrorRate firing 300
docker compose start postgres >/dev/null
cleanup; BG=()
wait_healthy
fi

say "Chaos config for ledger-service (observability/chaos.compose.yml)"
docker compose -f docker-compose.yml -f observability/chaos.compose.yml --profile app up -d ledger-service >/dev/null
wait_healthy

say "TransferRetriesExhausted: 20 concurrent transfers from one account, optimistic locking, 1 attempt"
for _ in $(seq 20); do transfer "$A" "$B" & done | sort | uniq -c
wait
wait_for TransferRetriesExhausted firing 120

say "ClientsRateLimited: 3 requests/s against a 60/min limit"
( while :; do json -o /dev/null "$LEDGER/accounts/$A"; sleep 0.33; done ) & BG+=($!)
wait_for ClientsRateLimited firing 300
kill "${BG[0]}"; BG=()

say "ReconciliationStale: the job never runs (initial delay 24h)"
wait_for ReconciliationStale firing 240

say "Restore normal config"
docker compose --profile app up -d ledger-service >/dev/null
wait_healthy
wait_for ReconciliationStale inactive 180

say "All alerts from phase '$FROM' on fired for real, and the stack is back to normal"
