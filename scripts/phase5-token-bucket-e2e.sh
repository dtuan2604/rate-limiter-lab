#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

export ADMIN_BEARER_TOKEN="${ADMIN_BEARER_TOKEN:-phase5-$(openssl rand -hex 24)}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-phase5-$(openssl rand -hex 24)}"
export POSTGRES_USER="${POSTGRES_USER:-rate_limiter}"

authorization="Authorization: Bearer ${ADMIN_BEARER_TOKEN}"
admin_url="http://localhost:8081/admin/api/v1/policies/catalog-client-fixed-window"
evidence_directory="$(mktemp -d /tmp/rate-limiter-phase5-token.XXXXXX)"

cleanup() {
  docker compose down --volumes --remove-orphans
  rm -rf "${evidence_directory}"
}
trap cleanup EXIT

wait_for_snapshot() {
  local port="$1"
  local version="$2"
  local algorithm="$3"
  local attempts=120
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
  local version="$1"
  local algorithm="$2"
  for port in 8081 8082 8083; do
    wait_for_snapshot "${port}" "${version}" "${algorithm}"
  done
}

clone_version() {
  local source="$1"
  local target="$2"
  curl --fail --silent --show-error --request POST \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --data "{\"version\":${target},\"sourceVersion\":${source}}" \
    "${admin_url}/versions" >/dev/null
}

update_token_bucket() {
  local version="$1"
  local capacity="$2"
  local initial="$3"
  local refill="$4"
  local period="$5"
  local cost="$6"
  local failure_mode="${7:-FAIL_CLOSED}"
  curl --fail --silent --show-error --request PUT \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --header 'If-Match: "0"' \
    --data "{\"description\":\"Distributed Token Bucket acceptance\",\"match\":{\"routeId\":\"catalog.items\",\"path\":\"/proxy/catalog/items\",\"methods\":[\"GET\"]},\"identity\":{\"components\":[{\"type\":\"HEADER\",\"name\":\"X-Client-Id\"},{\"type\":\"ROUTE\"}]},\"algorithm\":{\"type\":\"TOKEN_BUCKET\",\"configuration\":{\"capacity\":${capacity},\"initialTokens\":${initial},\"refillTokens\":${refill},\"refillPeriod\":\"${period}\",\"requestCost\":${cost}}},\"failureMode\":\"${failure_mode}\",\"priority\":100}" \
    "${admin_url}/versions/${version}" >/dev/null
}

update_fixed_window() {
  local version="$1"
  local limit="$2"
  curl --fail --silent --show-error --request PUT \
    --header "${authorization}" --header 'Content-Type: application/json' \
    --header 'If-Match: "0"' \
    --data "{\"description\":\"Fixed Window switch proof\",\"match\":{\"routeId\":\"catalog.items\",\"path\":\"/proxy/catalog/items\",\"methods\":[\"GET\"]},\"identity\":{\"components\":[{\"type\":\"HEADER\",\"name\":\"X-Client-Id\"},{\"type\":\"ROUTE\"}]},\"algorithm\":{\"type\":\"FIXED_WINDOW\",\"configuration\":{\"limit\":${limit},\"windowMilliseconds\":10000}},\"failureMode\":\"FAIL_CLOSED\",\"priority\":100}" \
    "${admin_url}/versions/${version}" >/dev/null
}

activate_version() {
  local version="$1"
  curl --fail --silent --show-error --request POST \
    --header "${authorization}" "${admin_url}/versions/${version}/activate" >/dev/null
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

parallel_distribution() {
  local prefix="$1"
  local client_id="$2"
  local total="$3"
  local allowed="$4"
  local rejected="$5"
  for number in $(seq 1 "${total}"); do
    request "${client_id}" "${prefix}-${number}" \
      >"${evidence_directory}/${prefix}-${number}.status" &
  done
  wait
  local observed_allowed
  observed_allowed="$(grep -h -c '^200$' "${evidence_directory}/${prefix}-"*.status \
    | awk '{ total += $1 } END { print total + 0 }')"
  local observed_rejected
  observed_rejected="$(grep -h -c '^429$' "${evidence_directory}/${prefix}-"*.status \
    | awk '{ total += $1 } END { print total + 0 }')"
  [[ "${observed_allowed}" == "${allowed}" ]]
  [[ "${observed_rejected}" == "${rejected}" ]]
}

token_key() {
  local version="$1"
  docker compose exec --no-TTY redis redis-cli --raw \
    KEYS "ratelimit:*:v=${version}:a=token-bucket:*" | tr -d '\r' | head -1
}

docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
docker compose build gateway-1 gateway-2 gateway-3 mock-catalog-service
docker compose up --detach --wait --wait-timeout 300
scripts/bootstrap-catalog-policy.sh
wait_for_all_snapshots 1 FIXED_WINDOW

# Scenarios 1 and 2: exact initial burst, backend count, multiple replicas, and continuous refill.
clone_version 1 2
update_token_bucket 2 5 5 1 1s 1
activate_version 2
wait_for_all_snapshots 2 TOKEN_BUCKET
reset_catalog_count
parallel_distribution initial-burst phase5-burst-client 6 5 1
[[ "$(catalog_count)" == "5" ]]
instances="$({
  for number in $(seq 1 6); do
    awk 'tolower($1) == "x-gateway-instance:" {gsub("\r", "", $2); print $2}' \
      "${evidence_directory}/initial-burst-${number}.headers"
  done
} | LC_ALL=C sort -u)"
[[ "$(printf '%s\n' "${instances}" | grep -c .)" -ge 2 ]]
burst_key="$(token_key 2)"
[[ -n "${burst_key}" ]]
burst_balance="$(docker compose exec --no-TTY redis redis-cli --raw \
  HGET "${burst_key}" tokens | tr -d '\r')"
(( burst_balance >= 0 && burst_balance < 1000 ))
[[ "$(request phase5-burst-client refill-early)" == "429" ]]
retry_milliseconds="$(jq --raw-output '.retryAfterMilliseconds' \
  "${evidence_directory}/refill-early.json")"
(( retry_milliseconds > 0 && retry_milliseconds <= 1000 ))
refill_allowed=false
for attempt in $(seq 1 5); do
  sleep "$(awk -v milliseconds="${retry_milliseconds}" \
    'BEGIN { print milliseconds / 1000 + 0.02 }')"
  status="$(request phase5-burst-client "refill-poll-${attempt}")"
  if [[ "${status}" == "200" ]]; then
    refill_allowed=true
    break
  fi
  [[ "${status}" == "429" ]]
  retry_milliseconds="$(jq --raw-output '.retryAfterMilliseconds' \
    "${evidence_directory}/refill-poll-${attempt}.json")"
done
[[ "${refill_allowed}" == "true" ]]
[[ "$(catalog_count)" == "6" ]]

# Scenario 3: cost three admits exactly three requests and retains one whole token.
clone_version 2 3
update_token_bucket 3 10 10 1 1h 3
activate_version 3
wait_for_all_snapshots 3 TOKEN_BUCKET
reset_catalog_count
parallel_distribution cost-three phase5-cost-client 4 3 1
[[ "$(catalog_count)" == "3" ]]
cost_key="$(token_key 3)"
cost_balance="$(docker compose exec --no-TTY redis redis-cli --raw \
  HGET "${cost_key}" tokens | tr -d '\r')"
(( cost_balance >= 1000 && cost_balance < 2000 ))

# Scenario 4: repeated HAProxy concurrency does not multiply capacity by three replicas.
clone_version 3 4
update_token_bucket 4 20 20 1 1d 1
activate_version 4
wait_for_all_snapshots 4 TOKEN_BUCKET
for repetition in 1 2 3; do
  reset_catalog_count
  parallel_distribution "concurrent-${repetition}" "phase5-concurrent-${repetition}" 60 20 40
  [[ "$(catalog_count)" == "20" ]]
  concurrent_key="$(token_key 4)"
  concurrent_balance="$(docker compose exec --no-TTY redis redis-cli --raw \
    HGET "${concurrent_key}" tokens | tr -d '\r')"
  (( concurrent_balance >= 0 && concurrent_balance < 1000 ))
  concurrent_ttl="$(docker compose exec --no-TTY redis redis-cli --raw \
    PTTL "${concurrent_key}" | tr -d '\r')"
  (( concurrent_ttl > 0 ))
done

# Scenario 5: Fixed Window and a new Token Bucket version use fresh algorithm/version namespaces.
clone_version 4 5
update_fixed_window 5 2
activate_version 5
wait_for_all_snapshots 5 FIXED_WINDOW
[[ "$(request phase5-switch-client switch-fixed)" == "200" ]]
fixed_key="$(docker compose exec --no-TTY redis redis-cli --raw \
  KEYS 'ratelimit:*:v=5:*fixed-window*' | tr -d '\r' | head -1)"
[[ -n "${fixed_key}" ]]
clone_version 5 6
update_token_bucket 6 7 7 1 1h 1
activate_version 6
wait_for_all_snapshots 6 TOKEN_BUCKET
[[ "$(request phase5-switch-client switch-token)" == "200" ]]
new_token_key="$(token_key 6)"
[[ -n "${new_token_key}" && "${new_token_key}" != "${fixed_key}" ]]

printf 'Phase 5 Token Bucket burst, refill, cost, concurrency, and switching scenarios passed.\n'
