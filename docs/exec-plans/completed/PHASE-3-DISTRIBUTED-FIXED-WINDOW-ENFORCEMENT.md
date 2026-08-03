# Phase 3 ExecPlan — Distributed Fixed-Window Enforcement

**Status:** Completed  
**Owner:** Codex, supervised by repository owner  
**Last updated:** 2026-08-02

## Purpose

Replace the catalog fixed-window policy's authoritative process-local counter
with one atomic Redis decision and prove that a five-request logical limit is
shared by three stateless gateway replicas behind HAProxy. Preserve explicit
`IN_MEMORY` mode for single-instance education and tests. No other algorithm
receives a Redis implementation in this phase.

## Scope and non-scope

Included: a fixed-window-specific state adapter boundary; reactive Redis/Lettuce
integration; Redis server time; versioned Lua; deterministic hashed keys and
TTL; configurable fail-open/fail-closed behavior; structured decision logs;
Redis-aware readiness; three gateways behind health-checking HAProxy; real
Redis Testcontainers concurrency tests; restart, scaling, unhealthy-replica,
and Redis-outage acceptance scenarios.

Excluded: Redis implementations of the other four algorithms, PostgreSQL,
dynamic policy updates, Pub/Sub, admin behavior, additional backends,
Prometheus/Grafana, Kubernetes, Redis Cluster/Sentinel deployment, cross-region
limiting, authentication, and request queueing.

## Current repository state and independently verified baseline

At commit `0541e4a`, Phase 1 provides storage-independent in-memory algorithms
and Phase 2 provides one local fixed-window gateway/catalog path. The current
orchestration class owns a per-identity in-memory registry; HTTP, static policy,
identity, forwarding, response mapping, and the Phase 1 domain are separate.
ADR 0011 makes this distributed fixed-window slice the next phase even though
the older `PROJECT_SPEC.md` phase list is stale.

Baseline rerun on 2026-08-02:

- Java 21.0.8, Python 3.12.13, Node 24.10.0, npm 11.6.0, Docker 27.5.1,
  Compose 2.32.4.
- Fresh `:gateway:compileJava :gateway:compileTestJava --rerun-tasks` passed.
- Fresh gateway tests passed: 102 tests, zero failures/errors/skips.
- Repository tests passed: traffic simulator 1, catalog 11, orders 1,
  payments 1, contracts 10, portal 1.
- Formatting, Checkstyle, Ruff, mypy, ESLint, and TypeScript passed.
- Coverage passed: gateway 626/635 lines (98.58%), 174/186 branches
  (93.55%), 139/139 methods; every other executable reported 100% of its
  supported metrics.
- Compose validation, pinned Hadolint, image builds, container health smoke,
  and `scripts/phase2-e2e.sh` passed. The E2E proof observed five catalog
  deliveries and one 429, then cleaned up.
- `jdeps` reported only `java.base` and self-package references for the Phase 1
  limiter domain. `git diff --check` and scoped placeholder/skip scans passed.
- Initial Gradle-cache and Docker-socket sandbox denials were environmental and
  passed with approved access. A broad marker scan matched quality-document
  prose; the correctly scoped source/test scan passed.

## Proposed design

### Storage-independent semantics

For state `(windowId, count)` and positive cost one, use the Phase 1 fixed
window behavior: reset count when the Redis-time-derived epoch window changes;
allow when `cost <= limit - count`; increment only an allowed cost; keep a
rejected count unchanged; report remaining `limit - countAfter`, rejection
retry at the exclusive boundary, and full reset at that same boundary.

Invariants: exactly the configured limit can be admitted per
policy/version/identity/window; rejected cost is not charged; no counter can
survive without expiration; replica count does not change capacity; Redis
failure never activates a local counter; and backend forwarding is never
subscribed for a rejection or fail-closed result.

### State boundary and application result

Add only `FixedWindowStateAdapter`, implemented by explicit
`InMemoryFixedWindowStateAdapter` and `RedisFixedWindowStateAdapter`. The
adapter emits a reactive result containing the domain decision, current count,
authoritative reset delay, state backend, and sanitized Redis outcome.
`RateLimitService` maps it to `ALLOW`, `REJECT`, `DEGRADED_ALLOW`, or
`STATE_UNAVAILABLE`. HTTP remains unaware of Redis commands and scripts.

Configuration is validated: `stateBackend=REDIS|IN_MEMORY`,
`failureMode=FAIL_CLOSED|FAIL_OPEN`, bounded instance ID, development instance
header flag, and positive Redis command timeout. Defaults are Redis and
fail-closed. Fail-open remains ready during an outage; fail-closed becomes
unready. Distributed limits are bounded to 1..1,000,000, windows to 1 ms..24 h,
and request cost remains one.

### Redis representation and operation

Identity SHA-256 input is a length-delimited UTF-8 sequence containing the
component type/name/value for normalized `x-client-id` and normalized route ID;
client values remain case-sensitive. Exact key:

```text
ratelimit:{p=<base64url-policy-id>:v=<version>:a=fixed-window:i=<sha256>}:w=<window-id>
```

It contains no raw client value, isolates policy versions, bounds key length,
and gives all windows for one policy/version/identity one cluster-compatible
slot without making all identities share a slot. One string counter is created
per active identity/window and expires exactly at the exclusive boundary.

The adapter obtains Redis TIME as a routing hint. The versioned repository Lua
script obtains Redis TIME again, truncates to milliseconds, derives and
validates the candidate window/key, reads or initializes the counter,
increments only an allowed request, applies `PEXPIREAT`, and returns ten RESP
integers: contract version, outcome, current count, remaining, limit,
retry-after milliseconds, reset epoch milliseconds, Redis-now milliseconds,
window ID, and TTL milliseconds. Outcome 0 is reject, 1 allow, and 2 a
non-mutating window mismatch. At most two mismatch retries are allowed;
completed decisions are never retried. Invalid arguments/state raise a script
error. Java validates the entire tuple before constructing a decision.

Use one singleton Spring `RedisScript<List>` backed by
`redis/fixed-window-v1.lua`; the standard reactive executor owns EVALSHA/EVAL
cache-miss handling. Spring Boot 3.5.16 manages the reactive Redis/Lettuce and
Testcontainers versions. Pin `redis:7.4.2-alpine` for Compose and tests.

### Failure, HTTP, health, and logs

Classify `ALLOWED`, `REJECTED`, `TIMEOUT`, `CONNECTION_FAILURE`,
`SCRIPT_ERROR`, `MALFORMED_STATE`, `MALFORMED_RESPONSE`, and
`WINDOW_MISMATCH_EXHAUSTED`. Fail-open forwards without authoritative counter
mutation or capacity headers and returns `X-RateLimit-Degraded: true`.
Fail-closed returns structured 503 `RATE_LIMIT_STATE_UNAVAILABLE`, preserves
correlation ID, and never forwards. Neither mode falls back to memory.

Every Compose response exposes development-only `X-Gateway-Instance`. JSON
structured decision logs contain correlationId, gatewayInstance, policyId,
policyVersion, algorithm, stateBackend, a 16-hex identity-hash prefix,
decision, degraded, failureMode, and redisOutcome. No raw identity, full hash,
Redis key, script detail, or exception text is logged.

Liveness is process-only. Readiness always includes catalog; Redis failure is
DOWN for fail-closed and an UP/degraded detail for fail-open. `IN_MEMORY` does
not require Redis.

### Development topology

Pin `haproxy:3.0.8-alpine` and run load-balancer, gateway-1, gateway-2,
gateway-3, redis, and mock-catalog-service. HAProxy uses round-robin, no
affinity configuration, and active readiness checks. Publish HAProxy on 8080,
catalog observation on 8101, and direct development gateway ports 8081..8083.
Ordering uses `service_started`; application health establishes readiness.

## Contract and schema changes

- Extend the strict error schema/examples with structured 503 state-unavailable.
- Add development response headers `X-Gateway-Instance` and degraded-only
  `X-RateLimit-Degraded`.
- Add the internal fixed-window state-adapter/application-result contract and
  the versioned ten-integer Lua result contract.
- Add validated application configuration for backend selection, failure mode,
  instance identity, header exposure, and timeout.
- No admin OpenAPI, policy JSON Schema, database, event, or metrics contract
  changes.

## Data, security, and migration

Switching to Redis intentionally discards old local counters. Policy versions
start fresh and old window keys expire by their own exclusive boundary; no
migration is required. Policy ID is bounded and base64url-encoded in the key;
identity uses full SHA-256 only in Redis and a short prefix in logs. Script and
Java validate arithmetic independently. A timed-out command may already have
executed; it is not replayed or refunded. Redis Cluster is not deployed, but
the one-key operation and hash tag are compatible.

## Milestones and TDD checkpoints

1. Contract/configuration/state port: RED 503/schema, configuration,
   adapter-selection, and reactive orchestration tests; GREEN minimal port,
   in-memory adapter, application result, and HTTP preservation.
2. Redis representation: RED key/hash/decoder/bounds and real-Redis behavior,
   TTL, expiry, isolation, server-time, malformed-state, and NOSCRIPT tests;
   GREEN key factory, script, strict decoder, and reactive adapter.
3. Atomicity: RED synchronized calls through three independent clients and
   application instances; GREEN exactly 50 of 100 accepted for at least 20
   unique-key repetitions, race-safe expiry, and no rejected increment.
4. Failure/readiness/logging: RED timeout, connection, script, malformed,
   fail-open/closed, recovery, HTTP, logging, and health tests; GREEN explicit
   mapping with no local fallback.
5. HAProxy/Compose: RED acceptance script against the Phase 2 topology; GREEN
   pinned Redis/HAProxy and three replicas; prove 5+1, multiple instance IDs,
   backend count five, Redis count/TTL, and no raw client key.
6. Restart/scale/outage: prove restart persistence, enabling a third replica,
   unhealthy removal, fail-open forwarding, fail-closed 503, and recovery.
7. Documentation/gates: add ADRs 0012..0015, update architecture/semantics,
   reconcile project phase ordering, update commands, and run every gate.

Each milestone records the exact RED command and expected failure, minimum
GREEN implementation, focused pass, refactor, affected suite, and evidence in
the progress log below before moving on.

## Validation plan

Focused Gradle tests cover state contracts, Redis key/decoder/integration,
concurrency, failure modes, readiness, HTTP, and logging. Full closeout runs:

```text
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
scripts/phase3-e2e.sh
scripts/phase3-redis-failure-e2e.sh
jdeps domain verification
git diff --check
forbidden-import and placeholder/skipped-test scans
```

The gateway and every mock executable retain independent 90% line, branch, and
method/function/statement gates. Lua is covered through real Redis behavior,
TTL, cache-miss, malformed-state, concurrency, and multi-replica tests.

## Progress log

- 2026-08-02 — Read root/gateway/mock instructions, repository skills,
  architecture, policy, semantics, quality documents, completed Phase 1/2
  plans, ADRs, commands, and current implementation.
- 2026-08-02 — Independently ran and recorded the clean baseline above.
- 2026-08-02 — Repository owner selected configurable fail-open and
  fail-closed, default fail-closed, with fail-open remaining ready.
- 2026-08-02 — Activated this ExecPlan before production implementation.
- 2026-08-02 — Milestone 1 contract RED: the 11-test focused contract suite
  failed one expected test because the existing schema rejected status 503 and
  `RATE_LIMIT_STATE_UNAVAILABLE` at stable `status` and `error` paths.
- 2026-08-02 — Milestone 1 contract GREEN: extended the strict error contract
  and added the approved example; all 11 focused contract tests passed.
- 2026-08-02 — Milestone 1 state-port RED: focused test compilation failed
  with 26 expected missing adapter, result, backend, failure-mode, and reactive
  orchestration symbols.
- 2026-08-02 — Milestone 1 state-port GREEN/REFACTOR: moved the local registry
  behind `InMemoryFixedWindowStateAdapter`, made orchestration reactive, and
  supplied authoritative reset duration to HTTP mapping. Focused adapter,
  service, and handler tests passed.
- 2026-08-02 — Milestone 1 configuration RED failed with six expected missing
  runtime-property accessors/constructor fields. GREEN added validated backend,
  failure mode, instance, header, timeout, limit, and window bounds. The focused
  configuration suite and full 111-test gateway suite passed.
- 2026-08-02 — Milestone 2 key/decoder RED compilation failed with 16 expected
  missing Redis key, result, and safe-exception symbols. GREEN added the exact
  versioned/base64url/SHA-256 key and strict ten-integer decoder; the focused
  key and decoder tests passed.
- 2026-08-02 — Milestone 2 identity RED changed the canonical digest fixture
  and failed one of five focused identity tests as expected. GREEN changed the
  SHA-256 input to length-delimited `HEADER/x-client-id/value` and
  `ROUTE_ID/catalog-route` components; all five focused tests passed.
- 2026-08-02 — Milestones 2/3 real-Redis RED compilation failed with 23
  expected absent reactive Redis, Testcontainers, and adapter symbols. The
  first GREEN execution exposed and corrected Lua-pattern and script-argument
  integration errors, then two test-assumption corrections captured Spring's
  persistent-key TTL representation and direct adapter exception shape.
- 2026-08-02 — Milestones 2/3 GREEN/REFACTOR added Boot-managed reactive
  Redis/Lettuce, Boot-managed Testcontainers, the reviewed Lua resource, TIME
  hint and independently validated Redis TIME, exact-boundary `PEXPIREAT`,
  strict response decoding, sanitized failure classification, and standard
  EVALSHA/EVAL recovery. The focused key/decoder/integration suite passed.
  Real `redis:7.4.2-alpine` proved limit edges, identity/route/version
  isolation, TTL assignment and repair, expiry, `SCRIPT FLUSH` recovery,
  malformed state, and 20 unique-key repetitions of 100 simultaneous requests
  through three independent Lettuce clients: every repetition allowed exactly
  50, stored count 50, and retained a positive TTL.
- 2026-08-02 — Adapter-selection RED failed compilation on the absent
  `FixedWindowStateConfiguration`. GREEN added property-conditional selection,
  one singleton classpath-backed script, Redis as the default, and explicit
  in-memory selection; both focused context-runner tests passed. Dependency
  insight verified Lettuce 6.6.0.RELEASE and Testcontainers 1.21.4 under Boot
  3.5.16 dependency management.
- 2026-08-02 — Milestone 4 orchestration RED propagated every injected Redis
  state exception in both failure modes. GREEN mapped all six failure outcomes
  to `DEGRADED_ALLOW` or `STATE_UNAVAILABLE`; focused failure-mode and existing
  service tests passed. No local adapter is constructed or invoked on failure.
- 2026-08-02 — Milestone 4 HTTP RED failed compilation on missing instance
  configuration. GREEN added development instance headers, degraded forwarding
  without misleading capacity headers, strict correlated 503 mapping, and
  fail-closed forwarding short-circuit. All focused handler tests passed and
  catalog attempts were one for fail open and zero for fail closed.
- 2026-08-02 — Milestone 4 readiness RED failed compilation on the absent
  indicator. GREEN made IN_MEMORY independent of Redis, Redis success UP,
  fail-open outage UP/degraded, and fail-closed outage DOWN, all with bounded
  ping timeout and sanitized details. Four focused tests passed.
- 2026-08-02 — Milestone 4 logging RED failed compilation on the absent logger.
  GREEN added SLF4J key/value decision logging and Boot Logstash JSON console
  output with all eleven required fields and only a 16-hex identity prefix.
  Focused logging and HTTP regression tests passed.
- 2026-08-02 — The affected full gateway suite passed with 135 tests before
  validation-coverage additions, zero failures. The final suite contains 140
  tests, zero failures/errors/skips.
- 2026-08-02 — Milestone 5 topology RED: `scripts/phase3-e2e.sh` rejected the
  existing Compose services `catalog` and `gateway`. GREEN added pinned
  `redis:7.4.2-alpine`, pinned `haproxy:3.0.8-alpine`, catalog, three gateways,
  direct development ports, active readiness checks, and no affinity. Compose
  validation passed. HAProxy config-only validation initially could not resolve
  absent service DNS; safe `init-addr last,libc,none` made validation exit 0
  while normal Compose startup resolved all created services.
- 2026-08-02 — First topology acceptance made Redis, catalog, and all gateways
  healthy but found HAProxy's `localhost` health probe selecting IPv6 while its
  listener was IPv4. The probe now uses `127.0.0.1`; all six containers became
  healthy. The first restart attempt also documented that this development JVM
  needs about 11 seconds to become healthy, longer than the policy window. The
  final scenario sends continuation traffic through the still-healthy replicas
  before waiting for the restarted instance, which tests distributed state
  without hiding the legitimate fixed-window boundary reset.
- 2026-08-02 — Milestones 5/6 final multi-replica acceptance exited 0. Six
  same-client requests through HAProxy produced five 200 and one 429, exactly
  five catalog deliveries, at least two instance IDs, Redis count five,
  positive TTL no greater than ten seconds, and no raw client key material.
  Restart, enabling a paused third replica, and stopping an unhealthy replica
  all preserved the one logical limit; remaining replicas served without 5xx.
- 2026-08-02 — Milestone 6 Redis-outage acceptance exited 0 for both isolated
  modes. FAIL_OPEN readiness stayed UP; six outage requests forwarded with
  degraded headers; recovery continued the pre-outage count and ignored outage
  traffic. FAIL_CLOSED readiness returned 503; a direct request returned the
  strict correlated 503; catalog count did not change; HAProxy returned 503
  after removing all replicas; recovery continued the retained count. Cleanup
  unpaused Redis and removed containers, volumes, and orphans.
- 2026-08-02 — Milestone 7 added ADRs 0012..0015 and updated algorithm
  semantics, system architecture, project phase ordering, commands, README,
  topology, mode comparison, limitations, CI naming, and required structure.
- 2026-08-02 — The first final coverage run exposed 82% gateway branch
  coverage, below the unchanged 90% gate. Added meaningful decoder, numeric
  bound, exception-classification, key, and result-invariant tests without
  exclusions or threshold changes. Final JaCoCo is 959/975 lines (98.36%),
  316/338 branches (93.49%), and 195/196 methods (99.49%). Traffic simulator,
  catalog, orders, payments, and portal remain 100% on supported metrics.
- 2026-08-02 — Final formatting, Checkstyle, Ruff, mypy, ESLint, TypeScript,
  138-test gateway and all repository tests, 11 contract tests, all coverage
  gates, artifact builds, pinned Hadolint, Compose config, HAProxy config,
  container smoke, both acceptance scripts, `git diff --check`, shell syntax,
  forbidden domain imports, and scoped placeholder/skipped-test scans passed.
  `jdeps` again reported only `java.base` and self-package dependencies. Final
  `docker compose ps --all` showed only the empty table header.
- 2026-08-02 — Final aggregate `scripts/verify.sh` exited 0. It replayed
  structure/CI checks, formatting, all static analysis, every test suite,
  independent coverage, contracts, builds, Compose validation, Dockerfile
  linting, six-service smoke, shared-limit/restart/scale/removal acceptance,
  both Redis outage modes, and reliable cleanup. The plan moved to completed.
- 2026-08-02 — A final scope audit made the requested behavioral compatibility
  fixture explicit rather than relying on equivalent separate tests.
  `FixedWindowStateAdapterContractTest` runs the same cost-one limit,
  rejection, remaining, retry, reset-delay, and identity-isolation assertions
  against a fresh in-memory adapter and real pinned Redis adapter. Its focused
  run and a fresh 140-test JaCoCo run passed; coverage remained 98.36% line,
  93.49% branch, and 99.49% method.

## Decision log

- Use a fixed-window-only adapter rather than a generic state repository.
- Use Redis Lua plus Redis TIME; the key-window validation is part of the
  atomic operation and mismatch attempts cannot mutate.
- Use exact boundary expiration without a grace period.
- Use built-in Spring reactive scripting/cache fallback and structured logging
  instead of custom caches or logging dependencies.
- Use HAProxy because its community image provides active HTTP health checks.
- Global Phase 3 failure mode is configurable; per-policy overrides remain a
  later policy-control concern.

## Discoveries and surprises

- `PROJECT_SPEC.md` still uses the pre-ADR 0011 delivery numbering and must be
  reconciled as documentation work in this phase; it is now reconciled through
  Phase 9.
- Spring Data Redis represents a persistent key as `Duration.ZERO` from its
  reactive TTL API; the test deliberately removes TTL, asserts that public API
  representation, then proves the Lua script repairs positive boundary TTL.
- A Redis script argument list must be expanded as varargs for Spring's script
  executor; passing the Java list as one argument correctly failed Lua contract
  validation during the first GREEN run.

## Risks and limitations

- Redis wall-clock rollback can revisit an older epoch window; this differs
  from the in-memory limiter's per-instance clock clamp and will be documented.
- Timeout after server execution is ambiguous; no automatic replay occurs.
- Testcontainers and all container/E2E gates require a working Docker daemon.
- Cost remains one and only fixed window is distributed in this phase.

## Final outcome

Phase 3 is complete. Real HAProxy traffic reached multiple replicas while six
same-client requests yielded exactly five backend deliveries and one 429.
Replica restart, enabling a third replica, unhealthy removal, fail-open,
fail-closed, recovery, concurrency, coverage, architecture-boundary, container,
and aggregate verification evidence all passed with no approved-scope
deviation.
