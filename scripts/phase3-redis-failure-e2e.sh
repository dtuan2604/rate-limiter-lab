#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

evidence_directory="$(mktemp -d /tmp/rate-limiter-phase3-redis-failure.XXXXXX)"
cleanup_environment() {
  docker compose unpause redis >/dev/null 2>&1 || true
  docker compose down --volumes --remove-orphans
}
cleanup() {
  cleanup_environment
  rm -rf "${evidence_directory}"
}
trap cleanup EXIT

wait_for_readiness() {
  local port="$1"
  local expected_status="$2"
  local attempts=60
  while (( attempts > 0 )); do
    local status
    status="$(
      curl --silent --output /dev/null --write-out '%{http_code}' \
        "http://localhost:${port}/actuator/health/readiness" || true
    )"
    if [[ "${status}" == "${expected_status}" ]]; then
      return 0
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
  printf 'Gateway on port %s did not report readiness HTTP %s.\n' \
    "${port}" "${expected_status}" >&2
  return 1
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

reset_state() {
  docker compose exec --no-TTY redis redis-cli FLUSHDB >/dev/null
  curl --fail --silent --show-error \
    --request POST \
    http://localhost:8101/_test/request-count/reset \
    | jq --exit-status '.catalogRequests == 0' >/dev/null
}

catalog_count() {
  curl --fail --silent --show-error \
    http://localhost:8101/_test/request-count \
    | jq --raw-output '.catalogRequests'
}

gateway_request() {
  local port="$1"
  local client_id="$2"
  local evidence_name="$3"
  curl --silent --show-error \
    --dump-header "${evidence_directory}/${evidence_name}.headers" \
    --output "${evidence_directory}/${evidence_name}.json" \
    --write-out '%{http_code}' \
    --header "X-Client-Id: ${client_id}" \
    --header "X-Correlation-Id: ${evidence_name}" \
    "http://localhost:${port}/proxy/catalog/items"
}

start_environment() {
  local mode="$1"
  RATE_LIMIT_FAILURE_MODE="${mode}" \
    docker compose up --detach --wait --wait-timeout 240
  for port in 8081 8082 8083; do
    wait_for_readiness "${port}" 200
  done
}

# FAIL_OPEN: requests are forwarded with explicit degraded metadata and no
# process-local counter is activated. Redis recovery resumes surviving state.
start_environment FAIL_OPEN
await_fresh_redis_window
reset_state
[[ "$(gateway_request 8081 fail-open-client fail-open-before)" == "200" ]]
docker compose pause redis
wait_for_readiness 8081 200
for number in 1 2 3 4 5 6; do
  [[ "$(gateway_request 8081 fail-open-client "fail-open-outage-${number}")" == "200" ]]
  grep -Eiq '^X-RateLimit-Degraded:[[:space:]]*true' \
    "${evidence_directory}/fail-open-outage-${number}.headers"
done
[[ "$(catalog_count)" == "7" ]]
docker compose unpause redis
wait_for_readiness 8081 200
for number in 2 3 4 5; do
  [[ "$(gateway_request 8081 fail-open-client "fail-open-recovery-${number}")" == "200" ]]
done
[[ "$(gateway_request 8081 fail-open-client fail-open-recovery-6)" == "429" ]]
[[ "$(catalog_count)" == "11" ]]
cleanup_environment

# FAIL_CLOSED: direct requests receive the strict 503, no backend delivery
# occurs, and HAProxy removes all unready replicas until Redis recovers.
start_environment FAIL_CLOSED
await_fresh_redis_window
reset_state
[[ "$(gateway_request 8081 fail-closed-client fail-closed-before-1)" == "200" ]]
[[ "$(gateway_request 8082 fail-closed-client fail-closed-before-2)" == "200" ]]
docker compose pause redis
wait_for_readiness 8081 503
[[ "$(gateway_request 8081 fail-closed-client fail-closed-outage)" == "503" ]]
jq --exit-status \
  '.status == 503
    and .error == "RATE_LIMIT_STATE_UNAVAILABLE"
    and .message == "Rate-limit state is unavailable"
    and .correlationId == "fail-closed-outage"' \
  "${evidence_directory}/fail-closed-outage.json" >/dev/null
grep -Eiq '^X-Correlation-Id:[[:space:]]*fail-closed-outage' \
  "${evidence_directory}/fail-closed-outage.headers"
[[ "$(catalog_count)" == "2" ]]
sleep 3
load_balancer_status="$(
  curl --silent --output /dev/null --write-out '%{http_code}' \
    --header 'X-Client-Id: fail-closed-load-balancer' \
    http://localhost:8080/proxy/catalog/items
)"
[[ "${load_balancer_status}" == "503" ]]
[[ "$(catalog_count)" == "2" ]]
docker compose unpause redis
wait_for_readiness 8081 200
for number in 3 4 5; do
  [[ "$(gateway_request 8081 fail-closed-client "fail-closed-recovery-${number}")" == "200" ]]
done
[[ "$(gateway_request 8081 fail-closed-client fail-closed-recovery-6)" == "429" ]]
[[ "$(catalog_count)" == "5" ]]

printf 'Phase 3 FAIL_OPEN and FAIL_CLOSED Redis outage acceptance passed.\n'
