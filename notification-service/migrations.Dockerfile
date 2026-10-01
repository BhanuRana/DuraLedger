# Flyway-only image: this service's SQL migrations, no application code. Runs as a Cloud Run Job
# before each rollout (infra/gcp/deploy.sh). Build context = src/main/resources/db/migration.
# Version pinned to the flyway-core that Spring Boot manages (12.4.0), so what's tested is what runs.
FROM flyway/flyway:12.4.0-alpine
COPY . /flyway/sql
