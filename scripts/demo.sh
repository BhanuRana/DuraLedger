#!/usr/bin/env bash
# End-to-end demo against the two running services:
#   (cd ledger-service && ./mvnw spring-boot:run)        # :8080, starts Postgres + Pub/Sub emulator
#   (cd notification-service && ./mvnw spring-boot:run)  # :8081
#   ./scripts/demo.sh
#
# Against Cloud Run (the API key is in Secret Manager; steps 11-12 then use Cloud Scheduler):
#   LEDGER=<ledger URL> NOTIFY=<notification URL> \
#   API_KEY=$(gcloud secrets versions access latest --secret=api-key --project=duraledger-bhanu) ./scripts/demo.sh
#
# Needs curl, jq and uuidgen. Shows: the API key check, derived balances, an idempotent retry, key
# reuse refused, an overdraft refused, a concurrent double-submit executing once, a withdrawal, FX
# through the pools, the events reaching the second service, and a live reconciliation of the ledger.
set -euo pipefail

LEDGER=${LEDGER:-http://localhost:8080}
NOTIFY=${NOTIFY:-http://localhost:8081}
API_KEY=${API_KEY:-local-dev-key}

step() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
json() { curl -s -H 'Content-Type: application/json' -H "X-Api-Key: $API_KEY" "$@"; }
uuid() { uuidgen | tr 'A-Z' 'a-z'; }
balance() { json "$LEDGER/accounts/$1/balance" | jq -r '.balanceMinor'; }

step "0. No API key: refused before any database work"
curl -s -o /dev/null -w "GET /accounts/... without X-Api-Key -> HTTP %{http_code}\n" "$LEDGER/accounts/$(uuid)"

step "1. Alice (USD and HKD wallets) and bob (USD)"
ALICE_USER=$(uuid)
ALICE=$(json -X POST "$LEDGER/accounts" -d "{\"userId\":\"$ALICE_USER\",\"currency\":\"USD\"}" | jq -r .id)
ALICE_HKD=$(json -X POST "$LEDGER/accounts" -d "{\"userId\":\"$ALICE_USER\",\"currency\":\"HKD\"}" | jq -r .id)
BOB=$(json -X POST "$LEDGER/accounts" -d "{\"userId\":\"$(uuid)\",\"currency\":\"USD\"}" | jq -r .id)
echo "alice USD=$ALICE"
echo "alice HKD=$ALICE_HKD"
echo "bob   USD=$BOB"

step "2. Deposit 100.00 USD to alice (posted against the USD external-clearing account)"
json -X POST "$LEDGER/deposits" -H "Idempotency-Key: $(uuid)" \
  -d "{\"accountId\":\"$ALICE\",\"amountMinor\":10000,\"currency\":\"USD\"}" | jq -c '{transactionId, type, amountMinor}'

step "3. Transfer 25.00 alice -> bob"
KEY=$(uuid)
TRANSFER="{\"fromAccountId\":\"$ALICE\",\"toAccountId\":\"$BOB\",\"amountMinor\":2500,\"currency\":\"USD\"}"
TX=$(json -X POST "$LEDGER/transfers" -H "Idempotency-Key: $KEY" -d "$TRANSFER" | jq -r .transactionId)
echo "transaction $TX, as two ledger legs:"
json "$LEDGER/transactions/$TX" | jq -r '.entries[] | "  \(.direction)\t\(.amountMinor)\t\(.currency)\t\(.accountId)"'

step "4. The client retries the SAME request (e.g. after a timeout): original response replayed, no second debit"
curl -s -D - -o /dev/null -H 'Content-Type: application/json' -H "X-Api-Key: $API_KEY" -X POST "$LEDGER/transfers" \
  -H "Idempotency-Key: $KEY" -d "$TRANSFER" | grep -iE '^(HTTP|idempotent-replayed)'
echo "alice balance: $(balance "$ALICE") (expected 7500)"

step "5. Same key, different amount: refused (key reuse)"
json -X POST "$LEDGER/transfers" -H "Idempotency-Key: $KEY" \
  -d "{\"fromAccountId\":\"$ALICE\",\"toAccountId\":\"$BOB\",\"amountMinor\":9999,\"currency\":\"USD\"}" | jq -c '{status, type}'

step "6. Overdraft attempt (1000.00): 422 insufficient-funds"
json -X POST "$LEDGER/transfers" -H "Idempotency-Key: $(uuid)" \
  -d "{\"fromAccountId\":\"$ALICE\",\"toAccountId\":\"$BOB\",\"amountMinor\":100000,\"currency\":\"USD\"}" | jq -c '{status, type, detail}'

step "7. Double-submit race: 10 identical requests fired at once with one key"
RACE_KEY=$(uuid)
RACE="{\"fromAccountId\":\"$ALICE\",\"toAccountId\":\"$BOB\",\"amountMinor\":1000,\"currency\":\"USD\"}"
for _ in $(seq 10); do
  json -X POST "$LEDGER/transfers" -H "Idempotency-Key: $RACE_KEY" -d "$RACE" | jq -r .transactionId &
done | sort | uniq -c
wait
echo "^ one distinct transactionId: executed exactly once"
echo "alice balance: $(balance "$ALICE") (expected 6500)"

step "8. Withdraw 5.00 USD (debit alice, credit the USD external-clearing account)"
json -X POST "$LEDGER/withdrawals" -H "Idempotency-Key: $(uuid)" \
  -d "{\"accountId\":\"$ALICE\",\"amountMinor\":500,\"currency\":\"USD\"}" | jq -c '{type, amountMinor, currency}'
echo "alice balance: $(balance "$ALICE") (expected 6000)"

step "9. FX: convert 20.00 USD to HKD (four legs through the USD and HKD FX pools; the rate is recorded)"
FX=$(json -X POST "$LEDGER/fx-convert" -H "Idempotency-Key: $(uuid)" \
  -d "{\"fromAccountId\":\"$ALICE\",\"toCurrency\":\"HKD\",\"amountMinor\":2000}")
echo "$FX" | jq -c '{fromAmountMinor, fromCurrency, toAmountMinor, toCurrency, rate}'
json "$LEDGER/transactions/$(echo "$FX" | jq -r .transactionId)" | jq -r '.entries[] | "  \(.direction)\t\(.amountMinor)\t\(.currency)\t\(.accountId)"'
echo "alice USD: $(balance "$ALICE") (expected 4000)   alice HKD: $(balance "$ALICE_HKD") (expected 15600)"

step "10. The same movements, as seen by notification-service (fed only by ledger.events via Pub/Sub)"
for _ in $(seq 30); do
  [ "$(json "$NOTIFY/accounts/$ALICE/activity" | jq length)" -ge 5 ] && break
  sleep 0.5
done
echo "alice USD:"; json "$NOTIFY/accounts/$ALICE/activity" | jq -r '.[] | "  \(.direction)\t\(.amountMinor) \(.currency)\t\(.type)"'
echo "alice HKD:"; json "$NOTIFY/accounts/$ALICE_HKD/activity" | jq -r '.[] | "  \(.direction)\t\(.amountMinor) \(.currency)\t\(.type)"'
echo "bob USD:";   json "$NOTIFY/accounts/$BOB/activity"   | jq -r '.[] | "  \(.direction)\t\(.amountMinor) \(.currency)\t\(.type)"'

if curl -sf -o /dev/null "$LEDGER/actuator/prometheus"; then   # local: the actuator is open
  step "11. Outbox relay health"
  curl -s "$LEDGER/actuator/prometheus" | grep -E '^duraledger_outbox_(pending|oldest)'

  step "12. Reconciliation: the whole ledger nets to zero per currency, and every stored balance matches its entries"
  curl -s -X POST "$LEDGER/actuator/reconciliation" | jq -c '{entriesChecked, currencyImbalances, balanceMismatches, duration}'
  curl -s "$LEDGER/actuator/prometheus" | grep -E '^duraledger_reconciliation_violations'
else                                                             # Cloud Run: actuator closed, tasks OIDC-protected
  step "11. Cloud Run: the task endpoint refuses anonymous callers"
  curl -s -o /dev/null -w "POST /internal/tasks/reconcile without a token -> HTTP %{http_code}\n" -X POST "$LEDGER/internal/tasks/reconcile"

  step "12. Reconciliation, run by Cloud Scheduler with a Google-signed token"
  PROJECT=${PROJECT:-duraledger-bhanu}; REGION=${REGION:-asia-southeast1}
  SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  gcloud scheduler jobs run reconcile --location="$REGION" --project="$PROJECT" --quiet
  LINE=""
  for _ in $(seq 20); do
    LINE=$(gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=ledger-service AND textPayload:\"econciliation\" AND timestamp>=\"$SINCE\"" \
      --project="$PROJECT" --limit=1 --format='value(textPayload)' 2>/dev/null | sed -E 's/^.*(Reconciliation|RECONCILIATION)/\1/')
    [ -n "$LINE" ] && break
    sleep 3
  done
  echo "ledger-service log: ${LINE:-<not visible yet; check Cloud Logging>}"
fi
