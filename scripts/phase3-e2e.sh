#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

expected_services=$'gateway-1\ngateway-2\ngateway-3\nload-balancer\nmock-catalog-service\nredis'
actual_services="$(docker compose config --services | LC_ALL=C sort)"
if [[ "${actual_services}" != "${expected_services}" ]]; then
  printf 'Phase 3 Compose services differ from the required topology:\n%s\n' \
    "${actual_services}" >&2
  exit 1
fi

evidence_directory="$(mktemp -d /tmp/rate-limiter-phase3-e2e.XXXXXX)"
cleanup() {
  docker compose unpause gateway-3 >/dev/null 2>&1 || true
  docker compose down --volumes --remove-orphans
  rm -rf "${evidence_directory}"
}
trap cleanup EXIT

wait_for_healthy() {
  local service="$1"
  local attempts=60
  while (( attempts > 0 )); do
    local container_id
    container_id="$(docker compose ps --quiet "${service}")"
    if [[ -n "${container_id}" ]]; then
      local health
      health="$(docker inspect --format '{{.State.Health.Status}}' "${container_id}")"
      if [[ "${health}" == "healthy" ]]; then
        return 0
      fi
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
  printf '%s did not become healthy.\n' "${service}" >&2
  return 1
}

reset_state() {
  docker compose exec --no-TTY redis redis-cli FLUSHDB >/dev/null
  curl --fail --silent --show-error \
    --request POST \
    http://localhost:8101/_test/request-count/reset \
    | jq --exit-status '.catalogRequests == 0' >/dev/null
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

instance_for() {
  local evidence_name="$1"
  awk 'tolower($1) == "x-gateway-instance:" {gsub("\r", "", $2); print $2}' \
    "${evidence_directory}/${evidence_name}.headers"
}

assert_catalog_count() {
  local expected="$1"
  curl --fail --silent --show-error \
    http://localhost:8101/_test/request-count \
    | jq --exit-status ".catalogRequests == ${expected}" >/dev/null
}

assert_five_of_six() {
  local prefix="$1"
  local client_id="$2"
  local statuses_file="${evidence_directory}/${prefix}.statuses"
  : >"${statuses_file}"
  for number in 1 2 3 4 5 6; do
    request "${client_id}" "${prefix}-${number}" >>"${statuses_file}"
    printf '\n' >>"${statuses_file}"
  done
  [[ "$(grep -c '^200$' "${statuses_file}")" == "5" ]]
  [[ "$(grep -c '^429$' "${statuses_file}")" == "1" ]]
}

docker compose config --quiet
if grep -Eiq 'cookie|stick-table|stick on|balance[[:space:]]+source' deploy/haproxy/haproxy.cfg; then
  printf 'HAProxy configuration contains a session-affinity directive.\n' >&2
  exit 1
fi

docker compose up --build --detach --wait --wait-timeout 300
for service in redis mock-catalog-service gateway-1 gateway-2 gateway-3 load-balancer; do
  wait_for_healthy "${service}"
done

# Scenario 1: one five-request limit is shared globally across three replicas.
await_fresh_redis_window
reset_state
assert_five_of_six "shared-global" "phase3-shared-client"
assert_catalog_count 5
instances="$({
  for number in 1 2 3 4 5 6; do
    instance_for "shared-global-${number}"
  done
} | LC_ALL=C sort -u)"
if [[ "$(printf '%s\n' "${instances}" | grep -c .)" -lt 2 ]]; then
  printf 'Traffic did not reach at least two gateway replicas: %s\n' "${instances}" >&2
  exit 1
fi
redis_key="$(docker compose exec --no-TTY redis redis-cli --raw KEYS 'ratelimit:*')"
[[ "$(printf '%s\n' "${redis_key}" | grep -c .)" == "1" ]]
[[ "${redis_key}" != *"phase3-shared-client"* ]]
[[ "$(docker compose exec --no-TTY redis redis-cli --raw GET "${redis_key}" | tr -d '\r')" == "5" ]]
redis_ttl="$(docker compose exec --no-TTY redis redis-cli --raw PTTL "${redis_key}" | tr -d '\r')"
(( redis_ttl > 0 && redis_ttl <= 10000 ))

# Scenario 2: restarting one replica does not discard the shared count.
await_fresh_redis_window
reset_state
[[ "$(request phase3-restart-client restart-1)" == "200" ]]
[[ "$(request phase3-restart-client restart-2)" == "200" ]]
docker compose restart gateway-1
for number in 3 4 5; do
  [[ "$(request phase3-restart-client "restart-${number}")" == "200" ]]
done
[[ "$(request phase3-restart-client restart-6)" == "429" ]]
assert_catalog_count 5
wait_for_healthy gateway-1

# Scenario 3: adding the third replica does not add capacity.
docker compose pause gateway-3
sleep 3
await_fresh_redis_window
reset_state
[[ "$(request phase3-scale-client scale-1)" == "200" ]]
[[ "$(request phase3-scale-client scale-2)" == "200" ]]
docker compose unpause gateway-3
sleep 3
observed_third=false
for number in 1 2 3 4 5 6; do
  [[ "$(request "phase3-scale-probe-${number}" "scale-probe-${number}")" == "200" ]]
  if [[ "$(instance_for "scale-probe-${number}")" == "gateway-3" ]]; then
    observed_third=true
    break
  fi
done
[[ "${observed_third}" == "true" ]]
for number in 3 4 5; do
  [[ "$(request phase3-scale-client "scale-${number}")" == "200" ]]
done
[[ "$(request phase3-scale-client scale-6)" == "429" ]]
assert_catalog_count "$((5 + $(find "${evidence_directory}" -name 'scale-probe-*.headers' | wc -l | tr -d ' ')))"

# Unhealthy removal: stopped replicas leave rotation and state remains valid.
await_fresh_redis_window
reset_state
[[ "$(request phase3-removal-client removal-1)" == "200" ]]
[[ "$(request phase3-removal-client removal-2)" == "200" ]]
docker compose stop gateway-2
sleep 3
for number in 3 4 5; do
  [[ "$(request phase3-removal-client "removal-${number}")" == "200" ]]
  [[ "$(instance_for "removal-${number}")" != "gateway-2" ]]
done
[[ "$(request phase3-removal-client removal-6)" == "429" ]]
[[ "$(instance_for removal-6)" != "gateway-2" ]]
assert_catalog_count 5

printf 'Phase 3 shared-limit, restart, scale-change, and unhealthy-removal acceptance passed.\n'
