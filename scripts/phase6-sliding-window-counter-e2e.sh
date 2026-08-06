#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

export ADMIN_BEARER_TOKEN="${ADMIN_BEARER_TOKEN:-phase6-$(openssl rand -hex 24)}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-phase6-$(openssl rand -hex 24)}"
export POSTGRES_USER="${POSTGRES_USER:-rate_limiter}"

authorization="Authorization: Bearer ${ADMIN_BEARER_TOKEN}"
admin_url="http://localhost:8081/admin/api/v1/policies/catalog-client-fixed-window"
evidence_directory="$(mktemp -d /tmp/rate-limiter-phase6.XXXXXX)"

cleanup() {
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

clone_version() {
  curl --fail --silent --show-error --request POST \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --data "{\"version\":$2,\"sourceVersion\":$1}" \
    "${admin_url}/versions" >/dev/null
}

update_sliding() {
  local version="$1" limit="$2" window="$3" cost="$4"
  local failure_mode="${5:-FAIL_CLOSED}"
  curl --fail --silent --show-error --request PUT \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --header 'If-Match: "0"' \
    --data "{\"description\":\"Distributed Sliding Counter acceptance\",\"match\":{\"routeId\":\"catalog.items\",\"path\":\"/proxy/catalog/items\",\"methods\":[\"GET\"]},\"identity\":{\"components\":[{\"type\":\"HEADER\",\"name\":\"X-Client-Id\"},{\"type\":\"ROUTE\"}]},\"algorithm\":{\"type\":\"SLIDING_WINDOW_COUNTER\",\"configuration\":{\"limit\":${limit},\"window\":\"${window}\",\"requestCost\":${cost}}},\"failureMode\":\"${failure_mode}\",\"priority\":100}" \
    "${admin_url}/versions/${version}" >/dev/null
}

update_fixed() {
  local version="$1" limit="$2" window="$3"
  curl --fail --silent --show-error --request PUT \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --header 'If-Match: "0"' \
    --data "{\"description\":\"Fixed boundary comparison\",\"match\":{\"routeId\":\"catalog.items\",\"path\":\"/proxy/catalog/items\",\"methods\":[\"GET\"]},\"identity\":{\"components\":[{\"type\":\"HEADER\",\"name\":\"X-Client-Id\"},{\"type\":\"ROUTE\"}]},\"algorithm\":{\"type\":\"FIXED_WINDOW\",\"configuration\":{\"limit\":${limit},\"windowMilliseconds\":${window}}},\"failureMode\":\"FAIL_CLOSED\",\"priority\":100}" \
    "${admin_url}/versions/${version}" >/dev/null
}

update_token() {
  local version="$1"
  curl --fail --silent --show-error --request PUT \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --header 'If-Match: "0"' \
    --data '{"description":"Token switch proof","match":{"routeId":"catalog.items","path":"/proxy/catalog/items","methods":["GET"]},"identity":{"components":[{"type":"HEADER","name":"X-Client-Id"},{"type":"ROUTE"}]},"algorithm":{"type":"TOKEN_BUCKET","configuration":{"capacity":7,"initialTokens":7,"refillTokens":1,"refillPeriod":"1h","requestCost":1}},"failureMode":"FAIL_CLOSED","priority":100}' \
    "${admin_url}/versions/${version}" >/dev/null
}

activate_version() {
  curl --fail --silent --show-error --request POST \
    --header "${authorization}" "${admin_url}/versions/$1/activate" >/dev/null
}

redis_now() {
  local seconds microseconds
  read -r seconds microseconds < <(docker compose exec --no-TTY redis redis-cli --raw TIME \
    | tr -d '\r' | paste -sd ' ' -)
  printf '%s\n' "$((seconds * 1000 + microseconds / 1000))"
}

wait_for_next_window() {
  local window="$1" initial="$2" attempts=300
  while (( attempts > 0 )); do
    if (( $(redis_now) / window > initial )); then
      return 0
    fi
    sleep 0.02
    attempts=$((attempts - 1))
  done
  printf 'Redis did not enter the next %sms window.\n' "${window}" >&2
  return 1
}

wait_near_boundary() {
  local window="$1" threshold="$2" attempts=600
  while (( attempts > 0 )); do
    local now remaining
    now="$(redis_now)"
    remaining="$((window - now % window))"
    if (( remaining > 100 && remaining <= threshold )); then
      printf '%s\n' "$((now / window))"
      return 0
    fi
    sleep 0.02
    attempts=$((attempts - 1))
  done
  return 1
}

reset_catalog_count() {
  curl --fail --silent --show-error --request POST \
    http://localhost:8101/_test/request-count/reset \
    | jq --exit-status '.catalogRequests == 0' >/dev/null
}

catalog_count() {
  curl --fail --silent http://localhost:8101/_test/request-count | jq -r '.catalogRequests'
}

request() {
  local client_id="$1" name="$2"
  curl --silent --show-error \
    --dump-header "${evidence_directory}/${name}.headers" \
    --output "${evidence_directory}/${name}.json" \
    --write-out '%{http_code}' \
    --header "X-Client-Id: ${client_id}" \
    --header "X-Correlation-Id: ${name}" \
    http://localhost:8080/proxy/catalog/items
}

parallel_distribution() {
  local prefix="$1" client="$2" total="$3" expected_allowed="$4"
  for number in $(seq 1 "${total}"); do
    request "${client}" "${prefix}-${number}" >"${evidence_directory}/${prefix}-${number}.status" &
  done
  wait
  local allowed rejected
  allowed="$(grep -h -c '^200$' "${evidence_directory}/${prefix}-"*.status \
    | awk '{ total += $1 } END { print total + 0 }')"
  rejected="$(grep -h -c '^429$' "${evidence_directory}/${prefix}-"*.status \
    | awk '{ total += $1 } END { print total + 0 }')"
  [[ "${allowed}" == "${expected_allowed}" ]]
  [[ "$((allowed + rejected))" == "${total}" ]]
}

parallel_distribution_range() {
  local prefix="$1" client="$2" total="$3" minimum="$4" maximum="$5"
  for number in $(seq 1 "${total}"); do
    request "${client}" "${prefix}-${number}" >"${evidence_directory}/${prefix}-${number}.status" &
  done
  wait
  local allowed rejected
  allowed="$(grep -h -c '^200$' "${evidence_directory}/${prefix}-"*.status \
    | awk '{ total += $1 } END { print total + 0 }')"
  rejected="$(grep -h -c '^429$' "${evidence_directory}/${prefix}-"*.status \
    | awk '{ total += $1 } END { print total + 0 }')"
  (( allowed >= minimum && allowed <= maximum ))
  [[ "$((allowed + rejected))" == "${total}" ]]
  printf '%s\n' "${allowed}"
}

sliding_key() {
  docker compose exec --no-TTY redis redis-cli --raw \
    KEYS "ratelimit:*:v=$1:a=sliding-window-counter:*" | tr -d '\r' | head -1
}

docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
docker compose build gateway-1 gateway-2 gateway-3 mock-catalog-service
docker compose up --detach --wait --wait-timeout 300
scripts/bootstrap-catalog-policy.sh
wait_for_all_snapshots 1 FIXED_WINDOW

# Scenario 1: normal weighting carries prior traffic into the next observed window.
clone_version 1 2
update_sliding 2 5 10s 1
activate_version 2
wait_for_all_snapshots 2 SLIDING_WINDOW_COUNTER
reset_catalog_count
initial_window="$(( $(redis_now) / 10000 ))"
for number in 1 2 3; do [[ "$(request phase6-normal "normal-${number}")" == "200" ]]; done
wait_for_next_window 10000 "${initial_window}"
[[ "$(request phase6-normal normal-next-1)" == "200" ]]
[[ "$(request phase6-normal normal-next-2)" == "200" ]]
[[ "$(request phase6-normal normal-next-rejected)" == "429" ]]
[[ "$(catalog_count)" == "5" ]]
normal_key="$(sliding_key 2)"
[[ "$(docker compose exec --no-TTY redis redis-cli --raw HGET "${normal_key}" previous_count | tr -d '\r')" == "3" ]]

# Scenario 2: Fixed Window admits the boundary burst; Sliding Counter retains weighted history.
clone_version 2 3
update_fixed 3 5 5000
activate_version 3
wait_for_all_snapshots 3 FIXED_WINDOW
reset_catalog_count
fixed_window="$(wait_near_boundary 5000 1000)"
for number in 1 2 3 4 5; do [[ "$(request phase6-fixed-boundary "fixed-before-${number}")" == "200" ]]; done
wait_for_next_window 5000 "${fixed_window}"
for number in 1 2 3 4 5; do [[ "$(request phase6-fixed-boundary "fixed-after-${number}")" == "200" ]]; done
[[ "$(catalog_count)" == "10" ]]

clone_version 3 4
update_sliding 4 5 5s 1
activate_version 4
wait_for_all_snapshots 4 SLIDING_WINDOW_COUNTER
reset_catalog_count
sliding_window="$(wait_near_boundary 5000 1000)"
for number in 1 2 3 4 5; do [[ "$(request phase6-sliding-boundary "sliding-before-${number}")" == "200" ]]; done
wait_for_next_window 5000 "${sliding_window}"
[[ "$(request phase6-sliding-boundary sliding-after-rejected)" == "429" ]]
[[ "$(catalog_count)" == "5" ]]

# Scenario 3: cost three consumes exactly three weighted admissions.
clone_version 4 5
update_sliding 5 10 10s 3
activate_version 5
wait_for_all_snapshots 5 SLIDING_WINDOW_COUNTER
reset_catalog_count
parallel_distribution cost-three phase6-cost-three 4 3
[[ "$(catalog_count)" == "3" ]]

# Scenario 4: repeated HAProxy concurrency shares one capacity across replicas.
clone_version 5 6
update_sliding 6 20 10s 1
activate_version 6
wait_for_all_snapshots 6 SLIDING_WINDOW_COUNTER
for repetition in 1 2 3; do
  reset_catalog_count
  parallel_distribution "concurrent-${repetition}" "phase6-concurrent-${repetition}" 60 20
  [[ "$(catalog_count)" == "20" ]]
  key="$(sliding_key 6)"
  [[ "$(docker compose exec --no-TTY redis redis-cli --raw HGET "${key}" current_count | tr -d '\r')" == "20" ]]
  (( $(docker compose exec --no-TTY redis redis-cli --raw PTTL "${key}" | tr -d '\r') > 0 ))
done
instances="$({ for file in "${evidence_directory}"/concurrent-*.headers; do awk 'tolower($1) == "x-gateway-instance:" {gsub("\r", "", $2); print $2}' "${file}"; done; } | sort -u)"
[[ "$(printf '%s\n' "${instances}" | grep -c .)" -ge 2 ]]

# Scenario 5: coordinated transition concurrency rotates one prepared count set.
clone_version 6 7
update_sliding 7 20 1s 1
activate_version 7
wait_for_all_snapshots 7 SLIDING_WINDOW_COUNTER
for repetition in 1 2 3; do
  client="phase6-transition-${repetition}"
  prepared_window="$(( $(redis_now) / 1000 ))"
  for number in $(seq 1 10); do [[ "$(request "${client}" "transition-${repetition}-prepare-${number}")" == "200" ]]; done
  wait_for_next_window 1000 "${prepared_window}"
  reset_catalog_count
  transition_allowed="$(parallel_distribution_range \
    "transition-${repetition}-race" "${client}" 40 10 11)"
  key="$(sliding_key 7)"
  [[ "$(docker compose exec --no-TTY redis redis-cli --raw HGET "${key}" previous_count | tr -d '\r')" == "10" ]]
  [[ "$(docker compose exec --no-TTY redis redis-cli --raw HGET "${key}" current_count | tr -d '\r')" == "${transition_allowed}" ]]
done

# Scenario 6: dynamic Fixed -> Sliding -> Token -> Sliding versions never reuse namespaces.
clone_version 7 8; update_fixed 8 2 10000; activate_version 8; wait_for_all_snapshots 8 FIXED_WINDOW
[[ "$(request phase6-switch switch-fixed)" == "200" ]]
clone_version 8 9; update_sliding 9 5 10s 1; activate_version 9; wait_for_all_snapshots 9 SLIDING_WINDOW_COUNTER
[[ "$(request phase6-switch switch-sliding-one)" == "200" ]]
clone_version 9 10; update_token 10; activate_version 10; wait_for_all_snapshots 10 TOKEN_BUCKET
[[ "$(request phase6-switch switch-token)" == "200" ]]
clone_version 10 11; update_sliding 11 9 10s 1; activate_version 11; wait_for_all_snapshots 11 SLIDING_WINDOW_COUNTER
[[ "$(request phase6-switch switch-sliding-two)" == "200" ]]
[[ -n "$(sliding_key 9)" && -n "$(sliding_key 11)" ]]

printf 'Phase 6 Sliding Counter weighting, boundary, cost, concurrency, transition, and switching scenarios passed.\n'
