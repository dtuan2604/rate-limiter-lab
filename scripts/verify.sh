#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

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

printf 'Phase 2 CI-equivalent verification passed.\n'
