#!/usr/bin/env bash
# Brings DuraLedger up on a local kind cluster, in dependency order:
# cluster, images, infra (postgres, pubsub), migration Jobs (to completion), then apps.
# Idempotent; re-run after code changes to rebuild and roll out new images.
set -euo pipefail
cd "$(dirname "$0")"; ROOT=$(cd .. && pwd)
step() { printf '\n== %s\n' "$*"; }

step "Cluster"
kind get clusters | grep -qx duraledger || kind create cluster --config kind-cluster.yaml
kubectl config use-context kind-duraledger >/dev/null

step "Build jars and images (native architecture, no emulation) and load them into the node"
(cd "$ROOT/ledger-service" && ./mvnw -q package -DskipTests)
(cd "$ROOT/notification-service" && ./mvnw -q package -DskipTests)
docker build -q -t duraledger/ledger-service:dev "$ROOT/ledger-service" >/dev/null
docker build -q -t duraledger/notification-service:dev "$ROOT/notification-service" >/dev/null
docker build -q -t duraledger/ledger-migrations:dev -f "$ROOT/ledger-service/migrations.Dockerfile" "$ROOT/ledger-service/src/main/resources/db/migration" >/dev/null
docker build -q -t duraledger/notification-migrations:dev -f "$ROOT/notification-service/migrations.Dockerfile" "$ROOT/notification-service/src/main/resources/db/migration" >/dev/null
kind load docker-image --name duraledger duraledger/ledger-service:dev duraledger/notification-service:dev \
  duraledger/ledger-migrations:dev duraledger/notification-migrations:dev >/dev/null
echo "images loaded"

step "Infra: postgres (StatefulSet) and the Pub/Sub emulator"
kubectl apply -k infra >/dev/null
kubectl -n duraledger rollout status statefulset/postgres --timeout=180s
kubectl -n duraledger rollout status deployment/pubsub --timeout=600s   # first pull of the emulator image is big

step "Migrations: Jobs must COMPLETE before any app pod starts"
kubectl -n duraledger delete job ledger-migrate notification-migrate --ignore-not-found >/dev/null
kubectl apply -k migrations >/dev/null
kubectl -n duraledger wait --for=condition=complete job/ledger-migrate job/notification-migrate --timeout=180s

step "Apps"
kubectl apply -k apps >/dev/null
kubectl -n duraledger rollout restart deployment/ledger-service deployment/notification-service >/dev/null  # pick up rebuilt :dev images
kubectl -n duraledger rollout status deployment/ledger-service --timeout=300s
kubectl -n duraledger rollout status deployment/notification-service --timeout=300s

echo; kubectl -n duraledger get pods -o wide | awk '{print $1, $2, $3, $5}' | column -t
echo; echo "ledger: http://localhost:18080   notification: http://localhost:18081"
echo "demo:   LEDGER=http://localhost:18080 NOTIFY=http://localhost:18081 ./scripts/demo.sh"
