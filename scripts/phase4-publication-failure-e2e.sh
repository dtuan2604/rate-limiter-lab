#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

export ADMIN_BEARER_TOKEN="${ADMIN_BEARER_TOKEN:-phase4-publication-$(openssl rand -hex 24)}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-phase4-publication-$(openssl rand -hex 24)}"
export POSTGRES_USER="${POSTGRES_USER:-rate_limiter}"
export POLICY_RECONCILIATION_INTERVAL=2s
authorization="Authorization: Bearer ${ADMIN_BEARER_TOKEN}"
admin_url="http://localhost:8081/admin/api/v1/policies"
evidence_directory="$(mktemp -d /tmp/rate-limiter-phase4-publication.XXXXXX)"

cleanup() {
  docker compose start redis >/dev/null 2>&1 || true
  docker compose down --volumes --remove-orphans
  rm -rf "${evidence_directory}"
}
trap cleanup EXIT

wait_for_version() {
  local port="$1"
  local expected="$2"
  local attempts=120
  while (( attempts > 0 )); do
    local version
    version="$(
      curl --silent --header "${authorization}" \
        "http://localhost:${port}/internal/policy-snapshot" \
        | jq --raw-output '.activePolicies[0].version' 2>/dev/null || true
    )"
    if [[ "${version}" == "${expected}" ]]; then
      return 0
    fi
    sleep 0.1
    attempts=$((attempts - 1))
  done
  printf 'Gateway %s did not converge to version %s.\n' "${port}" "${expected}" >&2
  return 1
}

docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
docker compose build gateway-1 gateway-2 gateway-3 mock-catalog-service
docker compose up --detach --wait --wait-timeout 300
scripts/bootstrap-catalog-policy.sh
for port in 8081 8082 8083; do
  wait_for_version "${port}" 1
done

# Invalid active-version update is rejected and creates no event.
outbox_before="$(docker compose exec --no-TTY postgres psql --tuples-only --no-align \
  --username "${POSTGRES_USER}" --dbname rate_limiter \
  --command 'SELECT count(*) FROM policy_event_outbox' | tr -d '\r')"
invalid_status="$(
  curl --silent --show-error --output "${evidence_directory}/invalid.json" \
    --write-out '%{http_code}' --request PUT \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --header 'If-Match: "0"' \
    --data '{"description":null,"match":{"routeId":"catalog.items","path":"/proxy/catalog/items","methods":["GET"]},"identity":{"components":[{"type":"HEADER","name":"X-Client-Id"},{"type":"ROUTE"}]},"algorithm":{"type":"FIXED_WINDOW","configuration":{"limit":0,"windowMilliseconds":10000}},"failureMode":"FAIL_CLOSED","priority":100}' \
    "${admin_url}/catalog-client-fixed-window/versions/1"
)"
[[ "${invalid_status}" == "409" ]]
outbox_after="$(docker compose exec --no-TTY postgres psql --tuples-only --no-align \
  --username "${POSTGRES_USER}" --dbname rate_limiter \
  --command 'SELECT count(*) FROM policy_event_outbox' | tr -d '\r')"
[[ "${outbox_before}" == "${outbox_after}" ]]

curl --fail --silent --show-error --request POST \
  --header "${authorization}" --header 'Content-Type: application/json' \
  --data '{"version":2,"sourceVersion":1}' \
  "${admin_url}/catalog-client-fixed-window/versions" >/dev/null
curl --fail --silent --show-error --request PUT \
  --header "${authorization}" --header 'Content-Type: application/json' \
  --header 'If-Match: "0"' \
  --data '{"description":"Publication failure policy","match":{"routeId":"catalog.items","path":"/proxy/catalog/items","methods":["GET"]},"identity":{"components":[{"type":"HEADER","name":"X-Client-Id"},{"type":"ROUTE"}]},"algorithm":{"type":"FIXED_WINDOW","configuration":{"limit":2,"windowMilliseconds":10000}},"failureMode":"FAIL_CLOSED","priority":100}' \
  "${admin_url}/catalog-client-fixed-window/versions/2" >/dev/null

# PostgreSQL commit succeeds while Redis publication is unavailable.
docker compose stop redis
activation="$(curl --fail --silent --show-error --request POST \
  --header "${authorization}" \
  "${admin_url}/catalog-client-fixed-window/versions/2/activate")"
printf '%s' "${activation}" | jq --exit-status \
  '.version == 2 and .policySetRevision == 2 and .propagationStatus == "PENDING"' >/dev/null
active_database_version="$(docker compose exec --no-TTY postgres psql --tuples-only --no-align \
  --username "${POSTGRES_USER}" --dbname rate_limiter \
  --command "SELECT version FROM policy_versions WHERE lifecycle_status = 'ACTIVE'" | tr -d '\r')"
[[ "${active_database_version}" == "2" ]]

attempts=60
while (( attempts > 0 )); do
  retry_count="$(docker compose exec --no-TTY postgres psql --tuples-only --no-align \
    --username "${POSTGRES_USER}" --dbname rate_limiter \
    --command "SELECT attempt_count FROM policy_event_outbox WHERE policy_set_revision = 2" \
    | tr -d '\r')"
  if (( retry_count > 0 )); then
    break
  fi
  sleep 0.1
  attempts=$((attempts - 1))
done
(( retry_count > 0 ))

# Reconciliation keeps PostgreSQL authoritative even before Redis returns.
for port in 8081 8082 8083; do
  wait_for_version "${port}" 2
done
docker compose start redis

attempts=120
while (( attempts > 0 )); do
  publication_status="$(docker compose exec --no-TTY postgres psql --tuples-only --no-align \
    --username "${POSTGRES_USER}" --dbname rate_limiter \
    --command "SELECT publication_status FROM policy_event_outbox WHERE policy_set_revision = 2" \
    | tr -d '\r')"
  if [[ "${publication_status}" == "PUBLISHED" ]]; then
    break
  fi
  sleep 0.1
  attempts=$((attempts - 1))
done
[[ "${publication_status}" == "PUBLISHED" ]]
for port in 8081 8082 8083; do
  wait_for_version "${port}" 2
done

printf 'Phase 4 invalid-activation and publication-failure recovery scenarios passed.\n'
