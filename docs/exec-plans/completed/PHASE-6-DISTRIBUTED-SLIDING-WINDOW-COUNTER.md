# Phase 6 — Distributed Sliding Window Counter

Status: Complete  
Owner: Codex  
Last updated: 2026-08-05

## Purpose

Add Sliding Window Counter as the third fully integrated distributed rate-limiting algorithm. Administrators will be able to create and activate a typed `SLIDING_WINDOW_COUNTER` policy; PostgreSQL will preserve its immutable versioned definition; Pub/Sub and reconciliation will distribute it; each gateway will compile an immutable snapshot; and one atomic Redis operation will enforce shared weighted-window state across gateway replicas. Fixed Window and Token Bucket remain supported.

This file is the durable execution record. Every implementation slice records its observed RED failure, minimum GREEN change, focused verification, refactor, affected suite, discoveries, and exact commands.

## Scope and non-scope

Included:

- strict contract, API, domain, persistence, and snapshot support for `FIXED_WINDOW | TOKEN_BUCKET | SLIDING_WINDOW_COUNTER`;
- a forward-only V3 Flyway migration and V2-to-V3 upgrade proof;
- exact bounded integer weighted arithmetic and analytical retry timing;
- atomic Redis server-time state rotation, admission, persistence, and expiry;
- real-Redis boundary, malformed-state, concurrency, version/identity/algorithm isolation, and `NOSCRIPT` tests;
- runtime/HTTP/failure/observability integration, dynamic activation, stale-event resistance, reconciliation, restart/scaling proof;
- an exact in-memory sliding-log oracle used only for tests and education;
- retained Phase 2–5 acceptance plus Phase 6 behavior and resilience programs;
- ADRs, architecture documentation, and completion evidence.

Excluded: production Sliding Window Log or Leaky Bucket, queueing, client-controlled time, dynamic request-cost expressions, arbitrary scripts, a generic algorithm plug-in framework, React administration, Prometheus/Grafana, OAuth, Kubernetes, Redis Cluster/Sentinel, and cross-region enforcement.

## Current repository state

Initial repository evidence, recorded before any Phase 6 edit:

- Date: 2026-08-05.
- Branch: `main...origin/main`; HEAD `5432b44d89761d72920b9448ce635446eee526b2` (`feat: implement token bucket mechanism`).
- Contrary to the initiating prompt's expectation, `git status --porcelain=v2`, `git diff --stat`, and `git diff --summary` were empty. No uncommitted Phase 5 changes were present.
- The status was rechecked immediately before this plan was added and remained empty. Any later user changes must be preserved; work stops before an overlapping edit.
- V1 and V2 are immutable. Phase 6 schema changes use only V3.

Independently observed Phase 5 baseline:

- Compilation passed with all three gateway compilation tasks executed.
- Spotless, Ruff formatting/lint, Checkstyle, mypy, ESLint, TypeScript, and Prettier passed.
- Gateway: 260 tests, zero failures, errors, or skips.
- Focused PostgreSQL/Flyway: 15 tests passed, including empty V1+V2 and V1-to-V2 upgrade.
- Focused real-Redis/property/trace: 18 tests passed; arithmetic property used 1,000 generated cases.
- Gateway coverage: 2,883/2,958 lines (97.46%), 817/891 branches (91.69%), 582/606 methods (96.04%).
- Other executables: traffic simulator 1, catalog 11, orders 1, payments 1, portal 1, and contracts 15 tests passed; every supported non-gateway coverage metric was 100%.
- Retained acceptance passed: Phase 2; Phase 3 distributed enforcement and Redis failures; Phase 4 propagation and publication failure; Phase 5 behavior/concurrency/switching and resilience.
- `jdeps` reported only `java.base` and limiter self-packages.
- Repository/CI structure, shell syntax, scoped placeholder/skipped-test/forbidden-import scans, `git diff --check`, and final empty Compose state passed.
- Two non-product setup failures are retained as evidence: initial sandboxed Gradle cache access was denied and the approved rerun passed; the first `jdeps` command resolved `/bin/jdeps` because of same-command variable expansion and passed after using the resolved JDK path.

Relevant implementation boundaries:

- `gateway` holds API DTOs, sealed policy definitions, PostgreSQL repository conversion, snapshot compilation, runtime dispatch, HTTP handling, and Redis adapters.
- `contracts` holds strict JSON Schema, OpenAPI, examples, and validation tests.
- V2 stores Fixed Window and Token Bucket subtypes behind a parent algorithm discriminator and deferrable composite foreign keys.
- Fixed Window and Token Bucket already use Redis `TIME`, privacy-safe versioned keys, reviewed Lua resources, strict tuple decoding, and safe `NOSCRIPT` recovery.
- Pub/Sub invalidation and reconciliation compile candidate snapshots completely before atomic replacement.
- The Phase 1 domain contains a framework-free in-memory sliding counter useful for compatibility traces, but production has no distributed Sliding Window Counter path.

## Proposed design

The external algorithm object remains wrapped and discriminated:

```yaml
algorithm:
  type: SLIDING_WINDOW_COUNTER
  configuration:
    limit: 100
    window: 60s
    requestCost: 1
```

The third branch is explicit at every boundary. A dedicated duration value preserves a positive integer plus `ms|s|m|h|d`. Bounds are `limit` 1..1,000,000, window 1..86,400,000 milliseconds, and request cost 1..limit. Decimal values, unknown fields/types, cross-algorithm fields, missing fields, and unsafe values are rejected before activation and rechecked at compilation/Redis boundaries.

Redis server time defines epoch-aligned half-open windows. With `W=windowMilliseconds`, `L=limit`, `C=requestCost`, `e=elapsed`, `p=previousCount`, and `c=currentCount`, admission uses exact integer cross-multiplication:

```text
N = c*W + p*(W-e)
allow iff N + C*W <= L*W
```

The returned weighted estimate is `ceil(N/W)` after the decision. Remaining capacity is `max(0,floor((L*W-N)/W))`, the largest whole cost immediately admissible. Explicit zero saturation covers conservative same-window clock rollback; arithmetic overflow is never clamped. The maximum intermediate bound `3*L*W = 2.592e14` is below Lua's exact integer ceiling `2^53-1`.

State is a Redis hash keyed by policy, version, algorithm, and identity hash. It contains only `window_id`, `current_count`, and `previous_count`. The Lua operation obtains time, validates and rotates state, decides, conditionally increments, persists valid rotation, sets deterministic absolute expiry, and returns a strictly versioned integer tuple. Missing, same-window, one-window, and multi-window advancement are constant-time. A current window older than stored state returns `CLOCK_ROLLBACK` without mutation.

For rejection, let `T=(L-C)W` and `b=W-e`. If `cW <= T`, exact retry milliseconds are `ceil((N-T)/p)` in the current window. Otherwise retry is `b + ceil((cW-T)/c)` in the following window. Zero divisors use the corresponding analytical case. HTTP `Retry-After` ceilings this value to seconds. `RateLimit-Reset` is the ceiling duration until all represented weighted usage reaches zero without further traffic.

V3 extends the parent discriminator and creates `sliding_window_counter_configurations` with a constant child discriminator, bounded fields, amount/unit duration, deferrable composite foreign key, and activated-child immutability. Repository reads require exactly one compatible subtype. Runtime compilation maps the explicit third algorithm to its own adapter; failed candidate compilation leaves the previous complete snapshot installed.

## Invariants

- Every distributed Sliding Window Counter decision and rotation is one atomic Redis operation.
- Rejected requests never increment state and never reach a backend.
- Gateway replica count does not multiply weighted capacity.
- Fixed Window and Token Bucket behavior/contracts remain compatible.
- Policy, version, algorithm, and hashed identity namespaces cannot collide; raw identity never enters Redis keys or logs.
- Counts remain integers in `[0, limit]`; all admission math is exact and bounded below `2^53`.
- Redis server time is authoritative; no production client timestamp path exists.
- Activated policy definitions and subtype rows are immutable; switching algorithm requires a new version.
- Malformed Redis state/result, unsafe arithmetic, script failure, and clock rollback fail safely through the existing FAIL_OPEN/FAIL_CLOSED policy without local authority.
- Candidate snapshot installation is all-or-nothing; proxy requests never query PostgreSQL.
- TTL remains present on persisted state and stale state expires without scanning.

## Milestones

### 1. Active plan, contracts, and ADRs

Expected files: this plan; contract schema/OpenAPI/examples/tests; ADRs 0028 onward.

- RED: add valid/invalid contract examples and tests for the third discriminator, strict fields, decimals, numeric bounds, typed responses, and match-test; observe the fixed/token-only schemas reject or fail to reject them.
- GREEN: extend only the closed union and schemas; add ADRs for integer arithmetic, Redis state/rotation/TTL, retry timing, and headers.
- Refactor boundary: preserve existing branches and avoid generic maps.
- Focused verification: `conda run -n rate-limiter python -m pytest contracts/tests -q`.
- Broader verification: contract Gradle/schema integration tests and gateway API tests.
- Success: all three branches validate and malformed/cross-algorithm objects fail.

### 2. Typed policy and V3 persistence

Expected files: Java duration/definition/DTO/response types and tests; V3 migration; repository mapping and migration/repository tests.

- RED: validation, round-trip, immutability, version switching, no-state match-test; empty V1–V3, V2 fixture upgrade, subtype conflict, constraints, and activated-child immutability.
- GREEN: explicit sealed third type and typed conversion; forward-only V3 and exact-one-subtype repository logic.
- Refactor boundary: duration parsing is shared only where semantics match; no Token Bucket-specific type reuse.
- Focused verification: policy/API tests and focused PostgreSQL Testcontainer suites.
- Broader verification: gateway policy lifecycle/propagation suite.
- Success: fields round-trip exactly, old rows upgrade, and database constraints reject inconsistent definitions.

### 3. Pure transition, retry, and approximation model

Expected files: bounded production arithmetic/transition types; deterministic and jqwik tests; BigInteger/rational reference; sliding-log oracle and experiment report.

- RED: beginning/middle/final instant, rotations, rollback, costs, exact boundary, overflow, maximum, and retry cases; generated comparison and brute-force retry.
- GREEN: exact Redis-compatible integer transition and analytical retry implementation.
- Refactor boundary: BigInteger and exact timestamp log remain test/education-only.
- Focused verification: arithmetic unit/property/trace suites.
- Broader verification: all Phase 1 algorithm tests and gateway domain suite.
- Success: generated production decisions match high-precision reference; experiment demonstrates fixed boundary burst and observed approximation differences without asserting an unproved universal bound.

### 4. Atomic Redis implementation

Expected files: Redis key/result/adapter/configuration; `redis/sliding-window-counter-v1.lua`; real-Redis tests.

- RED: initialization, same/one/multi-window, boundary, cost, rejection non-increment, isolation, TTL/expiry, malformed state/result, server time, and `SCRIPT FLUSH`.
- GREEN: reviewed Lua resource and thin strict Java adapter with NOSCRIPT-only recovery.
- Refactor boundary: common Redis infrastructure only where error/NOSCRIPT semantics are identical.
- Focused verification: pinned Redis 7.4.2 integration and trace suites.
- Broader verification: gateway state/application suite.
- Success: returned metadata and stored hash prove exact atomic behavior and deterministic positive TTL.

### 5. Concurrency and boundary proof

Expected files: independent-client and gateway integration/concurrency tests; package-private test-only deterministic executor if necessary.

- RED: coordinated initialization, same-window traffic, and traffic spanning an observed Redis boundary.
- GREEN: no additional production mechanism; repair Lua/adapter only if proof exposes defects.
- Refactor boundary: test time control cannot become an external or production argument.
- Focused verification: repeated Redis client/gateway trials.
- Broader verification: full Redis integration suite.
- Success: exactly one consistent rotation, exact cost-once, rejected non-increment, nonnegative counts, positive TTL, and capacity independent of replicas across repeated boundary trials.

### 6. Runtime, HTTP, failures, propagation, and observability

Expected files: compiled union/compiler/service/results, HTTP mapping/logging, application/propagation/reconciliation tests.

- RED: explicit dispatch, mixed snapshots, headers/429/503/no-backend, cost/weighted boundary, all Redis failures/recovery, logs, activation/switching/stale events/missed events/failed compilation.
- GREEN: third sealed runtime branch and Sliding Counter metadata while retaining existing behavior.
- Refactor boundary: no algorithm strings in HTTP handlers and no generic algorithm framework.
- Focused verification: runtime/HTTP/failure/propagation/reconciliation suites.
- Broader verification: gateway build and coverage.
- Success: all replicas converge without restart; old events cannot regress; a failed compile preserves the installed snapshot.

### 7. Acceptance, documentation, and closeout

Expected files: two Phase 6 scripts; repository/CI verification; architecture/requirements/command/index docs; this plan.

- RED: composed scenarios fail before complete integration.
- GREEN: behavior and resilience scripts use Redis time/metadata, bounded polling/barriers, fresh identities/versions, exact backend counts, gateway IDs, Redis state/TTL checks, repeated trials, and cleanup traps.
- Refactor boundary: helpers may remove shell duplication without obscuring scenario semantics.
- Focused verification: both Phase 6 programs.
- Broader verification: retained Phase 2–5, all quality gates, image builds/startup, hygiene, `jdeps`, and clean Compose state.
- Success: repeated multi-replica and transition scenarios prove shared weighted enforcement and atomic rotation.

## Contract and schema changes

- Policy JSON Schema and OpenAPI add a strict `SLIDING_WINDOW_COUNTER` discriminator branch with `limit`, `window`, and `requestCost`.
- Admin list/version output returns the same typed configuration; match-test exposes the selected type without state mutation.
- V3 extends PostgreSQL's algorithm discriminator and adds a normalized Sliding Counter subtype.
- Redis key: `ratelimit:{p=<base64url-policy-id>:v=<version>:a=sliding-window-counter:i=<sha256>}`.
- Redis hash fields exactly: `window_id`, `current_count`, `previous_count`.
- Lua tuple is versioned and contains outcome, echoed policy values, window ID/start/elapsed, post-decision counts, weighted numerator/estimate, remaining capacity, retry/reset, Redis timestamp, TTL, and rotation code.
- Sliding Counter headers define Limit as configured weighted limit, Remaining as immediate whole-cost capacity, Reset as time until represented usage is zero, and Retry-After as conservative ceiling seconds for the rejected cost.

## Data and migration considerations

V1 and V2 are immutable. Empty database tests apply V1 through V3. Upgrade tests migrate a V2 database containing valid Fixed Window and Token Bucket records, then prove typed reads and activation remain valid. The parent `(policy_id, version, algorithm_type)` discriminator and a constant child discriminator are connected by a deferrable composite foreign key; subtype primary keys prevent duplicates, and repository conversion requires exactly one compatible subtype. Activated child rows are trigger-protected. Redis state is isolated by policy version, requires no data migration, and expires at its semantic horizon. No down migration is provided.

## Security and failure analysis

Strict input schemas and repeated bounds protect PostgreSQL, Java multiplication, Lua exactness, TTL/timestamp calculation, and operational duration. Lua validates stored fields and arguments before any mutation. Identity values are SHA-256 hashed and policy IDs are base64url encoded. FAIL_OPEN forwards during Redis failure with degraded metadata and no fabricated capacity; FAIL_CLOSED returns a correlated 503 and never a misleading 429. Timeout, connection, script, malformed state/result, and clock rollback are not retried as ambiguous mutations. Only NOSCRIPT triggers safe script reload/re-execution. Existing authentication and trusted identity extraction boundaries remain unchanged.

## Numeric bounds and timing semantics

- `limit`: 1..1,000,000.
- `window`: 1..86,400,000 milliseconds represented as integer plus `ms|s|m|h|d`.
- `requestCost`: 1..limit.
- Counts never exceed limit.
- `L*W <= 8.64e13`; documented maximum intermediate `3*L*W <= 2.592e14 < 2^53-1`.
- Redis epoch milliseconds are nonnegative exact integers in the supported horizon; TTL and reset/retry are no more than two windows and checked without silent clamp.
- At an exact boundary rotation occurs before admission. One-window advancement promotes old current to previous; larger advancement clears both without looping.

## Validation plan

Exact commands are appended to the progress log when executed. Required closeout includes:

- Gradle compilation, Spotless, Checkstyle, full tests, jqwik properties, JaCoCo report/verification;
- Ruff format/lint, mypy, pytest/coverage; ESLint, TypeScript, Prettier, Vitest/coverage;
- focused PostgreSQL V3 migration/upgrade/repository tests;
- pinned real-Redis Lua, malformed-state, TTL, trace, boundary, concurrency, Pub/Sub, reconciliation, HTTP, and failure suites;
- JSON Schema/OpenAPI/example validation;
- container/Dockerfile/Compose/HAProxy validation, builds, smoke startup;
- retained Phase 2–5 acceptance and both Phase 6 programs;
- `jdeps`, `bash -n`, repository/CI structure, placeholders, skipped tests, forbidden imports, `git diff --check`, final Git status/diff summary, and empty Compose state.

## Progress log

- 2026-08-05 — Rechecked the repository immediately before implementation: branch `main...origin/main`, HEAD `5432b44d89761d72920b9448ce635446eee526b2`, no status/diff output. Materialized this active plan before feature edits.
- 2026-08-05 — Baseline evidence above was independently executed rather than copied from Phase 5's completion claim. The two environment/setup failures and corrected passing commands are deliberately retained.
- 2026-08-05 — Contract RED: `conda run -n rate-limiter python -m pytest contracts/tests -q` ran 17 tests and failed 2 as expected. The valid Sliding Counter fixture was rejected at `algorithm.type`; OpenAPI lacked its discriminator mapping. Existing 15 tests passed. Minimum GREEN extends the closed schema/OpenAPI union and match-test enum. Standard JSON Schema cannot compare sibling values, so `requestCost <= limit` is enforced by typed DTO/domain validation and database constraints; the contract schema enforces each field's absolute integer bounds.
- 2026-08-05 — Contract GREEN: the same command passed all 17 tests in 0.32 seconds. Added ADRs 0028–0031 for integer arithmetic, Redis state/rotation/TTL, retry timing, and response headers.
- 2026-08-05 — Typed policy RED: focused Gradle test initially could not access the sandboxed user Gradle lock; the approved rerun reached compilation and failed with 16 expected missing-symbol errors for `WindowDuration`, `SlidingWindowCounterAlgorithmDefinition`, and `SLIDING_WINDOW_COUNTER`. Minimum GREEN adds only those explicit typed domain elements and safe field bounds.
- 2026-08-05 — Typed policy focused GREEN: `:gateway:test --tests '*SlidingWindowCounterPolicyDefinitionTest'` passed all 4 tests. Admin API RED then ran 18 tests with one expected failure: Jackson rejected the missing third discriminator before repository invocation. Minimum GREEN adds the explicit request/response DTO branch and exact window literal conversion.
- 2026-08-05 — Admin API GREEN: focused `AdminPolicyApiTest` passed all 18 tests. Persistence RED: focused `PolicyMigrationTest` ran 8 tests and failed the 3 new expectations—missing table on empty migration, only two migrations in the V2 upgrade, and unsupported Sliding Counter parent discriminator. Minimum GREEN adds only forward migration V3; V1/V2 remain unchanged.
- 2026-08-05 — Migration GREEN: after correcting the retained Phase 4 upgrade expectation from one migration to the now-valid V2+V3 pair, focused `PolicyMigrationTest` passed all 8 tests. Repository RED ran 10 tests with the new round-trip failing at the explicit unsupported-algorithm branch. Minimum GREEN extends typed joins/inserts/deletes/decoding to require exactly one of three subtype rows.
- 2026-08-05 — Repository GREEN: focused `PostgresPolicyRepositoryTest` passed all 10 tests. Pure arithmetic RED then failed compilation with 41 expected missing symbols for the bounded parameters/state/transition/rotation/arithmetic types and `CLOCK_ROLLBACK`. Minimum GREEN implements exact cross-multiplication, constant-time rotation, output rounding, analytical retry, and full-weight reset timing.
- 2026-08-05 — Deterministic arithmetic GREEN passed 5 tests. The first combined 1,000-case property run found a shrunken same-window rollback state (`L=1,W=1,c=1,p=1,e=0`) whose valid counts weigh above the limit; the initial negative remaining result violated its nonnegative result invariant. Remaining is now explicitly zero-saturated as immediate capacity, documented in ADRs 0028/0031, while overflow remains fail-fast.
- 2026-08-05 — Corrected deterministic/property arithmetic passed 6 tests, including 1,000 generated comparisons with `BigInteger` and bounded brute-force retry. Redis contract RED failed compilation with 6 expected missing-symbol errors for the exact key and strict script tuple decoder. Minimum GREEN adds privacy-safe key construction and an 18-integer versioned result decoder that recomputes all derived values.
- 2026-08-05 — Redis key/tuple GREEN passed 3 tests. Atomic adapter RED failed compilation with 13 expected missing symbols for the application adapter/result and Redis adapter. Minimum GREEN adds the thin typed adapter and reviewed `redis/sliding-window-counter-v1.lua`; the script performs Redis-time validation, constant-time rotation, exact admission/retry/reset, conditional increment, hash persistence, and `PEXPIREAT` atomically.
- 2026-08-05 — First real-Redis run executed 6 tests; 5 passed, including 10 repeated 60-call/3-client concurrency trials. The expiry+SCRIPT FLUSH assertion assumed the retry stayed in the same epoch window, but returned metadata correctly showed a boundary rotation and `current_count=0` on rejection. The test now asserts stored current count equals returned post-decision metadata, preserving the intended no-double-deduction proof without a wall-clock assumption.
- 2026-08-05 — Corrected real-Redis adapter suite passed all 6 tests. Runtime RED failed compilation with 5 expected errors for the explicit compiled algorithm, three-adapter service constructor, and Sliding Counter evaluation metadata. Minimum GREEN extends the sealed runtime union/compiler and typed service dispatch; no HTTP handler switches on algorithm strings.
- 2026-08-05 — Runtime compiler/dispatch GREEN passed 6 focused tests. In-memory teaching-adapter RED failed compilation with 2 expected missing-symbol errors. Minimum GREEN delegates to the same pure transition under an atomic per-key `ConcurrentHashMap.compute`; runtime configuration wires the explicit third adapter for both Redis and local in-memory modes.
- 2026-08-05 — Teaching adapter/wiring GREEN passed 2 focused tests. Observability RED ran 3 tests with the Sliding Counter log assertion failing at the first missing field. Minimum GREEN adds the requested structured window/count/elapsed/numerator/estimate/cost/remaining/retry/rotation/outcome values without logging raw identity.
- 2026-08-05 — Observability GREEN passed 3 tests. Three-algorithm HTTP contract suites passed, including Sliding Counter cost-three headers/429/backend short-circuit and TIMEOUT/CONNECTION/SCRIPT/MALFORMED_STATE/MALFORMED_RESPONSE/CLOCK_ROLLBACK under FAIL_OPEN and FAIL_CLOSED. Added explicit Fixed→Sliding→Token→Sliding refresh/reconciliation coverage with stale-revision resistance.
- 2026-08-05 — First three-algorithm refresh run exposed an intentional coordinator behavior: when a stale authoritative revision 2 arrives after local revision 3 while `highestRequested` remains 3, reconciliation immediately reloads and installs available revision 4. The test now asserts this repair directly rather than expecting the intermediate revision 3 to remain installed.
- 2026-08-05 — Approximation tests passed three deterministic/seeded cases. Fixed Window admitted ten requests around a limit-five boundary while Sliding Counter rejected at the boundary with previous count five. Hand-built traces demonstrated both error signs. Seed `0x5C1D1A6` over 10,000 generated traces observed maximum numerator overestimate 5,960 and underestimate 6,320 for a 1,000 ms window; no universal bound is claimed.
- 2026-08-05 — First complete gateway test run passed. The first JaCoCo verification reported branch coverage 88%, below the unchanged 90% gate. Added meaningful decoder/adapter/value-boundary tests for malformed outcomes and constructor invariants; focused tests passed. The repeated full suite and coverage verification passed with 15,240/15,685 instructions (97.16%), 1,048/1,152 branches (90.97%), 3,326/3,415 lines (97.39%), 648/674 methods (96.14%), and 164/165 classes (99.39%).
- 2026-08-05 — Added a real-Redis compatibility trace driven only by returned Redis server time. It passed against the Phase 1 in-memory counter, pure Redis-compatible arithmetic, production Lua adapter, and exact timestamp-list oracle; no production client timestamp path was introduced.
- 2026-08-05 — Phase 6 behavior acceptance first failed before product execution because the sandbox denied Docker socket access. The approved rerun built images and passed normal weighting, cost-three, Fixed Window boundary burst versus Sliding Counter rejection, three fresh 60-request/three-replica shared-capacity trials, three coordinated Redis-time window-transition trials, and Fixed→Sliding→Token→Sliding activation. Transition metadata/hash assertions proved one retained previous count set, current count equal to admitted race calls, and positive TTL. Cleanup removed all containers, the network, and the PostgreSQL volume.
- 2026-08-05 — Phase 6 resilience acceptance passed missed Pub/Sub event repair by reconciliation, non-regression, version-isolated state, partial-state continuity through gateway restart and replica removal/restoration, FAIL_OPEN forwarding with degraded metadata and no fabricated remaining value, and FAIL_CLOSED correlated 503 without backend delivery. Recovery resumed the original Redis state; cleanup again removed containers and volumes.
- 2026-08-05 — Added the durable distributed-algorithm architecture document and seeded approximation experiment report. Updated algorithm/system/policy architecture, project roadmap, README, command catalog, repository structure check, and CI-equivalent verification to include V3, the reviewed Lua resource, and both Phase 6 programs.

## Decision log

- 2026-08-05 — Preserve the wrapped `algorithm.configuration` contract selected for existing algorithms; add an explicit third discriminator branch.
- 2026-08-05 — Use exact integer cross-multiplication and ceiling/floor output rules; floating-point values and admission division are forbidden.
- 2026-08-05 — Use epoch-aligned Redis server-time windows and versioned namespaces; mid-window activation begins empty.
- 2026-08-05 — Treat an older Redis window as clock rollback and fail without mutation. Same-window earlier time remains conservative.
- 2026-08-05 — Use semantic absolute expiry and exact analytical retry timing. Dedicated ADRs will make these durable.
- 2026-08-05 — Extend only the three named algorithm branches; a generic plug-in framework is explicitly out of scope.

## Discoveries and surprises

- The expected uncommitted Phase 5 changes were absent; both initial and pre-implementation checks were clean.
- The baseline Gradle sandbox and first `jdeps` issues were environment/command construction problems, not product failures.
- A naive transition acceptance assertion expected exactly ten admissions immediately after a half-full previous window. Because previous weight decays continuously after the boundary, sufficiently elapsed concurrent calls may safely admit an eleventh request. The scenario now accepts only the analytically valid 10..11 range and proves the stored current count equals the observed allowed count.

## Risks and limitations

- Sliding Counter is deliberately an approximation; the observed generated-trace errors are experimental values, not a universal bound.
- Redis is one pinned node. Cluster, Sentinel, failover ambiguity, and cross-region time/consistency are outside this phase.
- Redis server clock rollback into an older epoch window fails availability conservatively rather than attempting to rewrite or regress state.
- JSON Schema enforces absolute integer bounds but cannot express `requestCost <= limit`; DTO/domain, compilation, PostgreSQL constraints, adapter validation, and Lua independently enforce the relationship.
- `RateLimit-Remaining` is whole immediately admissible cost, not a fractional estimate. `RateLimit-Reset` describes zero represented contribution and may extend beyond the next boundary.
- No new production dependency was added. No V1/V2 migration, previous algorithm behavior, coverage gate, or retained acceptance scenario was changed or removed.
- Scope deviations: none. Approved non-goals remain non-goals.

## Final outcome

Phase 6 is complete. The Admin API can create, return, match-test, version, and activate strict typed Sliding Window Counter policies. V3 preserves them in a dedicated typed child table while existing Fixed Window and Token Bucket rows upgrade unchanged. Pub/Sub and reconciliation distribute the third compiled algorithm into immutable snapshots; runtime selection remains outside HTTP handlers and PostgreSQL is absent from the proxy path.

The reviewed Lua resource uses Redis server time and one atomic hash transition for validation, rotation, weighted admission, conditional increment, persistence, and semantic absolute expiry. Exact cross-multiplication and analytical retry timing remain below the documented `2^53` bound. Keys isolate policy/version/algorithm/hashed identity and contain no raw identity. Strict tuple decoding and all Redis failure categories preserve existing FAIL_OPEN/FAIL_CLOSED behavior.

Completion evidence:

- gateway: 299 tests, zero failures/errors/skips; 1,000 generated arithmetic/retry properties; real Redis 7.4.2 state/TTL/malformed/NOSCRIPT/trace/concurrency coverage;
- gateway JaCoCo: 3,326/3,415 lines (97.39%), 1,048/1,152 branches (90.97%), 649/674 methods (96.29%), 15,242/15,685 instructions (97.18%);
- contracts: 17 passed; catalog 11, traffic simulator 1, orders 1, payments 1, portal 1; all supported non-gateway line/statement/branch/function coverage metrics 100%;
- empty V1→V3 and populated V2→V3 PostgreSQL migrations, typed repository round-trip, conflicting subtype rejection, constraints, and activated-child immutability passed;
- seeded 10,000-trace approximation experiment observed numerator overestimate 5,960 and underestimate 6,320 at `W=1,000`, plus deterministic Fixed Window boundary burst and both error signs;
- three repeated 60-request HAProxy trials shared one capacity across replicas; three coordinated Redis-time boundary trials proved one rotation and stored counts equal observed admissions;
- dynamic Fixed→Sliding→Token→Sliding activation, stale-event resistance, missed-event reconciliation, independent version namespaces, restart/scale state continuity, and both failure modes passed;
- `scripts/verify.sh` passed formatting, static analysis, all tests/coverage/contracts/builds, Dockerfile lint, Compose/container smoke, retained Phase 2–5 acceptance, both Phase 6 programs, and unconditional cleanup;
- JDK 21 `jdeps` reported only `java.base` and limiter self-package dependencies; shell parsing, repository/CI structure, scoped placeholder/skipped-test/forbidden-import scans, V1/V2 no-diff check, Compose config, `git diff --check`, and final empty Compose state passed.

Closeout commands executed:

```text
scripts/format.sh
scripts/static-checks.sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:compileJava :gateway:compileTestJava --rerun-tasks --no-daemon
scripts/test.sh
scripts/coverage.sh
scripts/validate-contracts.sh
scripts/build.sh
scripts/lint-dockerfiles.sh
ADMIN_BEARER_TOKEN=phase6-compose-check POSTGRES_PASSWORD=phase6-compose-check docker compose config --quiet
scripts/container-smoke.sh
scripts/phase6-sliding-window-counter-e2e.sh
scripts/phase6-sliding-window-counter-resilience-e2e.sh
scripts/verify.sh
/Users/duongtuanhoang/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home/bin/jdeps --recursive -verbose:class gateway/build/classes/java/main/lab/ratelimiter/gateway/domain/limiter
bash -n scripts/*.sh
scripts/check-repository-structure.sh
scripts/check-ci.sh
git diff --exit-code -- gateway/src/main/resources/db/migration/V1__policy_control_plane.sql gateway/src/main/resources/db/migration/V2__distributed_token_bucket.sql
git diff --check
ADMIN_BEARER_TOKEN=phase6-final-check POSTGRES_PASSWORD=phase6-final-check docker compose ps --all
```

Closeout non-product failures are retained: the first Docker acceptance attempt was sandbox-denied and passed after approval; the first formatting closeout found Java/Python formatter drift and passed after formatter application; the first raw Compose config lacked mandatory credentials and passed with explicit test values; an over-broad compilation command named nonexistent `compileTestFixturesJava` and the corrected production/test compilation command executed three real tasks; an initial broad hygiene expression found intentional test/check-script strings and the corrected scoped scan passed. The earlier RED, property-shrink, coverage, and wall-clock-test failures remain in the progress log above.

## Risks and limitations

- Sliding Window Counter is an approximation of an exact timestamp log; observed error will be documented, not generalized into an unproved universal bound.
- Real-time boundary tests can be flaky unless they prove the actual Redis windows returned; all such tests/scripts must use bounded coordination and repeated fresh identities.
- Redis TIME rollback beyond a stored window is an operational failure routed through configured failure mode; state is not regressed.
- Redis Cluster/Sentinel and cross-region clock behavior remain non-goals.

## Final outcome

In progress. This section will be replaced with demonstrated behavior, files, exact commands/results, coverage by executable, integration/concurrency/acceptance evidence, assumptions, deviations, and remaining limitations. Phase 6 will not be marked complete without repeated multi-replica and window-transition proof.
