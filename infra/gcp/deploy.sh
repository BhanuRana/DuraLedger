#!/usr/bin/env bash
# Build, push, migrate, deploy, then wire events and schedules. Idempotent; safe to re-run.
#   ./infra/gcp/deploy.sh            (tests are the CI's job; this builds with -DskipTests)
set -euo pipefail
cd "$(dirname "$0")"; source config.sh
ROOT=$(cd ../.. && pwd)
TAG=$(git -C "$ROOT" rev-parse --short HEAD)$(git -C "$ROOT" diff --quiet -- . || echo "-dirty")
LEDGER_URL=$(url ledger-service); NOTIFY_URL=$(url notification-service)
step() { printf '\n== %s\n' "$*"; }
exists() { "$@" >/dev/null 2>&1; }

step "Build jars (ledger build needs Docker running: jOOQ codegen)"
(cd "$ROOT/ledger-service" && ./mvnw -q package -DskipTests)
(cd "$ROOT/notification-service" && ./mvnw -q package -DskipTests)

step "Build and push linux/amd64 images, tag $TAG (Cloud Run is amd64; this Mac is arm64)"
gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet >/dev/null 2>&1
build() { docker buildx build --platform linux/amd64 --provenance=false -q -t "$REGISTRY/$1:$TAG" "${@:2}" --push >/dev/null && echo "pushed $1:$TAG"; }
build ledger-service "$ROOT/ledger-service"
build notification-service "$ROOT/notification-service"
build ledger-migrations -f "$ROOT/ledger-service/migrations.Dockerfile" "$ROOT/ledger-service/src/main/resources/db/migration"
build notification-migrations -f "$ROOT/notification-service/migrations.Dockerfile" "$ROOT/notification-service/src/main/resources/db/migration"

step "Run migrations as Cloud Run Jobs, BEFORE any new code serves traffic"
DB_SECRETS="FLYWAY_URL=db-url:latest,FLYWAY_USER=db-username:latest,FLYWAY_PASSWORD=db-password:latest"
migrate() { # name image [extra env]
  gcloud run jobs deploy "$1" --image="$REGISTRY/$2:$TAG" --region=$REGION --project=$PROJECT \
    --service-account="$(sa migrator)" --set-secrets="$DB_SECRETS" ${3:+--set-env-vars=$3} \
    --args=migrate --max-retries=0 --task-timeout=300s --quiet >/dev/null
  gcloud run jobs execute "$1" --region=$REGION --project=$PROJECT --wait --quiet >/dev/null && echo "migrated: $1"
}
migrate ledger-migrate ledger-migrations
migrate notification-migrate notification-migrations "FLYWAY_SCHEMAS=notification,FLYWAY_DEFAULT_SCHEMA=notification"

step "Deploy services (scale to zero; max 2 instances caps cost and Neon connections)"
APP_SECRETS="SPRING_DATASOURCE_URL=db-url:latest,SPRING_DATASOURCE_USERNAME=db-username:latest,SPRING_DATASOURCE_PASSWORD=db-password:latest"
COMMON=(--region="$REGION" --project="$PROJECT" --allow-unauthenticated --min-instances=0 --max-instances=2
        --cpu=1 --memory=1Gi --cpu-boost --timeout=60s --set-secrets="$APP_SECRETS" --quiet)
gcloud run deploy ledger-service --image="$REGISTRY/ledger-service:$TAG" --service-account="$(sa ledger-svc)" "${COMMON[@]}" \
  --set-env-vars="SPRING_PROFILES_ACTIVE=gcp,DURALEDGER_TASKS_AUDIENCE=$LEDGER_URL,DURALEDGER_TASKS_ALLOWED_INVOKERS=$(sa scheduler-invoker)" >/dev/null
echo "ledger-service       -> $LEDGER_URL"
gcloud run deploy notification-service --image="$REGISTRY/notification-service:$TAG" --service-account="$(sa notification-svc)" "${COMMON[@]}" \
  --set-env-vars="SPRING_PROFILES_ACTIVE=gcp,DURALEDGER_EVENTS_PUSH_AUDIENCE=$NOTIFY_URL,DURALEDGER_EVENTS_PUSH_INVOKERS=$(sa pubsub-pusher)" >/dev/null
echo "notification-service -> $NOTIFY_URL"

step "Push subscription: Pub/Sub -> notification-service (OIDC-signed, retries with backoff, DLQ after 10)"
SUB_ARGS=(--push-endpoint="$NOTIFY_URL/pubsub/push" --push-auth-service-account="$(sa pubsub-pusher)"
          --push-auth-token-audience="$NOTIFY_URL" --dead-letter-topic="$DLQ_TOPIC" --max-delivery-attempts=10
          --min-retry-delay=10s --max-retry-delay=600s --ack-deadline=30 --project="$PROJECT")
if exists gcloud pubsub subscriptions describe $SUBSCRIPTION --project=$PROJECT; then
  gcloud pubsub subscriptions update $SUBSCRIPTION "${SUB_ARGS[@]}" >/dev/null
else
  gcloud pubsub subscriptions create $SUBSCRIPTION --topic=$TOPIC "${SUB_ARGS[@]}" >/dev/null
fi
gcloud pubsub subscriptions add-iam-policy-binding $SUBSCRIPTION --project=$PROJECT \
  --member="serviceAccount:service-$PROJECT_NUMBER@gcp-sa-pubsub.iam.gserviceaccount.com" --role=roles/pubsub.subscriber >/dev/null
echo "subscription $SUBSCRIPTION -> $NOTIFY_URL/pubsub/push"

step "Cloud Scheduler (2 of the 3 free jobs). Sparse on purpose: every call wakes Cloud Run AND Neon"
schedule() { # name cron path
  local args=(--location="$REGION" --project="$PROJECT" --schedule="$2" --time-zone=Asia/Hong_Kong --uri="$LEDGER_URL$3"
              --http-method=POST --oidc-service-account-email="$(sa scheduler-invoker)" --oidc-token-audience="$LEDGER_URL")
  if exists gcloud scheduler jobs describe "$1" --location=$REGION --project=$PROJECT; then
    gcloud scheduler jobs update http "$1" "${args[@]}" >/dev/null
  else
    gcloud scheduler jobs create http "$1" "${args[@]}" >/dev/null
  fi
  echo "scheduled $1 ($2) -> $3"
}
schedule outbox-sweep "0 * * * *"     /internal/tasks/outbox-sweep   # safety net; normal path is publish-after-commit
schedule reconcile    "30 */6 * * *"  /internal/tasks/reconcile

echo; echo "deployed $TAG"
echo "demo: LEDGER=$LEDGER_URL NOTIFY=$NOTIFY_URL ./scripts/demo.sh"
