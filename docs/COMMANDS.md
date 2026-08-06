# Verified Repository Commands

All commands below run from the repository root unless a different working
directory is stated. Phase 5 commands were executed and inspected on 2026-08-04
on macOS arm64.

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

Verifies the pinned toolchains, executable-codebase skeletons, Lua resource,
HAProxy configuration, Compose topology, migrations, and retained/Phase 5 acceptance scripts.

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

Runs the gateway, traffic simulator, each mock service independently, all
contracts, and the portal. The gateway suite includes pinned PostgreSQL and
Redis Testcontainers; Docker must be running.

### Independent coverage gates

```bash
scripts/coverage.sh
```

Runs JaCoCo plus independent pytest-cov and Vitest gates. The script exports
raw Python coverage data and separately enforces line, statement, branch, and
function percentages so a combined score cannot mask a deficient metric. Exit
0 was observed. Phase 5 gateway coverage reported 2,883/2,958 lines (97.46%),
817/891 branches (91.69%), and 583/606 methods (96.20%). Traffic simulator,
catalog, orders, payments, and portal each
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

All returned exit 0. The 260-test gateway suite includes Phase 1 algorithms,
atomic Fixed Window and Token Bucket adapters, repeated concurrent calls through three
Lettuce clients, real PostgreSQL migration/constraints/transactions/concurrent
activation, strict admin/auth contracts, outbox/Pub/Sub/reconciliation,
immutable snapshot concurrency, structured logging, reactive forwarding, and
per-policy dependency-aware readiness.

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
started by the Phase 4 Compose topology.

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

Both returned exit 0 with 15 passing tests. Strict typed Fixed Window/Token Bucket policy,
event, snapshot, traffic, error, and populated admin OpenAPI examples pass;
invalid/unknown fields fail at asserted stable paths.

## Containers

### Compose and Dockerfile validation

```bash
ADMIN_BEARER_TOKEN=test-only POSTGRES_PASSWORD=test-only docker compose config --quiet
scripts/lint-dockerfiles.sh
```

Both returned exit 0. Hadolint runs from pinned image `hadolint/hadolint:v2.12.0-alpine`.

### Image build

```bash
docker compose build gateway-1 gateway-2 gateway-3 mock-catalog-service
docker compose run --rm --no-deps load-balancer \
  haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg
```

The gateway/catalog images build successfully. The pinned HAProxy 3.0.8 image
validates the checked-in configuration; Redis 7.4.2, PostgreSQL 17.6, and
HAProxy use pinned tags.

### Health and smoke

```bash
scripts/container-smoke.sh
```

Generates ephemeral admin/PostgreSQL credentials; builds and starts HAProxy,
three gateways, Redis, PostgreSQL, and catalog; waits for health; bootstraps the
catalog policy through the admin API; checks each dependency; and shuts down
through a trap.

### End-to-end acceptance

```bash
scripts/phase3-e2e.sh
scripts/phase3-redis-failure-e2e.sh
scripts/phase4-e2e.sh
scripts/phase4-publication-failure-e2e.sh
scripts/phase5-token-bucket-e2e.sh
scripts/phase5-token-bucket-resilience-e2e.sh
```

The first script proves one global five-request limit through three replicas,
responses from multiple instance IDs, Redis count/TTL/key hygiene, replica
restart continuity, enabling a third replica without extra capacity, and
unhealthy-replica removal. The second pauses Redis and proves fail-open
forwarding/degraded metadata, fail-closed 503/readiness/HAProxy removal, exact
catalog counts, no local fallback, and recovery from surviving Redis state.
Both exited 0 and cleaned up containers and volumes.

`phase4-e2e.sh` proves version 1 five-of-six behavior, version 2 two-of-three
activation without container restart, multiple gateway IDs, equal replica
snapshot revisions, separate versioned Redis keys with version 1 retained,
missed-event reconciliation, restart loading from PostgreSQL, and invalid-update
stability. `phase4-publication-failure-e2e.sh` stops Redis after commit, proves
the outbox retry is retained, observes reconciliation convergence, restores
Redis, and observes publication complete. Both use bounded polling and cleanup
traps.

Those Phase 4 scripts passed against the completed propagation implementation
after the final readiness/subscription-observability refinement. The complete
Phase 5 behavior script proves initial burst, continuous refill, request cost
three, three repeated 60-request/three-replica capacity-20 trials, and dynamic
Fixed Window/Token Bucket switching. The resilience script proves missed-event
reconciliation, restart/removal/restoration state continuity, and Token Bucket
FAIL_OPEN/FAIL_CLOSED behavior. Both passed standalone and in the complete
`scripts/verify.sh` closeout rerun, which cleaned every environment.

### Destructive cleanup

The local Redis and PostgreSQL development state is disposable. The required
cleanup command is:

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

The detailed Phase 5 milestone and RED-GREEN-REFACTOR evidence is recorded in
the completed Phase 5 ExecPlan.

## Phase 6 Sliding Window Counter

Focused implementation verification:

```bash
conda run -n rate-limiter python -m pytest contracts/tests -q
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test \
  --tests '*SlidingWindowCounterPolicyDefinitionTest' \
  --tests '*PolicyMigrationTest' \
  --tests '*PostgresPolicyRepositoryTest' --no-daemon
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test \
  --tests '*RedisSlidingWindowCounterArithmetic*' \
  --tests '*SlidingWindowCounterApproximationTest' \
  --tests '*RedisSlidingWindowCounterStateAdapterTest' --no-daemon
```

Composed behavior and resilience proof:

```bash
scripts/phase6-sliding-window-counter-e2e.sh
scripts/phase6-sliding-window-counter-resilience-e2e.sh
```

The behavior program covers normal weighting, the Fixed Window boundary-burst
comparison, cost three, three repeated 60-request HAProxy concurrency trials,
three Redis-time-coordinated transition trials, exact backend delivery, replica
distribution, TTL/state inspection, and Fixed→Sliding→Token→Sliding switching.
The resilience program covers missed-event reconciliation, version isolation,
gateway restart and replica removal/restoration, FAIL_OPEN degraded forwarding,
and FAIL_CLOSED correlated 503 behavior. Both scripts install unconditional
container/volume cleanup traps.

The active Phase 6 ExecPlan records every observed RED/GREEN command, complete
quality-gate output, and final repository evidence. The durable algorithm and
experiment reports are `docs/architecture/DISTRIBUTED_SLIDING_WINDOW_COUNTER.md`
and `docs/experiments/SLIDING_WINDOW_COUNTER_APPROXIMATION.md`.
