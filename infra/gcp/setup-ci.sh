#!/usr/bin/env bash
# One-time (idempotent): lets GitHub Actions deploy with NO long-lived key. Workload Identity
# Federation: the workflow presents GitHub's OIDC token, Google checks it came from this repo's main
# branch, and hands back short-lived credentials for the deployer service account.
#   ./infra/gcp/setup-ci.sh      (after setup.sh)
# Prints the two values to save as repository variables GCP_WORKLOAD_IDENTITY_PROVIDER and
# GCP_DEPLOYER_SA (not secrets: useless outside a matching GitHub run).
set -euo pipefail
cd "$(dirname "$0")"; source config.sh
GITHUB_REPO=${GITHUB_REPO:-BhanuRana/DuraLedger}
POOL=github; PROVIDER=github-actions
exists() { "$@" >/dev/null 2>&1; }
step() { printf '\n== %s\n' "$*"; }

step "Deployer service account: exactly what infra/gcp/deploy.sh does, nothing more"
exists gcloud iam service-accounts describe "$(sa deployer)" --project=$PROJECT ||
  gcloud iam service-accounts create deployer --project=$PROJECT --display-name="DuraLedger CI deployer"
for role in roles/run.admin roles/artifactregistry.writer roles/cloudscheduler.admin \
            roles/pubsub.admin roles/browser; do
  # run.admin: services and migration jobs; pubsub.admin: the subscription's IAM binding;
  # browser: config.sh reads the project number
  gcloud projects add-iam-policy-binding $PROJECT --member="serviceAccount:$(sa deployer)" \
    --role=$role --condition=None >/dev/null
done
# Deploying a service as a runtime identity needs actAs on that identity, granted per account, not
# project-wide, so the deployer can't run anything as, say, the Compute default account.
for name in ledger-svc notification-svc migrator scheduler-invoker pubsub-pusher; do
  gcloud iam service-accounts add-iam-policy-binding "$(sa $name)" --project=$PROJECT \
    --member="serviceAccount:$(sa deployer)" --role=roles/iam.serviceAccountUser >/dev/null
done

step "Workload Identity pool and GitHub provider, restricted to $GITHUB_REPO"
exists gcloud iam workload-identity-pools describe $POOL --location=global --project=$PROJECT ||
  gcloud iam workload-identity-pools create $POOL --location=global --project=$PROJECT \
    --display-name="GitHub Actions"
exists gcloud iam workload-identity-pools providers describe $PROVIDER --workload-identity-pool=$POOL \
    --location=global --project=$PROJECT ||
  gcloud iam workload-identity-pools providers create-oidc $PROVIDER --workload-identity-pool=$POOL \
    --location=global --project=$PROJECT --issuer-uri=https://token.actions.githubusercontent.com \
    --attribute-mapping=google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref \
    --attribute-condition="assertion.repository == '$GITHUB_REPO' && assertion.ref == 'refs/heads/main'"

step "Only that repo's main branch may impersonate the deployer"
POOL_ID="projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/$POOL"
gcloud iam service-accounts add-iam-policy-binding "$(sa deployer)" --project=$PROJECT \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/$POOL_ID/attribute.repository/$GITHUB_REPO" >/dev/null

echo
echo "workload_identity_provider: $POOL_ID/providers/$PROVIDER"
echo "service_account:            $(sa deployer)"
