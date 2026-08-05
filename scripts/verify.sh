#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

export ADMIN_BEARER_TOKEN="${ADMIN_BEARER_TOKEN:-verify-$(openssl rand -hex 24)}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-verify-$(openssl rand -hex 24)}"

scripts/check-repository-structure.sh
scripts/check-ci.sh
scripts/format.sh
scripts/static-checks.sh
scripts/test.sh
scripts/coverage.sh
scripts/validate-contracts.sh
scripts/build.sh
docker compose config --quiet
scripts/lint-dockerfiles.sh
scripts/container-smoke.sh
scripts/phase2-e2e.sh
scripts/phase3-e2e.sh
scripts/phase3-redis-failure-e2e.sh
scripts/phase4-e2e.sh
scripts/phase4-publication-failure-e2e.sh
scripts/phase5-token-bucket-e2e.sh
scripts/phase5-token-bucket-resilience-e2e.sh

printf 'Phase 5 CI-equivalent verification passed.\n'
