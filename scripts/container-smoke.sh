#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

cleanup() {
  docker compose down --volumes --remove-orphans
}
trap cleanup EXIT

docker compose config --quiet
docker compose up --build --detach --wait --wait-timeout 240

gateway_count="$(docker compose ps gateway --format json | jq -s 'length')"
if [[ "${gateway_count}" != "1" ]]; then
  printf 'Expected one gateway, observed %s.\n' "${gateway_count}" >&2
  exit 1
fi

curl --fail --silent --show-error \
  http://localhost:8080/actuator/health/readiness >/dev/null
curl --fail --silent --show-error http://localhost:8101/health >/dev/null

printf 'Phase 2 gateway and catalog containers are healthy.\n'
