# Phase 1 ExecPlan — Core Rate Limiter Algorithms

**Status:** Complete  
**Owner:** Codex, supervised by repository owner  
**Last updated:** 2026-07-24

## Purpose

Create a framework-independent Java reference layer for five rate-limiting
algorithms. A developer will be able to construct an in-memory limiter with an
immutable policy and injected clock, submit positive integral request costs,
inspect immutable state snapshots and deterministic decisions, and compare the
algorithms under boundary, concurrent, and generated request sequences.

This reference layer defines semantics that later Redis implementations can
share. It does not provide production distributed enforcement.

## Scope and non-scope

Included:

- shared immutable algorithm, request, policy, state, and decision contracts;
- thread-safe in-memory fixed-window counter;
- thread-safe in-memory sliding-window log;
- thread-safe in-memory weighted sliding-window counter;
- thread-safe in-memory token bucket;
- thread-safe in-memory leaky-bucket policing meter;
- injectable `java.time.Clock` and millisecond-precision fake-time tests;
- shared contract tests and algorithm-specific unit, boundary, concurrency, and
  property-based tests;
- behavior and tradeoff documentation;
- gateway formatting, Checkstyle, Java compilation, test, build, and JaCoCo line
  and branch gates.

Excluded:

- Redis clients, keys, Lua, TTLs, server time, and distributed atomicity;
- Spring components, WebFlux routes, HTTP response mapping, and proxying;
- PostgreSQL, policy persistence, policy parsing, and schema expansion;
- admin APIs or portal behavior;
- mock-service or traffic-simulator behavior;
- Docker or Compose changes and product-level end-to-end traffic;
- true leaky-bucket request queueing or delayed delivery.

## Current repository state

Inspection on 2026-07-24 found:

- Phase 0 is complete and its plan is under
  `docs/exec-plans/completed/PHASE-0-FOUNDATION.md`.
- The gateway contains only the Spring Boot entry point, Actuator configuration,
  and one random-port foundation test.
- No limiter domain package, algorithm code, algorithm tests, or algorithm
  behavior document exists.
- The gateway already uses Java 21, JUnit 5, AssertJ, Spotless, Checkstyle, and
  JaCoCo with independent 90% line and branch gates.
- The only JaCoCo exclusion is the logic-free Spring Boot entry point.
- The Phase 0 policy JSON Schema intentionally supports only the documented
  token-bucket example. Phase 1 does not parse policies and therefore does not
  expand that external schema.
- ADR 0010 requires leaky bucket to be a policing meter and forbids authoritative
  in-process request queueing.
- The worktree was clean before Phase 1 planning.

## Requirements review and semantic assumptions

No source document contradicts the requested Phase 1 scope. Redis is
authoritative in distributed mode, while the product specification explicitly
requires in-memory reference implementations for education and tests. The
following semantics were ambiguous and are fixed here before implementation.
They will also be documented in
`docs/architecture/ALGORITHM_SEMANTICS.md`.

1. **Time precision:** All algorithms observe `Clock.instant()` at millisecond
   precision. Sub-millisecond clock values are truncated to their epoch
   millisecond. Tests advance a mutable fake clock and never sleep.
2. **Clock rollback:** Each limiter clamps an observed time to at least the last
   state timestamp. A backward wall-clock jump therefore pauses expiry, refill,
   or leakage instead of restoring capacity twice or moving a window backward.
3. **Request costs:** Costs are positive whole units. All five algorithms support
   costs greater than one. A cost greater than the policy's limit or capacity is
   deterministically rejected and has no finite retry-after value under an
   unchanged policy.
4. **Rejected mutation:** Rejections never add usage, consume tokens, or add
   backlog. Time-derived cleanup, rotation, refill, or leakage observed before
   the rejection remains in the new immutable state.
5. **Window interval:** Sliding-log membership is `(now - window, now]`; an entry
   exactly one window old has expired. Fixed and sliding-counter windows are
   epoch-aligned half-open intervals `[start, start + window)`.
6. **Fixed-window reset:** At an exact boundary, the prior count is discarded
   before evaluating the request. Boundary bursts are an intentional limitation.
7. **Sliding-log entries:** One immutable log entry stores one accepted request's
   timestamp and cost, rather than duplicating the timestamp once per cost unit.
   `maximumEntries` bounds accepted request events, and validation requires it to
   be at least the configured limit because unit-cost requests are supported.
8. **Sliding-counter formula:** Within the current window, estimated usage is
   `current + previous * (window - elapsed) / window`. Decisions compare this
   rational value exactly using scaled integers. Reported remaining whole units
   use `floor(limit - estimate)`, making the approximation conservative for
   integral request costs.
9. **Token refill:** Token bucket refills continuously and proportionally at
   `refillTokens / refillPeriod`, evaluated at millisecond precision. It starts
   with the explicit `initialTokens`, caps at capacity, and uses exact scaled
   integer arithmetic rather than floating point.
10. **Leaky-bucket meaning:** The leaky bucket tracks backlog ("water") and drains
    continuously at `leakUnits / leakPeriod`, evaluated at millisecond precision.
    An allowed request immediately adds its cost. There is no request queue,
    scheduling, delayed response, or delivery ownership.
11. **Decision metadata:** `remaining` is a non-negative count of whole cost units
    that could be accepted immediately after the decision. Accepted decisions
    omit retry-after. Rejected decisions include the earliest millisecond retry
    when the unchanged state can accept that cost, except for permanently
    oversize costs. `resetAt` means full recovery: the next fixed boundary, all
    active log usage expired, weighted counter estimate zero, token bucket full,
    or leaky backlog empty.
12. **Identity and storage:** The in-memory object represents one already-selected
    limiter identity and policy version. Identity construction, hashing, caches,
    and multi-key storage are later phases.
13. **Concurrency:** Each in-memory limiter linearizes `decide` and `snapshot`
    operations for one instance. This proves local thread safety only, not
    horizontal or distributed correctness.
14. **Numeric domain:** Policy quantities and request costs are positive `long`
    values; policy versions are positive `long` values. Time-based scaled values
    use immutable `BigInteger` internally to avoid overflow and floating-point
    drift. Duration configuration must be positive and exactly representable in
    whole milliseconds.
15. **External policy schema:** Phase 1's Java policy records are an internal
    algorithm contract, not the external declarative policy schema. Expanding
    `contracts/policy.schema.json` without the Phase 4 parsing and semantic
    validation slice would create an unconsumed external contract, so it remains
    unchanged.

These are implementation-semantic decisions within the already-approved
architecture, so no new ADR is required. A future distributed implementation
must either preserve them in shared tests or create an ADR that deliberately
changes the contract.

## Proposed design

### Packages and dependencies

Production types live below `lab.ratelimiter.gateway.domain.limiter`. They import
only Java platform types. Spring, Reactor, Redis, database, HTTP, and Docker types
are prohibited.

The only new dependency is test-only
`net.jqwik:jqwik:1.9.3`, used for generated invariant sequences. Version 1.9.3
was selected from the official release notes and is compatible with the JUnit
Platform used by the pinned Spring Boot test stack. It adds no production
artifact dependency.

### Shared contract

`RateLimiter<P, S>` exposes:

- immutable policy `P`;
- immutable current state snapshot `S`;
- `decide(RateLimitRequest)` returning an immutable `RateLimitDecision`.

`RateLimitPolicy` and `RateLimitState` are sealed interfaces. Each algorithm has a
specific immutable policy and state record. `RateLimitRequest`, `RequestCost`,
`PolicyId`, `PolicyVersion`, and `RateLimitDecision` are immutable value records.
The decision identifies algorithm, policy ID/version, configured limit, allowed
status, remaining capacity, optional retry-after, and full-reset instant.

An internal synchronized base class owns one immutable state reference and an
injected clock. Algorithm transitions create replacement state objects; callers
cannot mutate snapshots.

### State transitions

- Fixed window stores epoch-aligned window start and used units.
- Sliding log stores a defensively copied ordered list of timestamp/cost entries
  and trims expired entries before every decision.
- Sliding counter stores previous and current counts plus current window start.
  It rotates once or clears both counts after two or more elapsed windows.
- Token bucket stores exact scaled token units and last observed time.
- Leaky bucket stores exact scaled backlog units and last observed time.

### Failure and validation behavior

Constructors reject invalid policy IDs, versions, limits, capacities, refill/leak
rates, initial token counts, log bounds, and non-millisecond durations with
`IllegalArgumentException`. `decide` rejects null requests and request models
reject non-positive costs. No algorithm silently clamps invalid configuration.

There is no external I/O or storage failure in this phase.

## Invariants

- Policy, request, state, log-entry, and decision values are immutable.
- Production domain code has no Spring, Reactor, Redis, PostgreSQL, HTTP, or
  Docker dependency.
- State time never moves backward.
- Remaining capacity is always in `[0, configured limit or capacity]`.
- Rejected request cost is never charged.
- Fixed and sliding usage never becomes negative.
- Token balance remains in `[0, capacity]`.
- Leaky backlog remains in `[0, capacity]`.
- Log entries are ordered, active, and bounded by `maximumEntries`.
- An exact boundary is evaluated according to the semantics above.
- Concurrent calls on one limiter cannot collectively exceed its local policy
  semantics.
- No test sleeps to advance time.

## Milestone 1 — Shared immutable contracts

Observable behavior: validated immutable models describe one request, policy,
state snapshot, and decision without framework dependencies.

Files expected to change:

- `gateway/build.gradle.kts`;
- `gateway/src/main/java/lab/ratelimiter/gateway/domain/limiter/**`;
- `gateway/src/test/java/lab/ratelimiter/gateway/domain/limiter/**`.

RED:

- Write model and interface tests first.
- Expected failure: the domain contract types do not compile because they are
  absent.

GREEN:

- Add the minimum sealed interfaces, value records, policy/state records, and
  shared limiter interface needed by the tests.

REFACTOR:

- Centralize validation and time arithmetic without exposing mutable collections.

Focused command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*DomainModelTest' --no-daemon`

Broader command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --no-daemon`

Success: immutable model validation and defensive-copy assertions pass.

## Milestone 2 — Fixed-window vertical slice

Observable behavior: fixed-window decisions enforce integral costs, rotate at
exact epoch-aligned boundaries, expose deterministic metadata, and remain
linearizable within one instance.

Files expected to change:

- fixed-window implementation and tests;
- shared contract-test fixture;
- this plan's evidence log.

RED:

- Add the fixed-window subclass of the shared contract suite plus boundary and
  concurrency tests.
- Expected failure: `InMemoryFixedWindowRateLimiter` is absent.

GREEN:

- Implement only fixed-window transition behavior.

REFACTOR:

- Extract shared synchronized state/clock mechanics required by later algorithms.

Focused command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*FixedWindow*' --no-daemon`

Broader command: gateway test suite.

Success: first, exact-limit, rejection, cost, rollback, boundary-burst, metadata,
and synchronized-caller assertions pass.

## Milestone 3 — Sliding-window log vertical slice

Observable behavior: exact event history enforces the trailing window, trims the
exact expiry boundary, supports costs, bounds entries, and computes retry/reset
metadata.

Files expected to change: sliding-log implementation and tests, shared contract
subclass, plan evidence.

RED: add contract, boundary, cleanup, entry-bound, cost, concurrency, and
generated-sequence tests before implementation.

Expected failure: `InMemorySlidingWindowLogRateLimiter` is absent.

GREEN: implement the minimum trim/sum/append transition.

REFACTOR: isolate ordered-entry and retry calculations.

Focused command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*SlidingWindowLog*' --no-daemon`

Broader command: gateway test suite.

Success: exact sliding behavior and invariants pass without sleeps.

## Milestone 4 — Sliding-window counter vertical slice

Observable behavior: the weighted approximation rotates correctly, handles exact
boundaries and skipped windows, supports costs, and reports conservative
whole-unit capacity.

Files expected to change: sliding-counter implementation and tests, shared
contract subclass, plan evidence.

RED: add contract, formula-table, rotation, cost, retry, concurrency, and
generated-sequence tests before implementation.

Expected failure: `InMemorySlidingWindowCounterRateLimiter` is absent.

GREEN: implement exact scaled weighted-count arithmetic.

REFACTOR: centralize rational arithmetic and future-time calculation.

Focused command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*SlidingWindowCounter*' --no-daemon`

Broader command: gateway test suite.

Success: the documented approximation and all invariants pass.

## Milestone 5 — Token-bucket vertical slice

Observable behavior: a configurable initial balance refills continuously,
supports multi-token costs, caps at capacity, and computes exact millisecond
retry/full-reset metadata.

Files expected to change: token-bucket implementation and tests, shared contract
subclass, plan evidence.

RED: add contract, initial-balance, partial-refill, cap, cost, rollback,
concurrency, and generated-sequence tests before implementation.

Expected failure: `InMemoryTokenBucketRateLimiter` is absent.

GREEN: implement exact scaled refill and consumption.

REFACTOR: reuse overflow-safe scaled arithmetic.

Focused command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*TokenBucket*' --no-daemon`

Broader command: gateway test suite.

Success: balance invariants and retry/reset calculations pass.

## Milestone 6 — Leaky-bucket meter vertical slice

Observable behavior: backlog drains at a stable configured rate, admissions add
cost immediately, full buckets reject without queueing, and metadata identifies
space/full-drain timing.

Files expected to change: leaky-bucket implementation and tests, shared contract
subclass, plan evidence.

RED: add contract, drain, full, cost, rollback, no-queue, concurrency, and
generated-sequence tests before implementation.

Expected failure: `InMemoryLeakyBucketRateLimiter` is absent.

GREEN: implement exact scaled drainage and policing.

REFACTOR: share continuous-rate arithmetic with token bucket where names remain
algorithm-specific.

Focused command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*LeakyBucket*' --no-daemon`

Broader command: gateway test suite.

Success: backlog invariants pass and no queueing API or state exists.

## Milestone 7 — Cross-algorithm properties, documentation, and gates

Observable behavior: every implementation satisfies the common contract,
generated sequences preserve invariants, behavior is documented, and every
gateway quality gate passes.

Files expected to change:

- shared contract and property tests;
- `docs/architecture/ALGORITHM_SEMANTICS.md`;
- `docs/COMMANDS.md` if verified focused commands add durable value;
- this plan.

RED:

- Run the new cross-algorithm property and shared-contract suites as each
  implementation is introduced; absent or incorrect implementations fail their
  invariant.

GREEN:

- Complete only missing algorithm behavior needed by the contract.

REFACTOR:

- remove duplication, format, and keep public names aligned with the semantics
  document.

Focused commands: shared contract tests and property tests.

Broader commands:

- `scripts/format.sh`
- `scripts/static-checks.sh`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:compileJava :gateway:compileTestJava --no-daemon`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --no-daemon`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:jacocoTestReport :gateway:jacocoTestCoverageVerification --no-daemon`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:build --no-daemon`

Success: formatting, Checkstyle, compilation, all gateway tests, JaCoCo line and
branch thresholds, and gateway build return zero.

## Contract and schema changes

Phase 1 creates an internal Java algorithm contract and shared executable
contract tests. It does not change JSON Schema, OpenAPI, HTTP, Redis result,
event, metric, or traffic-scenario contracts.

The Java contract will be the reference for compatible Redis transition/result
contracts in Phases 2 and 3. That future external Redis result shape is not
invented in this phase.

## Data and migration considerations

No PostgreSQL table, Redis key, persisted state, or migration is added. All state
is process-local and discarded with the limiter instance. Policy versions are
metadata only in this layer and do not trigger storage migration.

## Security and failure analysis

- No identity or secret enters the algorithm API.
- No external state or network access occurs.
- Positive numeric and duration validation rejects malformed policies early.
- Immutable snapshots prevent callers from mutating live state.
- Synchronized transitions avoid local lost updates.
- Sliding-log entries are bounded by validated policy configuration.
- In-memory state must never be used as a silent fallback from Redis in later
  phases.
- Local synchronization is not evidence of distributed atomicity.

## Validation plan

Run and inspect:

- each milestone's focused RED and GREEN commands;
- the gateway affected suite after every slice;
- Spotless and Checkstyle;
- explicit main/test compilation;
- shared contracts and jqwik properties;
- JaCoCo report and verification with at least 90% line and branch coverage;
- gateway build;
- `git diff --check` and prohibited-marker/skip search.

Containers, Redis/PostgreSQL integration, root multi-codebase verification, and
end-to-end tests are not applicable because Phase 1 changes only a pure gateway
domain layer and a test-only dependency. The final gateway build still proves the
existing Spring application packages the new domain classes.

## Progress log

- 2026-07-24 — Read the root and gateway `AGENTS.md`, both required repository
  skills, Phase 0 completed plan, `docs/PLANS.md`, `docs/COMMANDS.md`, product
  specification, system architecture, policy model, repository layout,
  engineering standards, test strategy, definition of done, and all accepted
  ADRs.
- 2026-07-24 — Inspected the clean worktree, gateway source/build/check
  configuration, current contracts, and active/completed plan directories.
- 2026-07-24 — Completed the ambiguity review above. No blocking contradiction
  was found. Deferred policy-schema expansion and true leaky-bucket queueing.
- 2026-07-24 — Verified jqwik 1.9.3 availability and JUnit Platform lineage from
  the official jqwik release documentation. Recorded it as a pinned test-only
  dependency.
- 2026-07-24 — Created this active plan before production implementation.
- 2026-07-24 — Milestone 1 RED: `:gateway:test --tests
  '*DomainModelTest'` reached test compilation and failed with 35 expected
  `cannot find symbol` errors because the shared model types did not exist. The
  first sandboxed invocation failed earlier on the Gradle user-cache lock and was
  rerun with approved cache access; that environmental failure is not counted as
  RED.
- 2026-07-24 — Milestone 1 GREEN: added the sealed policy/state contracts,
  validated immutable records, decision/request value types, and generic limiter
  interface. The focused command passed with six tests.
- 2026-07-24 — Milestone 2 RED: the fixed-window focused command failed at test
  compilation with nine expected missing-implementation errors.
- 2026-07-24 — Milestone 2 GREEN/REFACTOR: implemented epoch-aligned fixed-window
  transitions and extracted synchronized immutable-state and clock utilities.
  Ten focused inherited-contract/boundary tests passed, followed by the full
  gateway suite.
- 2026-07-24 — Milestone 3 RED: the sliding-log focused command failed at test
  compilation with eight expected missing-implementation errors.
- 2026-07-24 — Milestone 3 GREEN/REFACTOR: implemented exact trailing-window
  trimming, cost-bearing entries, request-specific retry, and full-reset
  metadata. Eleven focused inherited-contract/algorithm tests passed, followed
  by the full gateway suite.
- 2026-07-24 — Milestone 4 RED: the sliding-counter focused command failed at
  test compilation with nine expected missing-implementation errors.
- 2026-07-24 — Milestone 4 GREEN/REFACTOR: implemented exact scaled weighting,
  one/two-window rotation, conservative remaining units, and future retry/reset
  calculation. Twelve focused inherited-contract/algorithm tests passed,
  followed by the full gateway suite.
- 2026-07-24 — Milestone 5 RED: the token-bucket focused command failed at test
  compilation with nine expected missing-implementation errors.
- 2026-07-24 — Milestone 5 GREEN/REFACTOR: implemented configurable initial
  tokens, continuous capped refill, exact scaled cost consumption, and separate
  retry/full-refill metadata. Twelve focused inherited-contract/algorithm tests
  passed, followed by the full gateway suite.
- 2026-07-24 — Milestone 6 RED: the leaky-bucket focused command failed at test
  compilation with eight expected missing-implementation errors.
- 2026-07-24 — Milestone 6 GREEN/REFACTOR: implemented continuously draining
  backlog policing, immediate cost admission, request-specific space retry, and
  empty reset without any queue state. Twelve focused inherited-contract/
  algorithm tests passed, followed by the full gateway suite.
- 2026-07-24 — Milestone 7 property RED: five deterministic properties were
  written before the test dependency was wired. Test compilation failed with 24
  expected missing `net.jqwik` symbol/package errors.
- 2026-07-24 — Milestone 7 property GREEN: added pinned test-only jqwik 1.9.3.
  Five properties, each with 200 generated sequences and a fixed seed, passed;
  the full gateway suite then passed.
- 2026-07-24 — Preliminary JaCoCo verification passed with 366/371 lines
  (98.65%) and 87/96 branches (90.63%). Final quality-gate results remain to be
  recorded after formatting, static analysis, documentation, and clean build.
- 2026-07-24 — Added `ALGORITHM_SEMANTICS.md` with shared time/decision
  semantics and algorithm behavior, formula, complexity, burst, and limitation
  sections, and linked it from the documentation index.
- 2026-07-24 — The first formatting gate failed on expected canonical-format
  differences in 17 new Java files. Applied the configured Spotless formatter;
  the repeated root formatting gate passed.
- 2026-07-24 — Root static analysis passed: gateway main/test Checkstyle,
  Python Ruff and strict mypy, portal ESLint, and strict TypeScript all returned
  zero.
- 2026-07-24 — Explicit Java main/test compilation, the final 69-test gateway
  suite, final JaCoCo report/verification, and gateway build all returned zero.
  Final coverage remained 366/371 lines (98.65%), 87/96 branches (90.63%), and
  67/67 methods (100%).
- 2026-07-24 — JDK `jdeps` proved the production limiter package references
  only `java.base` packages and itself. Final source scans found no Spring,
  Reactor, Redis, PostgreSQL, HTTP, or Docker import in the domain package, and
  no skipped tests or unexplained TODO/FIXME markers.
- 2026-07-24 — Added `.jqwik-database` to `.gitignore` because jqwik generates
  this local execution database. No generated database is part of the change.
- 2026-07-24 — Completed Phase 1 and moved this plan from `active/` to
  `completed/` after all applicable quality gates passed.

## Decision log

- 2026-07-24 — Use one limiter instance per selected identity/policy state. This
  keeps identity/storage concerns outside the pure algorithm boundary.
- 2026-07-24 — Use synchronized local transitions over immutable snapshots. An
  atomic reference retry loop was considered, but synchronized code is easier to
  reason about and does not imply Redis semantics.
- 2026-07-24 — Use exact `BigInteger` scaled arithmetic for continuous rates and
  weighted estimates. Floating point was rejected because retry boundaries and
  later Redis parity require deterministic arithmetic.
- 2026-07-24 — Treat reset as full recovery and retry-after as request-specific
  earliest admission. A single overloaded timestamp was rejected because those
  meanings differ for token, leaky, sliding-log, and sliding-counter state.
- 2026-07-24 — Keep external policy JSON unchanged. Java algorithm records are
  not yet parsed from the policy schema, and an unconsumed external expansion
  would violate the narrow vertical slice.
- 2026-07-24 — No new ADR is needed because these decisions refine the
  already-approved algorithm and in-memory reference design. Future divergence
  must be explicit.

## Discoveries and surprises

- Phase 0 intentionally left all non-token-bucket external algorithm fields
  unspecified.
- JaCoCo already enforces the required 90% line and branch thresholds for all
  gateway production logic except the documented logic-free application entry
  point.

## Risks and limitations

- Millisecond precision can admit or reject differently from a future
  microsecond Redis implementation unless the Redis contract deliberately uses
  the same precision.
- Sliding-window counter is an approximation and can over- or under-estimate an
  exact log near boundaries; this is inherent and will be quantified in the
  behavior document.
- Sliding-window log has O(n) immutable-copy and cleanup work per decision in
  this educational implementation.
- Synchronization provides one-process correctness only. Phase 2/3 must prove
  Redis atomicity with independent clients and replicas.
- `BigInteger` arithmetic is a reference-model choice; Redis Lua will require a
  bounded scaled-integer representation and compatibility tests.
- Policy numeric upper bounds remain a control-plane decision. This layer
  validates positivity, duration precision, and internal consistency but does
  not invent product maxima.

## Command and evidence log

Commands ran from the repository root. `JAVA_HOME` resolved the installed Java
21 JDK through `/usr/libexec/java_home -v 21`.

1. Read/audit commands using `rg`, `wc`, `sed`, `find`, `git status`, and
   `git diff` covered the root and gateway instructions, both skills, completed
   Phase 0 plan, project/architecture/policy/quality documents, every ADR,
   gateway source/build configuration, contracts, and scripts.
2. `./gradlew :gateway:test --tests '*DomainModelTest' --no-daemon`
   - Initial sandboxed invocation failed before Gradle on a denied user-cache
     lock and was not counted as RED.
   - Approved rerun exited 1 at test compilation with 35 missing-symbol errors,
     the expected Milestone 1 RED.
   - Post-implementation rerun exited 0 with 6 tests.
3. `./gradlew :gateway:test --tests '*FixedWindow*' --no-daemon`
   - RED exited 1 with 9 missing-implementation errors.
   - GREEN exited 0 with 10 tests.
4. `./gradlew :gateway:test --tests '*SlidingWindowLog*' --no-daemon`
   - RED exited 1 with 8 missing-implementation errors.
   - GREEN exited 0 with 11 tests.
5. `./gradlew :gateway:test --tests '*SlidingWindowCounter*' --no-daemon`
   - RED exited 1 with 9 missing-implementation errors.
   - GREEN exited 0 with 12 tests.
6. `./gradlew :gateway:test --tests '*TokenBucket*' --no-daemon`
   - RED exited 1 with 9 missing-implementation errors.
   - GREEN exited 0 with 12 tests.
7. `./gradlew :gateway:test --tests '*LeakyBucket*' --no-daemon`
   - RED exited 1 with 8 missing-implementation errors.
   - GREEN exited 0 with 12 tests.
8. `./gradlew :gateway:test --tests '*RateLimiterProperties' --no-daemon`
   - RED exited 1 at test compilation with 24 missing jqwik package/symbol
     errors before the test-only dependency was wired.
   - GREEN downloaded pinned jqwik 1.9.3 and exited 0 with five properties,
     each executing 200 generated sequences with a fixed seed.
9. `./gradlew :gateway:test --no-daemon`
   - Ran after every algorithm slice and again after formatting.
   - Final run exited 0 with 69 tests, 0 failures, 0 errors, and 0 skipped.
10. `./gradlew :gateway:jacocoTestReport
    :gateway:jacocoTestCoverageVerification --no-daemon`
    - Preliminary and final runs exited 0.
    - Final XML counters: 366/371 lines (98.65%), 87/96 branches (90.63%),
      and 67/67 methods (100%).
11. `scripts/format.sh`
    - Initial run exited 1 and identified canonical formatting differences in
      new Java sources.
    - `./gradlew :gateway:spotlessApply --no-daemon` exited 0.
    - Repeated `scripts/format.sh` exited 0 for Java/Kotlin Gradle, Python, and
      portal formatting checks.
12. `scripts/static-checks.sh`
    - Exited 0 for gateway Checkstyle, Python Ruff/mypy, and portal
      ESLint/TypeScript.
13. `./gradlew :gateway:compileJava :gateway:compileTestJava --no-daemon`
    - Exited 0.
14. `./gradlew :gateway:build --no-daemon`
    - Exited 0; compilation, tests, Checkstyle, Spotless, JaCoCo verification,
      JAR, and Boot JAR tasks succeeded.
15. First attempted `jdeps` command expanded an unset same-command shell
    variable to `/bin/jdeps` and exited 127; corrected command
    `$(/usr/libexec/java_home -v 21)/bin/jdeps -recursive -verbose:package
    gateway/build/classes/java/main/lab/ratelimiter/gateway/domain/limiter`
    exited 0 and reported only `java.base` plus self-package dependencies.
16. Final `git status --short --untracked-files=all`, `git diff --check`,
    prohibited-marker scan, and forbidden-domain-import scan found no whitespace
    error, skipped test, unexplained placeholder, or forbidden dependency.

## Final outcome

Phase 1 is complete. The gateway now contains a pure, thread-safe in-memory
reference implementation for all five required algorithms, shared immutable
contracts, five inherited contract suites, focused boundary tests, concurrent
caller proofs, and deterministic property tests.

The implementation remains deliberately outside Spring, Redis, PostgreSQL,
HTTP, Docker, policy parsing, routing, and queue delivery. The exact time,
cost, retry, reset, burst, and approximation semantics are recorded in
`docs/architecture/ALGORITHM_SEMANTICS.md` for later Redis parity.

All applicable Phase 1 gates passed: formatting, static analysis, explicit
compilation, 69 gateway tests, 98.65% line coverage, 90.63% branch coverage,
100% method coverage, framework-dependency inspection, and gateway artifact
build. No container or storage integration was run because this phase changes
no container, Redis, PostgreSQL, HTTP, or end-to-end boundary.

There was no deviation from the approved system design. The external policy
schema remains intentionally unchanged; true leaky-bucket queueing remains
deferred by ADR 0010.
