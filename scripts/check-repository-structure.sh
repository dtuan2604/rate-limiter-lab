#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

required_files=(
  ".java-version"
  ".node-version"
  ".python-version"
  "settings.gradle.kts"
  "build.gradle.kts"
  "gradlew"
  "gateway/build.gradle.kts"
  "gateway/src/main/java/lab/ratelimiter/gateway/GatewayApplication.java"
  "gateway/src/main/resources/redis/fixed-window-v1.lua"
  "gateway/src/main/resources/redis/token-bucket-v1.lua"
  "gateway/src/main/resources/db/migration/V1__policy_control_plane.sql"
  "gateway/src/main/resources/db/migration/V2__distributed_token_bucket.sql"
  "contracts/policy-event.schema.json"
  "contracts/policy-snapshot.schema.json"
  "deploy/haproxy/haproxy.cfg"
  "compose.yaml"
  "scripts/phase3-e2e.sh"
  "scripts/phase3-redis-failure-e2e.sh"
  "scripts/bootstrap-catalog-policy.sh"
  "scripts/phase4-e2e.sh"
  "scripts/phase4-publication-failure-e2e.sh"
  "scripts/phase5-token-bucket-e2e.sh"
  "scripts/phase5-token-bucket-resilience-e2e.sh"
  "traffic-simulator/pyproject.toml"
  "traffic-simulator/src/rate_limiter_traffic_simulator/__init__.py"
  "mock-services/pyproject.toml"
  "mock-services/src/rate_limiter_mock_services/catalog.py"
  "mock-services/src/rate_limiter_mock_services/orders.py"
  "mock-services/src/rate_limiter_mock_services/payments.py"
  "admin-portal/package.json"
  "admin-portal/src/App.tsx"
)

missing=()
for required_file in "${required_files[@]}"; do
  if [[ ! -f "${repository_root}/${required_file}" ]]; then
    missing+=("${required_file}")
  fi
done

if (( ${#missing[@]} > 0 )); then
  printf 'Missing required Phase 0 foundation files:\n' >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

printf 'Phase 5 repository structure is discoverable.\n'
