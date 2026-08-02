#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

expected_services=$'catalog\ngateway'
actual_services="$(docker compose config --services | LC_ALL=C sort)"
if [[ "${actual_services}" != "${expected_services}" ]]; then
  printf 'Phase 2 Compose must contain only catalog and gateway; observed:\n%s\n' \
    "${actual_services}" >&2
  exit 1
fi

evidence_directory="$(mktemp -d /tmp/rate-limiter-phase2-e2e.XXXXXX)"
cleanup() {
  docker compose down --volumes --remove-orphans
  rm -rf "${evidence_directory}"
}
trap cleanup EXIT

docker compose config --quiet
docker compose up --build --detach --wait --wait-timeout 240

for service in catalog gateway; do
  container_id="$(docker compose ps --quiet "${service}")"
  health="$(docker inspect --format '{{.State.Health.Status}}' "${container_id}")"
  if [[ "${health}" != "healthy" ]]; then
    printf '%s container is not healthy: %s\n' "${service}" "${health}" >&2
    exit 1
  fi
done

curl --fail --silent --show-error \
  --request POST \
  http://localhost:8101/_test/request-count/reset \
  | jq --exit-status '.catalogRequests == 0' >/dev/null

for request_number in 1 2 3 4 5; do
  status="$(
    curl --silent --show-error \
      --dump-header "${evidence_directory}/allowed-${request_number}.headers" \
      --output "${evidence_directory}/allowed-${request_number}.json" \
      --write-out '%{http_code}' \
      --header 'X-Client-Id: phase2-client' \
      --header 'X-Correlation-Id: phase2-correlation' \
      http://localhost:8080/proxy/catalog/items
  )"
  if [[ "${status}" != "200" ]]; then
    printf 'Expected allowed request %s to return 200, observed %s.\n' \
      "${request_number}" "${status}" >&2
    exit 1
  fi
  jq --exit-status \
    '.service == "catalog"
      and .clientId == "phase2-client"
      and .correlationId == "phase2-correlation"' \
    "${evidence_directory}/allowed-${request_number}.json" >/dev/null
done

rejected_status="$(
  curl --silent --show-error \
    --dump-header "${evidence_directory}/rejected.headers" \
    --output "${evidence_directory}/rejected.json" \
    --write-out '%{http_code}' \
    --header 'X-Client-Id: phase2-client' \
    --header 'X-Correlation-Id: phase2-rejected-correlation' \
    http://localhost:8080/proxy/catalog/items
)"
if [[ "${rejected_status}" != "429" ]]; then
  printf 'Expected sixth request to return 429, observed %s.\n' \
    "${rejected_status}" >&2
  exit 1
fi
jq --exit-status \
  '.status == 429
    and .error == "RATE_LIMIT_EXCEEDED"
    and .policy == "catalog-client-fixed-window"
    and .retryAfterMilliseconds > 0
    and .correlationId == "phase2-rejected-correlation"' \
  "${evidence_directory}/rejected.json" >/dev/null

curl --fail --silent --show-error \
  http://localhost:8101/_test/request-count \
  | jq --exit-status '.catalogRequests == 5' >/dev/null

different_status="$(
  curl --silent --show-error \
    --dump-header "${evidence_directory}/different.headers" \
    --output "${evidence_directory}/different.json" \
    --write-out '%{http_code}' \
    --header 'X-Client-Id: phase2-other-client' \
    --header 'X-Correlation-Id: phase2-other-correlation' \
    http://localhost:8080/proxy/catalog/items
)"
if [[ "${different_status}" != "200" ]]; then
  printf 'Expected a different client to return 200, observed %s.\n' \
    "${different_status}" >&2
  exit 1
fi
jq --exit-status \
  '.service == "catalog"
    and .clientId == "phase2-other-client"
    and .correlationId == "phase2-other-correlation"' \
  "${evidence_directory}/different.json" >/dev/null

allowed_correlation="$(
  awk 'tolower($1) == "x-correlation-id:" {gsub("\r", "", $2); print $2}' \
    "${evidence_directory}/allowed-1.headers"
)"
if [[ "${allowed_correlation}" != "phase2-correlation" ]]; then
  printf 'Allowed correlation ID was not propagated: %s\n' \
    "${allowed_correlation}" >&2
  exit 1
fi

rejected_correlation="$(
  awk 'tolower($1) == "x-correlation-id:" {gsub("\r", "", $2); print $2}' \
    "${evidence_directory}/rejected.headers"
)"
if [[ "${rejected_correlation}" != "phase2-rejected-correlation" ]]; then
  printf 'Rejected correlation ID was not propagated: %s\n' \
    "${rejected_correlation}" >&2
  exit 1
fi

curl --fail --silent --show-error \
  http://localhost:8101/_test/request-count \
  | jq --exit-status '.catalogRequests == 6' >/dev/null

printf 'Phase 2 end-to-end acceptance passed with five forwarded requests and one rejection.\n'
