#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

cleanup() {
  docker compose down --remove-orphans
}
trap cleanup EXIT

docker compose config --quiet
docker compose up --build --detach --wait --wait-timeout 240

gateway_count="$(docker compose ps gateway --format json | jq -s 'length')"
if [[ "${gateway_count}" != "3" ]]; then
  printf 'Expected three gateway replicas, observed %s.\n' "${gateway_count}" >&2
  exit 1
fi

curl --fail --silent --show-error http://localhost:8080/health >/dev/null
curl --fail --silent --show-error http://localhost:3000/health >/dev/null
curl --fail --silent --show-error http://localhost:8101/health >/dev/null
curl --fail --silent --show-error http://localhost:8102/health >/dev/null
curl --fail --silent --show-error http://localhost:8103/health >/dev/null
curl --fail --silent --show-error http://localhost:9090/-/healthy >/dev/null
curl --fail --silent --show-error http://localhost:3001/api/health >/dev/null

docker compose build traffic-simulator
traffic_output="$(docker compose run --rm traffic-simulator)"
if [[ "${traffic_output}" != "rate-limiter-traffic-simulator 0.0.0" ]]; then
  printf 'Unexpected traffic-simulator output: %s\n' "${traffic_output}" >&2
  exit 1
fi

printf 'Phase 0 containers are healthy; no product traffic was generated.\n'
