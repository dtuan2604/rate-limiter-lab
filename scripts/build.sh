#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

if [[ -z "${JAVA_HOME:-}" ]] && [[ "$(uname -s)" == "Darwin" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export JAVA_HOME
fi

if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "${JAVA_HOME}/bin/java" ]]; then
  printf 'JAVA_HOME must identify a Java 21 JDK.\n' >&2
  exit 1
fi

build_output="$(mktemp -d /tmp/rate-limiter-build.XXXXXX)"
cleanup() {
  rm -rf "${build_output}"
}
trap cleanup EXIT

./gradlew :gateway:build --no-daemon
conda run -n rate-limiter python -m build \
  --outdir "${build_output}/traffic-simulator" \
  traffic-simulator
conda run -n rate-limiter python -m build \
  --outdir "${build_output}/mock-services" \
  mock-services
npm --prefix admin-portal run build

printf 'All Phase 0 application artifacts built successfully.\n'
