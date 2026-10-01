#!/usr/bin/env bash
# Stores the Neon connection string in GCP Secret Manager without it ever touching disk, shell
# history, or chat. Prompts silently, converts Neon's `postgresql://user:pass@host/db?...` URI into
# the three values Spring Boot needs (JDBC URL, username, password), and writes each as a secret.
#
#   ./scripts/store-neon-secret.sh            # run in your own terminal
#
# Use the DIRECT (non-pooled) string: Flyway holds a lock across statements, which a transaction
# pooler (PgBouncer) would break.
set -euo pipefail
PROJECT=${PROJECT:-duraledger-bhanu}

read -rsp "Paste the Neon DIRECT connection string (input hidden): " NEON_URL; echo

parse() { python3 -c '
import sys, urllib.parse as u
p = u.urlparse(sys.argv[1]); part = sys.argv[2]
if p.scheme not in ("postgres", "postgresql"): sys.exit("not a postgres URI")
if "-pooler" in (p.hostname or ""): sys.exit("this is the POOLED string; use the direct one (Connection pooling off)")
print({"url": f"jdbc:postgresql://{p.hostname}:{p.port or 5432}{p.path}?sslmode=require",
       "user": u.unquote(p.username or ""), "password": u.unquote(p.password or "")}[part], end="")
' "$NEON_URL" "$1"; }

store() { # create the secret on first run, add a new version afterwards
  if gcloud secrets describe "$1" --project="$PROJECT" >/dev/null 2>&1; then
    parse "$2" | gcloud secrets versions add "$1" --project="$PROJECT" --data-file=- >/dev/null
  else
    parse "$2" | gcloud secrets create "$1" --project="$PROJECT" --replication-policy=automatic --data-file=- >/dev/null
  fi
  echo "stored $1"
}

parse url >/dev/null   # validate before writing anything
store db-url url
store db-username user
store db-password password
unset NEON_URL
