#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

export ADMIN_BEARER_TOKEN="${ADMIN_BEARER_TOKEN:-phase6-resilience-$(openssl rand -hex 24)}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-phase6-resilience-$(openssl rand -hex 24)}"
export POSTGRES_USER="${POSTGRES_USER:-rate_limiter}"
export POLICY_ACCEPTANCE_CONTROLS_ENABLED=true
export POLICY_RECONCILIATION_INTERVAL=5s
export SPRING_PROFILES_ACTIVE=acceptance

authorization="Authorization: Bearer ${ADMIN_BEARER_TOKEN}"
admin_url="http://localhost:8081/admin/api/v1/policies/catalog-client-fixed-window"
evidence_directory="$(mktemp -d /tmp/rate-limiter-phase6-resilience.XXXXXX)"

cleanup() {
  docker compose unpause redis >/dev/null 2>&1 || true
  curl --silent --request POST --header "${authorization}" \
    http://localhost:8083/internal/policy-events/resume >/dev/null 2>&1 || true
  docker compose down --volumes --remove-orphans
  rm -rf "${evidence_directory}"
}
trap cleanup EXIT

wait_for_snapshot() {
  local port="$1" version="$2" algorithm="$3" attempts=160
  while (( attempts > 0 )); do
    if curl --fail --silent --header "${authorization}" \
      "http://localhost:${port}/internal/policy-snapshot" \
      | jq --exit-status \
        ".activePolicies[] | select(.policyId == \"catalog-client-fixed-window\") | .version == ${version} and .algorithm == \"${algorithm}\"" \
        >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
    attempts=$((attempts - 1))
  done
  printf 'Gateway %s did not converge to %s version %s.\n' \
    "${port}" "${algorithm}" "${version}" >&2
  return 1
}

wait_for_all_snapshots() {
  for port in 8081 8082 8083; do
    wait_for_snapshot "${port}" "$1" "$2"
  done
}

wait_for_readiness() {
  local port="$1" expected="$2" attempts=60 status
  while (( attempts > 0 )); do
    status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
      "http://localhost:${port}/actuator/health/readiness" || true)"
    if [[ "${status}" == "${expected}" ]]; then
      return 0
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
  printf 'Gateway %s readiness did not become %s.\n' "${port}" "${expected}" >&2
  return 1
}

clone_version() {
  curl --fail --silent --show-error --request POST \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --data "{\"version\":$2,\"sourceVersion\":$1}" \
    "${admin_url}/versions" >/dev/null
}

update_sliding() {
  local version="$1" limit="$2" failure_mode="$3"
  curl --fail --silent --show-error --request PUT \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --header 'If-Match: "0"' \
    --data "{\"description\":\"Sliding Counter resilience proof\",\"match\":{\"routeId\":\"catalog.items\",\"path\":\"/proxy/catalog/items\",\"methods\":[\"GET\"]},\"identity\":{\"components\":[{\"type\":\"HEADER\",\"name\":\"X-Client-Id\"},{\"type\":\"ROUTE\"}]},\"algorithm\":{\"type\":\"SLIDING_WINDOW_COUNTER\",\"configuration\":{\"limit\":${limit},\"window\":\"1h\",\"requestCost\":1}},\"failureMode\":\"${failure_mode}\",\"priority\":100}" \
    "${admin_url}/versions/${version}" >/dev/null
}

activate_version() {
  curl --fail --silent --show-error --request POST \
    --header "${authorization}" "${admin_url}/versions/$1/activate" >/dev/null
}

reset_catalog_count() {
  curl --fail --silent --show-error --request POST \
    http://localhost:8101/_test/request-count/reset \
    | jq --exit-status '.catalogRequests == 0' >/dev/null
}

catalog_count() {
  curl --fail --silent --show-error http://localhost:8101/_test/request-count \
    | jq --raw-output '.catalogRequests'
}

request() {
  local port="$1" client_id="$2" evidence_name="$3"
  curl --silent --show-error \
    --dump-header "${evidence_directory}/${evidence_name}.headers" \
    --output "${evidence_directory}/${evidence_name}.json" \
    --write-out '%{http_code}' \
    --header "X-Client-Id: ${client_id}" \
    --header "X-Correlation-Id: ${evidence_name}" \
    "http://localhost:${port}/proxy/catalog/items"
}

sliding_key() {
  docker compose exec --no-TTY redis redis-cli --raw \
    KEYS "ratelimit:*:v=$1:a=sliding-window-counter:*" | tr -d '\r' | head -1
}

docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
docker compose up --build --detach --wait --wait-timeout 300
scripts/bootstrap-catalog-policy.sh
wait_for_all_snapshots 1 FIXED_WINDOW

# Scenario 7: a missed activation event is repaired by reconciliation without regression.
clone_version 1 2
update_sliding 2 5 FAIL_CLOSED
curl --fail --silent --show-error --request POST --header "${authorization}" \
  http://localhost:8083/internal/policy-events/pause >/dev/null
activate_version 2
wait_for_snapshot 8081 2 SLIDING_WINDOW_COUNTER
wait_for_snapshot 8082 2 SLIDING_WINDOW_COUNTER
wait_for_snapshot 8083 1 FIXED_WINDOW
curl --fail --silent --show-error --request POST --header "${authorization}" \
  http://localhost:8083/internal/policy-events/resume >/dev/null
wait_for_snapshot 8083 2 SLIDING_WINDOW_COUNTER
sleep 6
wait_for_all_snapshots 2 SLIDING_WINDOW_COUNTER

# Scenario 8: weighted state survives restart and replica removal/restoration.
reset_catalog_count
[[ "$(request 8080 phase6-restart-client restart-1)" == "200" ]]
[[ "$(request 8080 phase6-restart-client restart-2)" == "200" ]]
state_key="$(sliding_key 2)"
[[ "$(docker compose exec --no-TTY redis redis-cli --raw HGET "${state_key}" current_count | tr -d '\r')" == "2" ]]
docker compose restart gateway-1
wait_for_snapshot 8081 2 SLIDING_WINDOW_COUNTER
docker compose stop --timeout 1 gateway-3
for number in 3 4 5; do
  [[ "$(request 8080 phase6-restart-client "restart-${number}")" == "200" ]]
done
[[ "$(request 8080 phase6-restart-client restart-6)" == "429" ]]
[[ "$(catalog_count)" == "5" ]]
[[ "$(docker compose exec --no-TTY redis redis-cli --raw HGET "${state_key}" current_count | tr -d '\r')" == "5" ]]
(( $(docker compose exec --no-TTY redis redis-cli --raw PTTL "${state_key}" | tr -d '\r') > 0 ))
docker compose start gateway-3
wait_for_snapshot 8083 2 SLIDING_WINDOW_COUNTER
[[ "$(request 8083 phase6-restart-client restart-restored)" == "429" ]]

# A new activated version uses an independent namespace for the same identity.
clone_version 2 3
update_sliding 3 3 FAIL_CLOSED
activate_version 3
wait_for_all_snapshots 3 SLIDING_WINDOW_COUNTER
reset_catalog_count
for number in 1 2 3; do
  [[ "$(request 8080 phase6-restart-client "version-isolation-${number}")" == "200" ]]
done
[[ "$(request 8080 phase6-restart-client version-isolation-rejected)" == "429" ]]
[[ "$(catalog_count)" == "3" ]]
[[ -n "$(sliding_key 2)" && -n "$(sliding_key 3)" ]]

# Scenario 9a: FAIL_OPEN forwards degraded traffic without fabricated capacity.
clone_version 3 4
update_sliding 4 5 FAIL_OPEN
activate_version 4
wait_for_all_snapshots 4 SLIDING_WINDOW_COUNTER
reset_catalog_count
[[ "$(request 8081 fail-open-sliding fail-open-before)" == "200" ]]
docker compose pause redis
wait_for_readiness 8081 200
for number in 1 2 3; do
  [[ "$(request 8081 fail-open-sliding "fail-open-outage-${number}")" == "200" ]]
  grep -Eiq '^X-RateLimit-Degraded:[[:space:]]*true' \
    "${evidence_directory}/fail-open-outage-${number}.headers"
  ! grep -Eiq '^RateLimit-Remaining:' \
    "${evidence_directory}/fail-open-outage-${number}.headers"
done
[[ "$(catalog_count)" == "4" ]]
docker compose unpause redis
wait_for_readiness 8081 200
for number in 2 3 4 5; do
  [[ "$(request 8081 fail-open-sliding "fail-open-recovery-${number}")" == "200" ]]
done
[[ "$(request 8081 fail-open-sliding fail-open-recovery-6)" == "429" ]]
[[ "$(catalog_count)" == "8" ]]

# Scenario 9b: FAIL_CLOSED returns correlated 503 and never forwards during failure.
clone_version 4 5
update_sliding 5 5 FAIL_CLOSED
activate_version 5
wait_for_all_snapshots 5 SLIDING_WINDOW_COUNTER
reset_catalog_count
[[ "$(request 8081 fail-closed-sliding fail-closed-before)" == "200" ]]
docker compose pause redis
wait_for_readiness 8081 503
[[ "$(request 8081 fail-closed-sliding fail-closed-outage)" == "503" ]]
jq --exit-status \
  '.status == 503 and .error == "RATE_LIMIT_STATE_UNAVAILABLE" and .correlationId == "fail-closed-outage"' \
  "${evidence_directory}/fail-closed-outage.json" >/dev/null
[[ "$(catalog_count)" == "1" ]]
docker compose unpause redis
wait_for_readiness 8081 200
[[ "$(request 8081 fail-closed-sliding fail-closed-recovery)" == "200" ]]
[[ "$(catalog_count)" == "2" ]]

printf 'Phase 6 missed-event, version-isolation, restart/scale, and Redis failure scenarios passed.\n'
