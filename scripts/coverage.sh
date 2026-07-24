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

java_major="$("${JAVA_HOME}/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
if [[ "${java_major}" != "21" ]]; then
  printf 'Java 21 is required; JAVA_HOME reports Java %s.\n' "${java_major}" >&2
  exit 1
fi

./gradlew :gateway:jacocoTestReport :gateway:jacocoTestCoverageVerification --no-daemon
conda run -n rate-limiter python -m pytest \
  traffic-simulator/tests \
  --cov=rate_limiter_traffic_simulator \
  --cov-branch \
  --cov-config=traffic-simulator/pyproject.toml \
  --cov-fail-under=90
conda run -n rate-limiter python -m pytest \
  mock-services/tests/test_catalog.py \
  --cov=rate_limiter_mock_services.catalog \
  --cov-branch \
  --cov-fail-under=90
conda run -n rate-limiter python -m pytest \
  mock-services/tests/test_orders.py \
  --cov=rate_limiter_mock_services.orders \
  --cov-branch \
  --cov-fail-under=90
conda run -n rate-limiter python -m pytest \
  mock-services/tests/test_payments.py \
  --cov=rate_limiter_mock_services.payments \
  --cov-branch \
  --cov-fail-under=90
npm --prefix admin-portal run coverage
