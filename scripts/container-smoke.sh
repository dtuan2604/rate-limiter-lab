#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

export ADMIN_BEARER_TOKEN="${ADMIN_BEARER_TOKEN:-smoke-$(openssl rand -hex 24)}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-smoke-$(openssl rand -hex 24)}"

cleanup() {
  docker compose down --volumes --remove-orphans
}
trap cleanup EXIT

docker compose config --quiet
docker compose up --build --detach --wait --wait-timeout 240

gateway_count="$(docker compose ps --format json | jq -s '[.[] | select(.Service | startswith("gateway-"))] | length')"
if [[ "${gateway_count}" != "3" ]]; then
  printf 'Expected three gateways, observed %s.\n' "${gateway_count}" >&2
  exit 1
fi

curl --fail --silent --show-error \
  http://localhost:8080/actuator/health/readiness >/dev/null
curl --fail --silent --show-error http://localhost:8101/health >/dev/null
docker compose exec --no-TTY redis redis-cli ping | grep -q PONG
docker compose exec --no-TTY postgres pg_isready \
  --username "${POSTGRES_USER:-rate_limiter}" \
  --dbname "${POSTGRES_DB:-rate_limiter}" >/dev/null

printf 'HAProxy, three gateways, Redis, PostgreSQL, and catalog are healthy.\n'
