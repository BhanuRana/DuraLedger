#!/usr/bin/env bash
# One-time (idempotent) infrastructure for DuraLedger on GCP. Creates only free-tier-friendly
# resources; nothing here bills while idle. Run: ./infra/gcp/setup.sh
# Before the first run: a budget alert on the billing account, and the Neon secrets
# (./scripts/store-neon-secret.sh). See docs/decisions/0006.
set -euo pipefail
cd "$(dirname "$0")"; source config.sh
exists() { "$@" >/dev/null 2>&1; }
step() { printf '\n== %s\n' "$*"; }

step "Artifact Registry (keep only the 2 newest images per package: stay near the 0.5 GB free tier)"
exists gcloud artifacts repositories describe $REPO --location=$REGION --project=$PROJECT ||
  gcloud artifacts repositories create $REPO --repository-format=docker --location=$REGION --project=$PROJECT
cat > /tmp/duraledger-ar-cleanup.json <<'JSON'
[{"name": "keep-2-newest", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 2}},
 {"name": "delete-rest", "action": {"type": "Delete"}, "condition": {"tagState": "any"}}]
JSON
gcloud artifacts repositories set-cleanup-policies $REPO --location=$REGION --project=$PROJECT \
  --policy=/tmp/duraledger-ar-cleanup.json --no-dry-run >/dev/null

step "Service accounts: one per role, least privilege"
for name in ledger-svc notification-svc migrator scheduler-invoker pubsub-pusher; do
  exists gcloud iam service-accounts describe "$(sa $name)" --project=$PROJECT ||
    gcloud iam service-accounts create $name --project=$PROJECT --display-name="DuraLedger $name"
done

step "Secrets: readable only by the runtimes and the migrator"
for secret in db-url db-username db-password; do
  for name in ledger-svc notification-svc migrator; do
    gcloud secrets add-iam-policy-binding $secret --project=$PROJECT \
      --member="serviceAccount:$(sa $name)" --role=roles/secretmanager.secretAccessor >/dev/null
  done
done

step "API key: generated once, random, never printed (read it with: gcloud secrets versions access latest --secret=api-key)"
exists gcloud secrets describe api-key --project=$PROJECT ||
  openssl rand -base64 33 | tr -d '\n/+=' | gcloud secrets create api-key --project=$PROJECT \
    --replication-policy=automatic --data-file=- >/dev/null
for name in ledger-svc notification-svc; do
  gcloud secrets add-iam-policy-binding api-key --project=$PROJECT \
    --member="serviceAccount:$(sa $name)" --role=roles/secretmanager.secretAccessor >/dev/null
done

step "Grafana Cloud token, if stored: readable by the runtimes"
if exists gcloud secrets describe grafana-otlp-authorization --project=$PROJECT; then
  for name in ledger-svc notification-svc; do
    gcloud secrets add-iam-policy-binding grafana-otlp-authorization --project=$PROJECT \
      --member="serviceAccount:$(sa $name)" --role=roles/secretmanager.secretAccessor >/dev/null
  done
fi

step "Pub/Sub topics (+ dead-letter topic for poison messages)"
for t in $TOPIC $DLQ_TOPIC; do
  exists gcloud pubsub topics describe $t --project=$PROJECT || gcloud pubsub topics create $t --project=$PROJECT
done
# ledger-service may publish to its topic and nothing else
gcloud pubsub topics add-iam-policy-binding $TOPIC --project=$PROJECT \
  --member="serviceAccount:$(sa ledger-svc)" --role=roles/pubsub.publisher >/dev/null
# the Pub/Sub service agent moves undeliverable messages to the DLQ
PUBSUB_AGENT="serviceAccount:service-$PROJECT_NUMBER@gcp-sa-pubsub.iam.gserviceaccount.com"
gcloud pubsub topics add-iam-policy-binding $DLQ_TOPIC --project=$PROJECT \
  --member="$PUBSUB_AGENT" --role=roles/pubsub.publisher >/dev/null
exists gcloud pubsub subscriptions describe $DLQ_TOPIC.hold --project=$PROJECT ||
  gcloud pubsub subscriptions create $DLQ_TOPIC.hold --topic=$DLQ_TOPIC --project=$PROJECT \
    --message-retention-duration=7d   # so dead-lettered messages can be inspected, not lost

echo; echo "setup done"
