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

coverage_evidence="$(mktemp -d /tmp/rate-limiter-coverage.XXXXXX)"
cleanup() {
  rm -rf "${coverage_evidence}"
}
trap cleanup EXIT

verify_python_metrics() {
  local codebase="$1"
  local evidence_file="$2"
  local coverage_configuration="$3"

  conda run -n rate-limiter python -m coverage json \
    --rcfile="${coverage_configuration}" \
    -o "${evidence_file}"
  jq --exit-status --arg codebase "${codebase}" '
    def percentage(covered; total):
      if total == 0 then 100 else (covered * 100 / total) end;

    .totals as $totals
    | [
        .files[].functions
        | to_entries[]
        | select(.key != "")
      ] as $functions
    | percentage(
        $totals.covered_lines;
        $totals.covered_lines + $totals.missing_lines
      ) as $lines
    | $totals.percent_statements_covered as $statements
    | percentage(
        $totals.covered_branches;
        $totals.covered_branches + $totals.missing_branches
      ) as $branches
    | percentage(
        [$functions[] | select(.value.summary.covered_lines > 0)] | length;
        $functions | length
      ) as $function_coverage
    | if ($lines >= 90
          and $statements >= 90
          and $branches >= 90
          and $function_coverage >= 90)
      then
        "\($codebase): lines=\($lines) statements=\($statements) branches=\($branches) functions=\($function_coverage)"
      else
        error(
          "\($codebase) coverage below 90%: lines=\($lines) statements=\($statements) branches=\($branches) functions=\($function_coverage)"
        )
      end
  ' "${evidence_file}"
}

./gradlew :gateway:jacocoTestReport :gateway:jacocoTestCoverageVerification --no-daemon
conda run -n rate-limiter python -m pytest \
  traffic-simulator/tests \
  --cov=rate_limiter_traffic_simulator \
  --cov-branch \
  --cov-config=traffic-simulator/pyproject.toml \
  --cov-fail-under=90
verify_python_metrics \
  "traffic-simulator" \
  "${coverage_evidence}/traffic-simulator.json" \
  "traffic-simulator/pyproject.toml"
conda run -n rate-limiter python -m pytest \
  mock-services/tests/test_catalog.py \
  --cov=rate_limiter_mock_services.catalog \
  --cov-branch \
  --cov-fail-under=90
verify_python_metrics \
  "catalog" \
  "${coverage_evidence}/catalog.json" \
  "mock-services/pyproject.toml"
conda run -n rate-limiter python -m pytest \
  mock-services/tests/test_orders.py \
  --cov=rate_limiter_mock_services.orders \
  --cov-branch \
  --cov-fail-under=90
verify_python_metrics \
  "orders" \
  "${coverage_evidence}/orders.json" \
  "mock-services/pyproject.toml"
conda run -n rate-limiter python -m pytest \
  mock-services/tests/test_payments.py \
  --cov=rate_limiter_mock_services.payments \
  --cov-branch \
  --cov-fail-under=90
verify_python_metrics \
  "payments" \
  "${coverage_evidence}/payments.json" \
  "mock-services/pyproject.toml"
npm --prefix admin-portal run coverage
