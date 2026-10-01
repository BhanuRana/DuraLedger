# shellcheck shell=bash disable=SC2034  # sourced by the other scripts
# Shared settings for infra/gcp/*.sh
PROJECT=${PROJECT:-duraledger-bhanu}
REGION=${REGION:-asia-southeast1}                       # Singapore: same city as the Neon database
REPO=duraledger
REGISTRY="$REGION-docker.pkg.dev/$PROJECT/$REPO"
TOPIC=ledger.events
DLQ_TOPIC=ledger.events.dlq
SUBSCRIPTION=notification-service.ledger-events
sa() { echo "$1@$PROJECT.iam.gserviceaccount.com"; }
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')
# Cloud Run URLs are deterministic: https://<service>-<project-number>.<region>.run.app
url() { echo "https://$1-$PROJECT_NUMBER.$REGION.run.app"; }
