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

./gradlew :gateway:spotlessCheck --no-daemon
conda run -n rate-limiter python -m ruff format --check traffic-simulator mock-services contracts
npm --prefix admin-portal run format
