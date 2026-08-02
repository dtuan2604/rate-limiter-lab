# Verified Repository Commands

All commands below run from the repository root unless a different working directory is stated. They were executed and inspected on 2026-07-26 on macOS arm64.

## Prerequisites

- Microsoft OpenJDK 21.0.8; on macOS the scripts discover it with `/usr/libexec/java_home -v 21`. On other platforms, set `JAVA_HOME` to a Java 21 JDK.
- Gradle 8.14.3 from the checked-in, SHA-256-verified wrapper.
- Conda environment `rate-limiter` with CPython 3.12.13.
- Node.js 24.10.0 and npm 11.6.0.
- Docker Engine 27.5.1 and Docker Compose 2.32.4 or compatible later versions.
- `curl` and `jq` for container smoke checks.

Install the exact Python dependency set and local packages:

```bash
conda run -n rate-limiter python -m pip install --disable-pip-version-check --requirement requirements-dev.lock
conda run -n rate-limiter python -m pip install --disable-pip-version-check --no-deps --editable traffic-simulator --editable mock-services
```

Install the exact frontend lock:

```bash
npm --prefix admin-portal ci --no-audit --no-fund
```

A dependency-resolution or peer-dependency error means the lock and manifest disagree; do not bypass it with force or legacy-peer flags.

## Entire repository

### Structure discovery

```bash
scripts/check-repository-structure.sh
```

Verifies the pinned toolchain files and executable-codebase skeletons exist. Exit 0 and `Phase 0 repository skeleton is discoverable.` were observed.

### Formatting

```bash
scripts/format.sh
```

Runs Java/Kotlin Gradle Spotless, Python Ruff format checks, and frontend Prettier. Exit 0 was observed. A formatter diagnostic means the checked-in source differs from the configured canonical format.

### Static checks and type checking

```bash
scripts/static-checks.sh
```

Runs Java Checkstyle, Python Ruff, strict mypy, frontend ESLint, and strict TypeScript. Exit 0 was observed. Controlled violations proved every checker returns nonzero.

### Unit and contract tests

```bash
scripts/test.sh
```

Runs the gateway, traffic simulator, each mock service independently, all contracts, and the portal. Exit 0 was observed.

### Independent coverage gates

```bash
scripts/coverage.sh
```

Runs JaCoCo plus independent pytest-cov and Vitest gates. The script exports
raw Python coverage data and separately enforces line, statement, branch, and
function percentages so a combined score cannot mask a deficient metric. Exit
0 was observed. Gateway coverage reported 98.58% lines, 93.55% branches, and
100% methods. Traffic simulator, catalog, orders, payments, and portal each
reported 100% for every supported metric.

### Application artifact builds

```bash
scripts/build.sh
```

Runs the gateway Gradle build, builds both Python distributions in an isolated temporary output directory, and creates the portal production bundle. Exit 0 was observed. Build output is removed after verification.

## Java gateway

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:spotlessCheck --no-daemon
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:checkstyleMain :gateway:checkstyleTest --no-daemon
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --no-daemon
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:jacocoTestReport :gateway:jacocoTestCoverageVerification --no-daemon
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:build --no-daemon
```

All returned exit 0. The 102-test gateway suite covers the Phase 1 algorithms,
static policy startup validation, matching, identity construction, local
fixed-window orchestration, reactive request/response mapping, forwarding, and
catalog-aware readiness. The real random-port application test verifies
liveness remains up while readiness is down when catalog is unavailable.
Phase 2 intentionally has no Redis or PostgreSQL integration.

## Python traffic simulator

```bash
conda run -n rate-limiter python -m ruff format --check traffic-simulator
conda run -n rate-limiter python -m ruff check traffic-simulator
conda run -n rate-limiter python -m mypy traffic-simulator/src
conda run -n rate-limiter python -m pytest traffic-simulator/tests
conda run -n rate-limiter python -m pytest traffic-simulator/tests --cov=rate_limiter_traffic_simulator --cov-branch --cov-config=traffic-simulator/pyproject.toml --cov-fail-under=90
conda run -n rate-limiter python -m build traffic-simulator
```

The first five commands returned exit 0; coverage reported 100%. The package build was verified through `scripts/build.sh`, which supplies an isolated `--outdir` and removes it afterward.

## Python mock services

Each service has an independent unit and coverage boundary:

```bash
conda run -n rate-limiter python -m pytest mock-services/tests/test_catalog.py --cov=rate_limiter_mock_services.catalog --cov-branch --cov-fail-under=90
conda run -n rate-limiter python -m pytest mock-services/tests/test_orders.py --cov=rate_limiter_mock_services.orders --cov-branch --cov-fail-under=90
conda run -n rate-limiter python -m pytest mock-services/tests/test_payments.py --cov=rate_limiter_mock_services.payments --cov-branch --cov-fail-under=90
```

All returned exit 0 and each reported 100%. The catalog suite contains 11
tests; orders and payments remain unchanged health-only foundations and are not
started by Phase 2 Compose.

Shared static and package checks:

```bash
conda run -n rate-limiter python -m ruff format --check mock-services
conda run -n rate-limiter python -m ruff check mock-services
conda run -n rate-limiter python -m mypy mock-services/src
conda run -n rate-limiter python -m build mock-services
```

Static commands returned exit 0. The package build was verified through `scripts/build.sh` with isolated output.

## Admin portal

```bash
npm --prefix admin-portal run format
npm --prefix admin-portal run lint
npm --prefix admin-portal run typecheck
npm --prefix admin-portal test
npm --prefix admin-portal run coverage
npm --prefix admin-portal run build
```

All returned exit 0. Coverage reported 100% statements, lines, and functions; the final application has no conditional branch. The production Vite build emitted `dist/index.html` and one JavaScript asset.

## Contracts

```bash
scripts/validate-contracts.sh
```

Equivalent focused command:

```bash
conda run -n rate-limiter python -m pytest contracts/tests
```

Both returned exit 0 with 10 passing tests. Valid token-bucket policy, empty
traffic v0, strict Phase 2 structured errors, and OpenAPI examples pass;
invalid examples fail at asserted stable paths.

## Containers

### Compose and Dockerfile validation

```bash
docker compose config --quiet
scripts/lint-dockerfiles.sh
```

Both returned exit 0. Hadolint runs from pinned image `hadolint/hadolint:v2.12.0-alpine`.

### Image build

```bash
docker compose build gateway catalog
```

Exit 0 was observed for the only two Phase 2 images.

### Health and smoke

```bash
scripts/container-smoke.sh
```

Builds and starts the Phase 2 environment, waits up to 240 seconds for health,
asserts exactly one gateway, checks gateway catalog-aware readiness and catalog
health, and shuts down through a trap. Exit 0 and
`Phase 2 gateway and catalog containers are healthy.` were observed.

### End-to-end acceptance

```bash
scripts/phase2-e2e.sh
```

Builds and starts gateway and catalog, asserts both containers are healthy,
resets the protected catalog counter, sends six same-client requests, proves
five catalog successes and one 429, proves exactly five catalog arrivals,
allows a different client, verifies correlation propagation, and tears down
with volumes and orphans removed. Exit 0 and
`Phase 2 end-to-end acceptance passed with five forwarded requests and one rejection.`
were observed.

### Destructive cleanup

Phase 2 has no persistent volume. The required cleanup command is:

```bash
docker compose down --volumes --remove-orphans
```

Exit 0 was observed, followed by an empty `docker compose ps --all`.

## CI workflow

```bash
scripts/check-ci.sh
```

Validates YAML parsing, required files, command documentation completion, and reuse of `scripts/verify.sh`. A nonzero result means CI and local orchestration have diverged.

The complete CI-equivalent entry point is:

```bash
scripts/verify.sh
```

Exit 0 and `Phase 2 CI-equivalent verification passed.` were observed. The
detailed milestone and RED-GREEN-REFACTOR evidence is recorded in the completed
Phase 2 ExecPlan.
