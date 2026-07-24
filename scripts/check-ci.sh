#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

required_files=(
  ".github/workflows/ci.yml"
  "scripts/build.sh"
  "scripts/verify.sh"
)

missing=()
for required_file in "${required_files[@]}"; do
  if [[ ! -f "${required_file}" ]]; then
    missing+=("${required_file}")
  fi
done

if (( ${#missing[@]} > 0 )); then
  printf 'Missing required Phase 0 CI files:\n' >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

if grep -q 'TBD:' docs/COMMANDS.md; then
  printf 'docs/COMMANDS.md still contains unverified placeholders.\n' >&2
  exit 1
fi

conda run -n rate-limiter python -c \
  'from pathlib import Path; import yaml; yaml.safe_load(Path(".github/workflows/ci.yml").read_text())'

grep -q 'scripts/verify.sh' .github/workflows/ci.yml
grep -q 'scripts/container-smoke.sh' scripts/verify.sh

printf 'CI workflow and command documentation are structurally complete.\n'
