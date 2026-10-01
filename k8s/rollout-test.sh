#!/usr/bin/env bash
# Zero-downtime check: keep firing transfers while Kubernetes replaces every ledger-service pod,
# then count responses. Pass = every request got a 201 (no 5xx, no connection errors).
#   ./k8s/rollout-test.sh            (cluster from ./k8s/up.sh)
set -euo pipefail
LEDGER=${LEDGER:-http://localhost:18080}
API_KEY=${API_KEY:-local-dev-key}
WORKERS=${WORKERS:-8}
OUT=$(mktemp)
uuid() { uuidgen | tr 'A-Z' 'a-z'; }
json() { curl -s -H 'Content-Type: application/json' -H "X-Api-Key: $API_KEY" "$@"; }

FROM=$(json -X POST "$LEDGER/accounts" -d "{\"userId\":\"$(uuid)\",\"currency\":\"USD\"}" | jq -r .id)
TO=$(json -X POST "$LEDGER/accounts" -d "{\"userId\":\"$(uuid)\",\"currency\":\"USD\"}" | jq -r .id)
json -X POST "$LEDGER/deposits" -H "Idempotency-Key: $(uuid)" \
  -d "{\"accountId\":\"$FROM\",\"amountMinor\":100000000,\"currency\":\"USD\"}" >/dev/null

echo "firing transfers with $WORKERS workers..."
STOP=$(mktemp); rm "$STOP"
worker() {
  while [ ! -e "$STOP" ]; do
    # %{http_code} is 000 when the connection itself failed (refused or reset): a failure too
    curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 -H 'Content-Type: application/json' -H "X-Api-Key: $API_KEY" \
      -H "Idempotency-Key: $(uuid)" -X POST "$LEDGER/transfers" \
      -d "{\"fromAccountId\":\"$FROM\",\"toAccountId\":\"$TO\",\"amountMinor\":1,\"currency\":\"USD\"}" >> "$OUT" || echo 000 >> "$OUT"
  done
}
for _ in $(seq "$WORKERS"); do worker & done

sleep 3
echo "rolling out new ledger-service pods (kubectl rollout restart)..."
kubectl -n duraledger rollout restart deployment/ledger-service >/dev/null
kubectl -n duraledger rollout status deployment/ledger-service --timeout=300s
sleep 3
touch "$STOP"; wait

echo; echo "responses during the rollout:"; sort "$OUT" | uniq -c
TOTAL=$(wc -l < "$OUT" | tr -d ' '); OK=$(grep -c '^201$' "$OUT" || true)
echo "total=$TOTAL ok=$OK failed=$((TOTAL - OK))"
BAL_TO=$(json "$LEDGER/accounts/$TO/balance" | jq -r .balanceMinor)
echo "receiver balance=$BAL_TO (must equal ok=$OK: every 201 moved exactly 1 cent, nothing else did)"
rm -f "$OUT" "$STOP"
[ "$OK" = "$TOTAL" ] && [ "$BAL_TO" = "$OK" ]
