#!/usr/bin/env bash

set -euo pipefail

admin_api_url="${ADMIN_API_URL:-http://localhost:8081/admin/api/v1}"
: "${ADMIN_BEARER_TOKEN:?ADMIN_BEARER_TOKEN is required}"
failure_mode="${CATALOG_POLICY_FAILURE_MODE:-FAIL_CLOSED}"

authorization="Authorization: Bearer ${ADMIN_BEARER_TOKEN}"
policy_url="${admin_api_url}/policies/catalog-client-fixed-window"
response_file="$(mktemp /tmp/rate-limiter-bootstrap-policy.XXXXXX)"
cleanup() {
  rm -f "${response_file}"
}
trap cleanup EXIT
status="$({
  curl --silent --show-error \
    --output "${response_file}" \
    --write-out '%{http_code}' \
    --header "${authorization}" \
    "${policy_url}"
})"

if [[ "${status}" == "404" ]]; then
  curl --fail --silent --show-error \
    --request POST \
    --header "${authorization}" \
    --header 'Content-Type: application/json' \
    --data "{\"policyId\":\"catalog-client-fixed-window\",\"name\":\"Catalog client fixed window\",\"version\":1,\"definition\":{\"description\":\"Catalog requests per client and route\",\"match\":{\"routeId\":\"catalog.items\",\"path\":\"/proxy/catalog/items\",\"methods\":[\"GET\"]},\"identity\":{\"components\":[{\"type\":\"HEADER\",\"name\":\"X-Client-Id\"},{\"type\":\"ROUTE\"}]},\"algorithm\":{\"type\":\"FIXED_WINDOW\",\"configuration\":{\"limit\":5,\"windowMilliseconds\":10000}},\"failureMode\":\"${failure_mode}\",\"priority\":100}}" \
    "${admin_api_url}/policies" >/dev/null
elif [[ "${status}" != "200" ]]; then
  printf 'Unexpected catalog policy lookup status: %s\n' "${status}" >&2
  exit 1
fi

summary="$(curl --fail --silent --show-error --header "${authorization}" "${policy_url}")"
if [[ "$(printf '%s' "${summary}" | jq --raw-output '.activeVersion')" == "null" ]]; then
  curl --fail --silent --show-error \
    --request POST \
    --header "${authorization}" \
    "${admin_api_url}/policies/catalog-client-fixed-window/versions/1/activate" >/dev/null
fi
