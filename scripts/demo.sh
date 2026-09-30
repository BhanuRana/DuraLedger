#!/usr/bin/env bash
# End-to-end demo against the two running services:
#   (cd ledger-service && ./mvnw spring-boot:run)        # :8080, starts Postgres + Pub/Sub emulator
#   (cd notification-service && ./mvnw spring-boot:run)  # :8081
#   ./scripts/demo.sh
#
# Needs curl, jq and uuidgen. Shows: derived balances, an idempotent retry, key reuse refused, an
# overdraft refused, a concurrent double-submit executing once, and the events reaching the second service.
set -euo pipefail

LEDGER=${LEDGER:-http://localhost:8080}
NOTIFY=${NOTIFY:-http://localhost:8081}

step() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
json() { curl -s -H 'Content-Type: application/json' "$@"; }
uuid() { uuidgen | tr 'A-Z' 'a-z'; }
balance() { json "$LEDGER/accounts/$1/balance" | jq -r '.balanceMinor'; }

step "1. Two USD wallets: alice and bob"
ALICE=$(json -X POST "$LEDGER/accounts" -d "{\"userId\":\"$(uuid)\",\"currency\":\"USD\"}" | jq -r .id)
BOB=$(json -X POST "$LEDGER/accounts" -d "{\"userId\":\"$(uuid)\",\"currency\":\"USD\"}" | jq -r .id)
echo "alice=$ALICE"
echo "bob  =$BOB"

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
curl -s -D - -o /dev/null -H 'Content-Type: application/json' -X POST "$LEDGER/transfers" \
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

step "8. The same movements, as seen by notification-service (fed only by ledger.events via Pub/Sub)"
for _ in $(seq 30); do
  [ "$(json "$NOTIFY/accounts/$ALICE/activity" | jq length)" -ge 3 ] && break
  sleep 0.5
done
echo "alice:"; json "$NOTIFY/accounts/$ALICE/activity" | jq -r '.[] | "  \(.direction)\t\(.amountMinor) \(.currency)\t\(.type)"'
echo "bob:";   json "$NOTIFY/accounts/$BOB/activity"   | jq -r '.[] | "  \(.direction)\t\(.amountMinor) \(.currency)\t\(.type)"'

step "9. Outbox relay health"
curl -s "$LEDGER/actuator/prometheus" | grep -E '^duraledger_outbox_(pending|oldest)'
