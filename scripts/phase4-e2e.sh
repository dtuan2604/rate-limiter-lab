#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

export ADMIN_BEARER_TOKEN="${ADMIN_BEARER_TOKEN:-phase4-$(openssl rand -hex 24)}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-phase4-$(openssl rand -hex 24)}"
export POSTGRES_USER="${POSTGRES_USER:-rate_limiter}"
export POLICY_ACCEPTANCE_CONTROLS_ENABLED=true
export POLICY_RECONCILIATION_INTERVAL="${POLICY_RECONCILIATION_INTERVAL:-5s}"

authorization="Authorization: Bearer ${ADMIN_BEARER_TOKEN}"
admin_url="http://localhost:8081/admin/api/v1/policies"
evidence_directory="$(mktemp -d /tmp/rate-limiter-phase4-e2e.XXXXXX)"

cleanup() {
  curl --silent --request POST \
    --header "${authorization}" \
    http://localhost:8083/internal/policy-events/resume >/dev/null 2>&1 || true
  docker compose down --volumes --remove-orphans
  rm -rf "${evidence_directory}"
}
trap cleanup EXIT

wait_for_snapshot() {
  local port="$1"
  local expected_version="$2"
  local attempts=100
  while (( attempts > 0 )); do
    local observed
    observed="$(
      curl --silent --header "${authorization}" \
        "http://localhost:${port}/internal/policy-snapshot" \
        | jq --raw-output '.activePolicies[]? | select(.policyId == "catalog-client-fixed-window") | .version' \
        || true
    )"
    if [[ "${observed}" == "${expected_version}" ]]; then
      return 0
    fi
    sleep 0.1
    attempts=$((attempts - 1))
  done
  printf 'Gateway %s did not install policy version %s.\n' "${port}" "${expected_version}" >&2
  return 1
}

wait_for_all_snapshots() {
  local version="$1"
  for port in 8081 8082 8083; do
    wait_for_snapshot "${port}" "${version}"
  done
}

await_fresh_redis_window() {
  local redis_time
  redis_time="$(docker compose exec --no-TTY redis redis-cli --raw TIME | tr -d '\r')"
  local redis_seconds
  redis_seconds="$(printf '%s\n' "${redis_time}" | sed -n '1p')"
  local redis_microseconds
  redis_microseconds="$(printf '%s\n' "${redis_time}" | sed -n '2p')"
  local now_milliseconds=$((redis_seconds * 1000 + redis_microseconds / 1000))
  local remaining=$((10000 - now_milliseconds % 10000))
  if (( remaining < 9500 )); then
    sleep "$(awk -v milliseconds="${remaining}" 'BEGIN { print milliseconds / 1000 + 0.2 }')"
  fi
}

request() {
  local client_id="$1"
  local evidence_name="$2"
  curl --silent --show-error \
    --dump-header "${evidence_directory}/${evidence_name}.headers" \
    --output "${evidence_directory}/${evidence_name}.json" \
    --write-out '%{http_code}' \
    --header "X-Client-Id: ${client_id}" \
    --header "X-Correlation-Id: ${evidence_name}" \
    http://localhost:8080/proxy/catalog/items
}

assert_status_distribution() {
  local prefix="$1"
  local client_id="$2"
  local allowed="$3"
  local rejected="$4"
  local requests=$((allowed + rejected))
  local statuses="${evidence_directory}/${prefix}.statuses"
  : >"${statuses}"
  for number in $(seq 1 "${requests}"); do
    request "${client_id}" "${prefix}-${number}" >>"${statuses}"
    printf '\n' >>"${statuses}"
  done
  local observed_allowed
  observed_allowed="$(grep -c '^200$' "${statuses}" || true)"
  local observed_rejected
  observed_rejected="$(grep -c '^429$' "${statuses}" || true)"
  if [[ "${observed_allowed}" != "${allowed}" || "${observed_rejected}" != "${rejected}" ]]; then
    printf 'Expected %s allowed and %s rejected responses; observed statuses:\n' \
      "${allowed}" "${rejected}" >&2
    sed -n '1,20p' "${statuses}" >&2
    return 1
  fi
}

reset_catalog_count() {
  curl --fail --silent --show-error --request POST \
    http://localhost:8101/_test/request-count/reset \
    | jq --exit-status '.catalogRequests == 0' >/dev/null
}

catalog_count() {
  curl --fail --silent --show-error \
    http://localhost:8101/_test/request-count \
    | jq --raw-output '.catalogRequests'
}

snapshot_revision() {
  local port="$1"
  curl --fail --silent --show-error \
    --header "${authorization}" \
    "http://localhost:${port}/internal/policy-snapshot" \
    | jq --raw-output '.snapshotRevision'
}

clone_version() {
  local source="$1"
  local target="$2"
  curl --fail --silent --show-error \
    --request POST \
    --header "${authorization}" \
    --header 'Content-Type: application/json' \
    --data "{\"version\":${target},\"sourceVersion\":${source}}" \
    "${admin_url}/catalog-client-fixed-window/versions" >/dev/null
}

update_limit() {
  local version="$1"
  local limit="$2"
  curl --fail --silent --show-error \
    --request PUT \
    --header "${authorization}" \
    --header 'Content-Type: application/json' \
    --header 'If-Match: "0"' \
    --data "{\"description\":\"Catalog requests per client and route\",\"match\":{\"routeId\":\"catalog.items\",\"path\":\"/proxy/catalog/items\",\"methods\":[\"GET\"]},\"identity\":{\"components\":[{\"type\":\"HEADER\",\"name\":\"X-Client-Id\"},{\"type\":\"ROUTE\"}]},\"algorithm\":{\"type\":\"FIXED_WINDOW\",\"configuration\":{\"limit\":${limit},\"windowMilliseconds\":10000}},\"failureMode\":\"FAIL_CLOSED\",\"priority\":100}" \
    "${admin_url}/catalog-client-fixed-window/versions/${version}" >/dev/null
}

activate_version() {
  local version="$1"
  curl --fail --silent --show-error \
    --request POST \
    --header "${authorization}" \
    "${admin_url}/catalog-client-fixed-window/versions/${version}/activate"
}

expected_services=$'gateway-1\ngateway-2\ngateway-3\nload-balancer\nmock-catalog-service\npostgres\nredis'
actual_services="$(docker compose config --services | LC_ALL=C sort)"
[[ "${actual_services}" == "${expected_services}" ]]

docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
docker compose build gateway-1 gateway-2 gateway-3 mock-catalog-service
docker compose up --detach --wait --wait-timeout 300

scripts/bootstrap-catalog-policy.sh
wait_for_all_snapshots 1

# Scenario 1: version 1 is authoritative and enforced across replicas.
await_fresh_redis_window
reset_catalog_count
assert_status_distribution version-1 phase4-version-1-client 5 1
[[ "$(catalog_count)" == "5" ]]
instances="$({
  for number in 1 2 3 4 5 6; do
    awk 'tolower($1) == "x-gateway-instance:" {gsub("\r", "", $2); print $2}' \
      "${evidence_directory}/version-1-${number}.headers"
  done
} | LC_ALL=C sort -u)"
[[ "$(printf '%s\n' "${instances}" | grep -c .)" -ge 2 ]]
version_one_key="$(docker compose exec --no-TTY redis redis-cli --raw KEYS 'ratelimit:*:v=1:*' | tr -d '\r')"
[[ -n "${version_one_key}" ]]

# Scenario 2: activate version 2 without changing any gateway container ID.
clone_version 1 2
update_limit 2 2
containers_before="$(docker compose ps --quiet gateway-1 gateway-2 gateway-3)"
activation_two="$(activate_version 2)"
printf '%s' "${activation_two}" | jq --exit-status \
  '.version == 2 and .policySetRevision == 2 and .runtimeState == "FRESH_VERSION_STATE"' >/dev/null
# Activation must not scan/delete the previous version's runtime keys. They may expire naturally later.
docker compose exec --no-TTY redis redis-cli --raw EXISTS "${version_one_key}" \
  | tr -d '\r' | grep -qx '1'
wait_for_all_snapshots 2
containers_after="$(docker compose ps --quiet gateway-1 gateway-2 gateway-3)"
[[ "${containers_before}" == "${containers_after}" ]]
for port in 8081 8082 8083; do
  [[ "$(snapshot_revision "${port}")" == "2" ]]
done
await_fresh_redis_window
reset_catalog_count
assert_status_distribution version-2 phase4-version-2-client 2 1
[[ "$(catalog_count)" == "2" ]]
version_two_instances="$({
  for number in 1 2 3; do
    awk 'tolower($1) == "x-gateway-instance:" {gsub("\r", "", $2); print $2}' \
      "${evidence_directory}/version-2-${number}.headers"
  done
} | LC_ALL=C sort -u)"
[[ "$(printf '%s\n' "${version_two_instances}" | grep -c .)" -ge 2 ]]
version_two_key="$(docker compose exec --no-TTY redis redis-cli --raw KEYS 'ratelimit:*:v=2:*' \
  | tr -d '\r' | head -1)"
[[ -n "${version_two_key}" ]]
[[ "${version_one_key}" != "${version_two_key}" ]]

# Scenario 3: one missed Pub/Sub event is repaired only by reconciliation.
clone_version 2 3
update_limit 3 3
curl --fail --silent --show-error --request POST \
  --header "${authorization}" \
  http://localhost:8083/internal/policy-events/pause >/dev/null
activate_version 3 >/dev/null
wait_for_snapshot 8081 3
wait_for_snapshot 8082 3
gateway_three_before="$(
  curl --fail --silent --show-error --header "${authorization}" \
    http://localhost:8083/internal/policy-snapshot \
    | jq --raw-output '.activePolicies[0].version'
)"
[[ "${gateway_three_before}" == "2" ]]
curl --fail --silent --show-error --request POST \
  --header "${authorization}" \
  http://localhost:8083/internal/policy-events/resume >/dev/null
wait_for_snapshot 8083 3
for attempt in 1 2 3; do
  [[ "$(
    curl --fail --silent --show-error --header "${authorization}" \
      http://localhost:8083/internal/policy-snapshot \
      | jq --raw-output '.activePolicies[0].version'
  )" == "3" ]]
done

# Scenario 4: restart loads PostgreSQL state and retains Redis runtime state.
request phase4-restart-client restart-before >/dev/null
restart_key="$(docker compose exec --no-TTY redis redis-cli --raw KEYS 'ratelimit:*:v=3:*' \
  | tr -d '\r' | head -1)"
restart_value="$(docker compose exec --no-TTY redis redis-cli --raw GET "${restart_key}" | tr -d '\r')"
docker compose restart gateway-1
wait_for_snapshot 8081 3
[[ "$(docker compose exec --no-TTY redis redis-cli --raw GET "${restart_key}" | tr -d '\r')" == "${restart_value}" ]]

# Scenario 5: invalid draft input changes neither snapshots nor outbox state.
outbox_before="$(docker compose exec --no-TTY postgres psql --tuples-only --no-align \
  --username "${POSTGRES_USER}" --dbname rate_limiter \
  --command 'SELECT count(*) FROM policy_event_outbox' | tr -d '\r')"
invalid_status="$(
  curl --silent --show-error --output "${evidence_directory}/invalid.json" \
    --write-out '%{http_code}' --request PUT \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --header 'If-Match: "0"' \
    --data '{"description":null,"match":{"routeId":"catalog.items","path":"/proxy/catalog/items","methods":["GET"]},"identity":{"components":[{"type":"HEADER","name":"X-Client-Id"},{"type":"ROUTE"}]},"algorithm":{"type":"FIXED_WINDOW","configuration":{"limit":0,"windowMilliseconds":10000}},"failureMode":"FAIL_CLOSED","priority":100}' \
    "${admin_url}/catalog-client-fixed-window/versions/3"
)"
[[ "${invalid_status}" == "409" || "${invalid_status}" == "422" ]]
outbox_after="$(docker compose exec --no-TTY postgres psql --tuples-only --no-align \
  --username "${POSTGRES_USER}" --dbname rate_limiter \
  --command 'SELECT count(*) FROM policy_event_outbox' | tr -d '\r')"
[[ "${outbox_before}" == "${outbox_after}" ]]
wait_for_all_snapshots 3

printf 'Phase 4 dynamic activation, reconciliation, restart, and invalid-input scenarios passed.\n'
