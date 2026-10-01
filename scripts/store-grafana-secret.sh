#!/usr/bin/env bash
# Stores the Grafana Cloud access-policy token in GCP Secret Manager without it touching disk, shell
# history, or chat (same pattern as store-neon-secret.sh). Writes two secrets:
#   grafana-otlp-authorization  "Basic base64(<OTLP instance id>:<token>)". Cloud Run sends it as the
#                               OTLP Authorization header (the header needs the encoded pair, and
#                               --set-secrets can only inject a secret verbatim)
#   grafana-token               the raw token, for uploading the alert rules to the Mimir ruler
#
#   GRAFANA_OTLP_INSTANCE_ID=123456 ./scripts/store-grafana-secret.sh     # run in your own terminal
#
# The instance id is on the stack's "OpenTelemetry (OTLP)" connection page (not secret). The token
# needs scopes metrics:write, rules:read, rules:write.
set -euo pipefail
PROJECT=${PROJECT:-duraledger-bhanu}
: "${GRAFANA_OTLP_INSTANCE_ID:?set GRAFANA_OTLP_INSTANCE_ID (numeric, from the OTLP connection page)}"
[[ $GRAFANA_OTLP_INSTANCE_ID =~ ^[0-9]+$ ]] || { echo "GRAFANA_OTLP_INSTANCE_ID must be numeric" >&2; exit 1; }

read -rsp "Paste the Grafana Cloud access-policy token (input hidden): " TOKEN; echo
[[ $TOKEN == glc_* ]] || { echo "expected a token starting with glc_" >&2; exit 1; }

store() { # name, value on stdin: create on first run, add a version afterwards
  if gcloud secrets describe "$1" --project="$PROJECT" >/dev/null 2>&1; then
    gcloud secrets versions add "$1" --project="$PROJECT" --data-file=- >/dev/null
  else
    gcloud secrets create "$1" --project="$PROJECT" --replication-policy=automatic --data-file=- >/dev/null
  fi
  echo "stored $1"
}

printf 'Basic %s' "$(printf '%s:%s' "$GRAFANA_OTLP_INSTANCE_ID" "$TOKEN" | base64 | tr -d '\n')" | store grafana-otlp-authorization
printf '%s' "$TOKEN" | store grafana-token
unset TOKEN
