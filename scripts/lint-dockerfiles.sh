#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

dockerfiles=(
  "gateway/Dockerfile"
  "traffic-simulator/Dockerfile"
  "mock-services/Dockerfile"
  "admin-portal/Dockerfile"
)

missing=()
for dockerfile in "${dockerfiles[@]}"; do
  if [[ ! -f "${dockerfile}" ]]; then
    missing+=("${dockerfile}")
  fi
done

if (( ${#missing[@]} > 0 )); then
  printf 'Missing required Phase 0 Dockerfiles:\n' >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

docker run --rm \
  --volume "${repository_root}:/work:ro" \
  --workdir /work \
  hadolint/hadolint:v2.12.0-alpine \
  hadolint "${dockerfiles[@]}"
