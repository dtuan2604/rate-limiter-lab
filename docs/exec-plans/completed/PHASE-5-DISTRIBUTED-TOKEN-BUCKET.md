# Phase 5 — Distributed Token Bucket

Status: Completed  
Owner: Codex  
Last updated: 2026-08-04
Completed: 2026-08-04

## Purpose

Add Token Bucket as the second fully integrated distributed rate-limiting algorithm. An administrator can create and activate a typed `TOKEN_BUCKET` policy; PostgreSQL stores its immutable versioned definition; Pub/Sub and reconciliation distribute it; every gateway installs a complete immutable snapshot; and an atomic Redis operation enforces one shared bucket across gateway replicas. Fixed Window remains supported without contract or behavior regressions.

This plan is the durable work record. It must contain the observed RED failure, minimum GREEN change, focused verification, refactor, broader verification, coverage, and acceptance evidence for every slice before completion.

## Scope and non-scope

Included:

- acceptance-only pause/resume control hardening and the Phase 3 unhealthy-removal timing repair;
- a strict typed `FIXED_WINDOW | TOKEN_BUCKET` policy union in API, domain, persistence, and runtime snapshots;
- one forward-only V2 Flyway migration and V1-to-V2 upgrade coverage;
- bounded millitoken arithmetic, a Redis `TIME`-authoritative Lua operation, versioned keys, safe TTL and missing-state reconstruction;
- typed runtime dispatch, Token Bucket HTTP metadata, failure behavior, observability fields, propagation, concurrency, restart, and acceptance proof;
- ADRs and compatibility documentation.

Excluded: every algorithm other than Fixed Window and Token Bucket in the distributed path; client-controlled timestamps or costs; floating-point policy values; queuing; UI expansion; metrics dashboards; OAuth; Kubernetes; Redis Cluster/Sentinel; cross-region enforcement; unrelated services.

## Starting repository state

- Java 21/Spring WebFlux gateway includes Phase 1 in-memory algorithms and a Phase 3 Redis Fixed Window adapter.
- Phase 4 stores immutable versioned Fixed Window policies in PostgreSQL V1, publishes invalidations through Redis Pub/Sub/outbox, reconciles missed events, and atomically replaces complete policy snapshots.
- External policy configuration is currently Fixed Window-only. `PolicyDefinition`, repository conversion, `CompiledPolicy`, and runtime enforcement therefore contain fixed-only types.
- `/internal/policy-snapshot` and pause/resume routes are registered together. Authentication protects `/internal/**`, and the enable flag defaults off, but the pause/resume routes can be enabled in any Spring profile. This violates the acceptance-only boundary.
- `scripts/phase3-e2e.sh` stops a replica with the default approximately ten-second timeout inside a ten-second Fixed Window. The stop can cross the window boundary and admit request six. This is a harness defect.
- Independently verified baseline before edits: 214 gateway tests with zero failures/errors/skips; 4 PostgreSQL migration tests; 7 Redis Fixed Window tests including 20 repeated 100-request/three-client concurrency trials; coverage 98.08% lines, 92.27% branches, 96.17% methods; formatting, static analysis, compilation, contracts, builds, and `jdeps` passed; retained Phase 2, Phase 3 Redis-failure, Phase 4 dynamic-policy, and Phase 4 publication-failure scenarios passed and cleaned up.

## Proposed design

The external `algorithm` object remains wrapped and discriminated. Fixed Window retains its exact JSON shape. Token Bucket uses:

```yaml
algorithm:
  type: TOKEN_BUCKET
  configuration:
    capacity: 10
    initialTokens: 10
    refillTokens: 2
    refillPeriod: 1s
    requestCost: 1
```

A sealed typed union crosses API, domain, repository, and snapshot compilation boundaries. Snapshot compilation maps Fixed Window to its existing adapter and Token Bucket to a distinct adapter; HTTP handlers do not switch on strings. Candidate compilation is all-or-nothing.

V2 expands the policy discriminator, introduces a normalized Token Bucket subtype table, and uses database constraints plus deferrable discriminator foreign keys so one version cannot contain a conflicting subtype. Activated children remain immutable. First activation time is written by PostgreSQL `CURRENT_TIMESTAMP` and is the reconstruction anchor.

Redis uses 1,000 millitokens per token. Integer policy fields are scaled in Java after validation. The Lua resource calls Redis `TIME`, atomically validates/reconstructs/refills/decides/persists/expires, and returns a strictly decoded versioned tuple. Refill carries a persisted division remainder. Keys include policy ID, version, algorithm, and SHA-256 identity hash; raw identities never appear.

Missing state reconstructs from activation time and initial balance. TTL is never shorter than bounded time-to-full (including tolerated future-anchor/rollback delay), so expiry cannot reset an old full bucket to a smaller initial balance.

## Invariants

- Every shared Token Bucket decision is one atomic Redis operation.
- Allowed admissions never exceed the balance made available by initial tokens and bounded refill, regardless of gateway count.
- Rejections do not consume tokens and never reach the backend.
- Balance remains in `[0, capacity]`; malformed/unsafe state fails safely.
- Gateway clocks and client timestamps are never authoritative in production.
- Fixed Window and Token Bucket, identities, policy versions, and policies never collide in Redis.
- A failed candidate policy reload leaves the prior complete snapshot installed.
- FAIL_OPEN forwards with degraded metadata and no fabricated remaining balance; FAIL_CLOSED returns correlated 503 and does not forward.
- Activated policy definitions are immutable. Algorithm conversion requires a new version.
- The Phase 1 domain package remains dependent only on Java base modules and itself.

## Milestones

### 1. Baseline security and acceptance repair

Expected files: internal route/handler/configuration tests and production wiring, compose configuration, Phase 3 and Phase 4 acceptance scripts.

- RED: full WebFlux route/profile/property/authentication tests show pause/resume can be registered outside an acceptance profile; retained Phase 3 run shows request six can enter a new window.
- GREEN: snapshot remains normally available/authenticated; a profile- and property-guarded configuration registers pause/resume only for `acceptance` plus explicit enablement; Phase 4 missed-event run supplies both. Phase 3 uses a bounded stop and verifies it remains in the intended Redis window.
- Refactor boundary: keep acceptance controls separate from ordinary internal snapshot visibility.
- Focused verification: targeted route/authentication tests; repeated `scripts/phase3-e2e.sh`.
- Broader verification: all retained Phase 2–4 scenarios and `scripts/verify.sh`.
- Success: no normal/dev/prod route exposure, 401 before handlers for bad credentials, and stable removal proof without boundary crossing.

### 2. Contracts, ADRs, and typed domain

Expected files: OpenAPI/JSON Schemas/examples, control-plane DTO/domain types, validation tests, policy/compiler tests, ADRs 0023 onward and architecture documentation.

- RED: strict valid/invalid union contract tests and typed bounds/duration validation fail against fixed-only types.
- GREEN: sealed typed union, exact duration literal, fixed bounds, strict serialization/deserialization, and matching without state mutation.
- Refactor boundary: no generic maps and no HTTP string dispatch.
- Focused verification: policy validation/contract/schema suites.
- Broader verification: gateway tests and contract validation.
- Success: both algorithms round-trip; unknown/cross-algorithm/missing/decimal/unknown fields fail.

### 3. V2 migration and persistence

Expected files: `V2__distributed_token_bucket.sql`, migration/repository/lifecycle tests, typed repository conversion.

- RED: empty migration, V1 fixture upgrade, Token Bucket round-trip/activation/conflict/immutability, and Fixed Window compatibility tests.
- GREEN: V2 normalized subtype constraints and typed transactional repository writes/reads.
- Refactor boundary: exactly one matching subtype is required; activation timestamps come from the database.
- Focused verification: migration and repository suites with PostgreSQL 17.6 Testcontainers.
- Broader verification: all policy lifecycle/propagation tests.
- Success: V1 data upgrades intact; Fixed→Token→Fixed works through distinct versions only.

### 4. Pure arithmetic and shared traces

Expected files: bounded arithmetic model, BigInteger reference/property tests, compatibility traces and documentation.

- RED: focused boundary/remainder/cost/rollback/overflow tests and jqwik generated traces.
- GREEN: quotient/remainder millitoken arithmetic with saturation and conservative floor rounding.
- Refactor boundary: production math is Redis-compatible; high-precision code stays test/reference-only.
- Focused verification: arithmetic and property suites.
- Broader verification: shared Phase 1/model traces.
- Success: generated sequences agree within documented precision and every unsafe configuration is rejected.

### 5. Atomic Redis adapter

Expected files: Redis key, script resource, decoder, adapter/configuration, real-Redis tests.

- RED: Redis 7.4.2 tests for initialization, refill, cost, isolation, TTL/reconstruction, malformed values/tuple, server time, NOSCRIPT, traces, and repeated concurrency.
- GREEN: reviewed Lua plus strict Java invocation/decoding and safe EVALSHA/EVAL recovery only for NOSCRIPT.
- Refactor boundary: timeout/connection/ambiguous mutations are never retried; test timestamps have no production route.
- Focused verification: Redis integration, Lua trace, and repeated multi-client suites.
- Broader verification: state and application suites.
- Success: atomic invariants and safe TTL hold in repeated trials.

### 6. Runtime, HTTP, failures, propagation, and observability

Expected files: compiled algorithms/snapshot compiler, service and HTTP mappings, logs, propagation/reconciliation/contract tests.

- RED: typed dispatch, mixed snapshots, failed compilation preservation, headers, 429/503, no-backend, failure recovery, propagation and reconciliation tests.
- GREEN: sealed pattern dispatch and Token Bucket-specific results/headers/log fields while preserving Fixed Window.
- Refactor boundary: meaningful algorithm semantics remain distinct.
- Focused verification: application/HTTP/propagation/reconciliation suites.
- Broader verification: gateway build and coverage.
- Success: dynamic Fixed↔Token activation converges without restart; stale/missed/invalid events cannot corrupt snapshots.

### 7. Multi-replica acceptance and completion

Expected files: Phase 5 behavior and resilience acceptance scripts, docs, this plan.

- RED: acceptance scenarios initially fail before integrated wiring.
- GREEN: scenarios 1–8 use Redis time, response metadata, barriers, bounded polling, reliable cleanup, and exact backend counts.
- Refactor boundary: shared shell helpers only where semantics remain visible.
- Focused verification: both Phase 5 scripts and repeated concurrency runs.
- Broader verification: every listed quality gate and retained acceptance scenario.
- Success: repeated HAProxy trials prove replica count multiplies neither capacity nor refill; all executable codebases remain above 90% coverage.

## Contract and schema changes

- OpenAPI/JSON Schema policy algorithm becomes a strict discriminated union while retaining the Fixed Window branch unchanged.
- Token Bucket period is a positive integer plus `ms|s|m|h|d`; amount and unit persist separately for exact literal round-trip.
- V2 adds Token Bucket configuration and discriminator constraints.
- Redis key: `ratelimit:{p=<base64url-policy-id>:v=<version>:a=token-bucket:i=<sha256>}`.
- Redis hash fields exactly: `tokens`, `last_ms`, `refill_remainder`.
- Lua result is a versioned strict tuple containing outcome, scaled policy/balance/cost/refill values, period, retry/reset, Redis time, TTL, remainder, and reconstruction flag.
- Token Bucket headers: Limit is whole-token capacity; Remaining is floor whole-token balance; Reset is ceiling seconds until full; Retry-After is ceiling seconds until the configured rejected cost is affordable; JSON retry milliseconds remains exact.

## Data and migration considerations

V1 is immutable and must not be edited. V2 is forward-only. Empty-database tests run V1+V2; an upgrade test first migrates to V1, inserts valid Phase 4 data, then migrates to V2 and proves Fixed Window read/activation compatibility. Deferrable discriminator foreign keys allow transactional subtype replacement for drafts while rejecting conflicting committed state. Redis policy versions never share state. No rollback migration is supplied; constraint/migration errors must be explicit and transactional.

## Security and failure analysis

Acceptance event controls require an `acceptance` Spring profile, explicit enable flag, and admin bearer authentication. The snapshot endpoint stays authenticated. Policy input is strict and bounded before persistence/activation/script invocation. Raw identities are hashed in Redis keys and omitted from logs. Lua validates canonical integers and state shape before mutation. At most five minutes of Redis rollback or future activation-anchor skew is tolerated; larger inconsistency fails safely. FAIL_OPEN and FAIL_CLOSED retain established behavior without local fallback.

## Numeric bounds

- Scale: 1 token = 1,000 millitokens.
- `capacity`, `refillTokens`, `requestCost`: 1..100,000 tokens.
- `initialTokens`: 0..capacity; request cost <= capacity.
- Refill period: 1..86,400,000 milliseconds.
- Maximum empty-to-full interval: 30 days.
- Maximum tolerated rollback/future-anchor skew: 300,000 milliseconds.
- Largest bounded product: 100,000,000 × 86,400,000 = 8.64e15, below `2^53-1`.
- Elapsed time beyond bounded time-to-full saturates before multiplication. Newly credited millitokens round down.

## Validation plan

Run and record every command requested in the approved plan, including repository/CI checks, formatting, static analysis, forced compilation/tests/coverage, aggregate test/coverage/contract/build scripts, compose validation, Dockerfile lint/container smoke, all retained Phase 2–4 scenarios, both Phase 5 scenarios, and `scripts/verify.sh`. Also record focused migration-upgrade, arithmetic property, Redis integration/trace/concurrency, Pub/Sub/reconciliation, and HTTP suites; `jdeps`; `git diff --check`; shell syntax; placeholder/skipped-test/forbidden-import scans; and final container/volume cleanup.

## Progress log

- 2026-08-04 — Plan activated before Phase 5 feature changes.
- 2026-08-04 — Baseline GREEN evidence independently verified: 214 gateway tests; 4 migration tests; 7 Fixed Window Redis tests with 20 repeated concurrency trials; 98.08% line, 92.27% branch, 96.17% method coverage; formatting/static/compilation/contracts/build/`jdeps`; retained Phase 2, Phase 3 Redis-failure, Phase 4, and Phase 4 publication-failure scenarios.
- 2026-08-04 — Milestone 1 RED evidence: `scripts/phase3-e2e.sh` reproducibly admitted request six after replica stop crossed the ten-second window boundary. Inspection also proved pause/resume route registration was profile-agnostic and could be enabled in normal runtime with only a property, although authentication and default-off behavior existed.
- 2026-08-04 — Milestone 1 route RED command: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*PolicySnapshotEndpointTest' --tests '*AcceptancePolicyEventControlConfigurationTest' --tests '*AdminAuthenticationTest' --no-daemon`. Expected failure observed at test compilation: the acceptance-only handler/configuration and snapshot-only constructor did not exist.
- 2026-08-04 — Milestone 1 minimum GREEN: split the snapshot router/handler from a dedicated acceptance event-control router/handler; register the latter only under Spring profile `acceptance` and both explicit policy-control properties; retain the global `/internal/**` bearer filter. Compose now passes the Spring profile and Phase 4 explicitly selects `acceptance`.
- 2026-08-04 — Milestone 1 focused GREEN: the same focused Gradle command passed 10 tests. Normal, development, and production profiles did not create control beans even with the property; acceptance without the property did not create them; acceptance plus the property created working routes; bad bearer credentials returned 401 before invocation.
- 2026-08-04 — Milestone 1 harness RED after the first repair: a one-second container stop still allowed HAProxy's stale-backend retry to cross the window. The new explicit Redis-window assertion failed with `expected 178587338, observed 178587339`, proving the harness now failed safely instead of silently accepting a new window.
- 2026-08-04 — Milestone 1 harness GREEN/refactor: bounded HAProxy-removal polling now completes before obtaining a fresh Redis window and resetting evidence. The five-allowed/one-rejected proof then asserts the same Redis window before and after enforcement. `scripts/phase3-e2e.sh` passed twice consecutively, and passed a third time inside the complete verification run.
- 2026-08-04 — Milestone 1 affected suite GREEN: forced Spotless, Checkstyle, all gateway tests, JaCoCo report, and 90% line/branch/method verification passed. `scripts/phase4-e2e.sh` passed with explicit acceptance profile and cleaned up.
- 2026-08-04 — Milestone 1 broad GREEN: `scripts/verify.sh` completed successfully. It ran repository/CI checks, formatting, Java/Python/portal static checks and tests, coverage, contracts, builds, compose/Docker lint and smoke, Phase 2, Phase 3 shared/restart/scale/removal, Phase 3 Redis FAIL_OPEN/FAIL_CLOSED, Phase 4 dynamic/missed-event, and Phase 4 publication-failure scenarios. Every Compose run removed its network and PostgreSQL volume.
- 2026-08-04 — Milestone 2 contract RED: `conda run -n rate-limiter pytest contracts/tests/test_contracts.py -q` produced 1 expected failure because the valid Token Bucket example violated the Fixed Window-only schema at the discriminator/configuration.
- 2026-08-04 — Milestone 2 contract GREEN: policy JSON Schema now uses a closed conditional union and OpenAPI uses `oneOf` plus an explicit discriminator. The focused command passed 15 tests, including valid Fixed Window compatibility and rejection of unknown, missing, cross-algorithm, decimal, and unknown configuration fields.
- 2026-08-04 — Milestone 2 Java RED: focused `TokenBucketPolicyDefinitionTest` and `AdminPolicyApiTest` compilation failed on the intentionally absent sealed definition union, exact refill-period type, and Token Bucket DTO/domain records.
- 2026-08-04 — Milestone 2 Java GREEN/refactor: added the two-member sealed definition union, canonical integer-plus-unit refill periods, documented numeric/30-day validation, polymorphic Jackson DTOs, float-to-integer rejection, exact typed responses, and algorithm-aware non-mutating match-test. The focused command passed 20 tests.
- 2026-08-04 — ADRs 0023–0027 accepted for millitoken arithmetic, activation-anchor reconstruction, TTL, typed multi-algorithm representation, and Token Bucket headers.
- 2026-08-04 — Milestone 3 RED: focused migration/repository command ran 15 tests with 5 expected failures: no V2 table/discriminator, no V1 upgrade result, and fixed-only repository conversion rejected Token Bucket definitions.
- 2026-08-04 — Milestone 3 minimum GREEN: added forward-only `V2__distributed_token_bucket.sql`; V1 was not edited. V2 expands the discriminator, adds typed Token Bucket storage and invariants, discriminator-bearing deferrable composite foreign keys, and activated-child protection. Repository joins/conversion/writes are typed; subtype replacement is transactional; first activation uses PostgreSQL `CURRENT_TIMESTAMP`.
- 2026-08-04 — Milestone 3 focused GREEN: `PolicyMigrationTest` and `PostgresPolicyRepositoryTest` passed 15 tests against PostgreSQL 17.6. Evidence covers empty V1+V2 migration, a V1 Phase 4 fixture upgrade, exact Token Bucket round-trip/activation, conflicting subtype rejection, child immutability, Fixed Window compatibility, and Fixed→Token→Fixed through new versions.
- 2026-08-04 — Milestone 4 arithmetic RED: focused compilation failed on the absent `TokenBucketParameters`, state, transition, and Redis-compatible arithmetic model required by the new deterministic examples and jqwik property.
- 2026-08-04 — Milestone 4 GREEN/refactor: added bounded millitoken parameters and a pure quotient/remainder transition. It clamps tolerated rollback without moving the observation timestamp, saturates long idle intervals before multiplication, floors partial credit, carries remainder, preserves balance on rejection, and computes exact bounded retry/full durations. The focused unit/property command passed; jqwik ran 1,000 generated traces against a `BigInteger` reference.
- 2026-08-04 — Milestone 5 Redis RED: focused test compilation failed on the absent Token Bucket key, strict tuple decoder, adapter, and Lua resource.
- 2026-08-04 — Milestone 5 minimum GREEN: added `redis/token-bucket-v1.lua`, a versioned hashed key, strict 13-integer result decoder, and distinct Redis adapter/result. The script uses Redis `TIME`, exact three-field hashes, activation-anchor reconstruction, bounded quotient/remainder refill, deduction only on allow, safe TTL refresh, and the established RedisScript EVALSHA/NOSCRIPT recovery path. Timeout/connection/other ambiguous mutations are not retried.
- 2026-08-04 — Milestone 5 focused GREEN: real Redis 7.4.2 Testcontainers covered initial balance/burst, cost three, server-time partial and continuous refill, identity/version/algorithm isolation, TTL and expiry reconstruction, malformed state without mutation, strict tuples, and `SCRIPT FLUSH`. Ten repeated 60-request trials through three independent Redis clients admitted exactly capacity 20 with a nonnegative balance and TTL present.
- 2026-08-04 — Milestone 6 dispatch RED: `PolicySnapshotCompilerTokenBucketTest` and `RateLimitServiceTest` failed compilation because the immutable compiled-algorithm union, Token Bucket dispatch constructor, activation anchor, and algorithm-specific evaluation metadata did not exist.
- 2026-08-04 — Milestone 6 minimum GREEN: snapshots now contain sealed compiled Fixed Window or Token Bucket algorithms. Compilation validates Token Bucket arithmetic and requires its database activation timestamp; the application service pattern-matches the sealed type and calls only its distinct adapter. Production configuration registers the reviewed Token Bucket script/adapter; explicit teaching-mode configuration uses the Phase 1 in-memory implementation and is not a Redis fallback.
- 2026-08-04 — Milestone 6 observability RED: `RateLimitDecisionLoggerTest.logsTokenBucketFieldsWithoutRawIdentity` failed because algorithm-specific structured fields were absent.
- 2026-08-04 — Milestone 6 HTTP/log GREEN: focused snapshot/service tests, both Fixed Window and Token Bucket HTTP contract suites, and logger tests passed. Token Bucket responses expose capacity, floor whole-token remaining balance, ceiling time-to-full reset, and ceiling affordable-cost retry while JSON retains exact retry milliseconds. Rejections never subscribe to the backend. Redis failures preserve per-policy FAIL_OPEN/FAIL_CLOSED semantics and fail-open emits no normal remaining value. Logs add the requested scaled balance/refill/cost/outcome/reconstruction data without raw identity.
- 2026-08-04 — A forced full `:gateway:test --rerun-tasks` passed after runtime integration, including PostgreSQL and Redis Testcontainers; retained Fixed Window HTTP behavior remains green.
- 2026-08-04 — Coverage RED: the first forced Phase 5 JaCoCo verification reported 86% branch coverage. Decoder, unsafe-boundary, failure-classification, and configuration branches lacked behavioral assertions; no threshold was weakened.
- 2026-08-04 — Coverage GREEN/refactor: substantive strict-decoder, malformed-state, maximum-bound, and failure-path tests raised the forced gateway result above every 90% gate. After the final trace seam refactor the suite contained 260 tests with zero failures, errors, or skips and measured 2,883/2,958 lines (97.46%), 817/891 branches (91.69%), and 583/606 methods (96.20%).
- 2026-08-04 — Shared-trace GREEN: deterministic timestamped traces run the Phase 1 Token Bucket, the bounded Java Redis-compatible model, and the real Redis adapter. The test injects a package-private script executor that derives a timestamp-controlled test script from the reviewed production Lua resource. The public production constructor always uses Redis `TIME`, and no application bean or external contract accepts a timestamp.
- 2026-08-04 — Propagation GREEN: focused snapshot refresh/repository suites proved Token Bucket activation without restart, all-gateway convergence, independent state for a new version, Fixed→Token→Fixed transitions through new versions, stale-event rejection, missed-event reconciliation, and preservation of the prior complete snapshot after invalid Token Bucket compilation.
- 2026-08-04 — Milestone 7 behavior acceptance GREEN: `scripts/phase5-token-bucket-e2e.sh` passed standalone and in the closeout verification. It proved exactly five of six immediate requests and five backend deliveries, bounded continuous refill using returned retry metadata, cost-three admission of three requests with one whole token left and rejection of the fourth, dynamic Fixed Window→Token Bucket→new Token Bucket activation, and fresh state namespaces.
- 2026-08-04 — Repeated multi-replica concurrency GREEN: the behavior acceptance program ran three fresh-identity trials of 60 concurrent HAProxy requests at capacity 20. Every trial admitted exactly 20, reached multiple gateway replicas, retained a valid Redis hash and TTL, and did not multiply capacity by three replicas. The real-Redis integration suite separately repeated ten three-client trials at capacity 20. No balance became negative or exceeded capacity and rejected work did not deduct state.
- 2026-08-04 — Milestone 7 resilience acceptance GREEN: `scripts/phase5-token-bucket-resilience-e2e.sh` passed standalone and in closeout. It proved an isolated gateway retained its prior snapshot, reconciliation installed the missed algorithm change after restoration, partially consumed Redis state survived gateway restart and replica removal/restoration, capacity was not multiplied, FAIL_OPEN forwarded with degraded metadata and exact backend count, and FAIL_CLOSED returned correlated 503 without backend delivery before recovery.
- 2026-08-04 — Full retained acceptance GREEN: Phase 2, Phase 3 shared/restart/scale/removal, Phase 3 Redis failure, Phase 4 dynamic/missed-event, Phase 4 publication failure, and both Phase 5 programs passed in one `scripts/verify.sh` run. Cleanup traps removed every environment, network, and PostgreSQL volume; final Compose process state was empty.
- 2026-08-04 — Final affected-suite GREEN after the test-only executor refinement: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:spotlessCheck :gateway:checkstyleMain :gateway:checkstyleTest :gateway:jacocoTestReport :gateway:jacocoTestCoverageVerification --rerun-tasks --no-daemon` passed all 13 tasks, including all real PostgreSQL/Redis Testcontainers and the 260-test suite.
- 2026-08-04 — Dependency boundary GREEN: JDK 21 `jdeps --recursive -verbose:class gateway/build/classes/java/main/lab/ratelimiter/gateway/domain/limiter` reported `limiter -> java.base`; the Phase 1 limiter package acquired no gateway, Spring, Redis, or persistence dependency.

## Decision log

- 2026-08-04 — Preserve the existing wrapped Fixed Window contract; add Token Bucket as a discriminator branch with a wrapped typed `configuration`.
- 2026-08-04 — Use 1,000 millitokens and carry refill remainder; bounded integer products remain exactly representable in Redis Lua numbers.
- 2026-08-04 — Reconstruct missing buckets from the database activation anchor and Redis time; gateway clocks are not an authority.
- 2026-08-04 — TTL equals bounded time-to-full including tolerated skew, so expiry is semantically equivalent to a full reconstructed bucket.
- 2026-08-04 — Keep Fixed Window and Token Bucket adapters/results distinct; dispatch on the sealed compiled type in the application service.
- 2026-08-04 — The real-adapter compatibility trace uses an injected package-private executor rather than exposing a production timestamp argument. This preserves deterministic equivalence testing while keeping Redis `TIME` authoritative in every production path.

## Discoveries and surprises

- A default Docker Compose stop duration is approximately the same as the Phase 3 window, making a previously retained removal proof timing-dependent.
- Handler-level default-off checks and bearer authentication were insufficient to make event controls acceptance-only because the routes remained present and the flag was not profile-scoped.
- A bounded quotient/remainder implementation must retain the remainder as state; flooring each call independently loses refill under frequent traffic even when every individual multiplication is exact.
- The initial Phase 5 implementation met line and method coverage but missed branch coverage, which exposed meaningful strict-decoding and failure-classification cases rather than a need to change the gate.

## Risks and limitations

- Redis Lua exactness depends on enforcing every stated bound consistently at API, persistence, compilation, invocation, and state-decoding boundaries.
- PostgreSQL and Redis clocks can differ; only the documented five-minute future-anchor tolerance is supported.
- Redis Cluster/Sentinel and cross-region clock/state behavior remain explicit non-goals.
- Redis Cluster/Sentinel, cross-region clock coordination, dynamic per-request costs, and additional distributed algorithms remain explicit non-goals rather than incomplete Phase 5 work.

## Final outcome

Phase 5 is complete. The integrated path is Admin API → immutable PostgreSQL policy version → transactional outbox/Pub/Sub plus reconciliation → atomic complete gateway snapshot → Redis Token Bucket Lua state shared by all gateway replicas → catalog backend. Fixed Window remains fully supported, and every retained Phase 2–4 acceptance scenario passed unchanged.

### Delivered files and contracts

- Added forward-only `gateway/src/main/resources/db/migration/V2__distributed_token_bucket.sql`; `V1__policy_control_plane.sql` has no diff. V2 adds typed Token Bucket configuration, discriminator-bearing deferrable foreign keys, database invariants, and activated-child immutability.
- Added the reviewed `gateway/src/main/resources/redis/token-bucket-v1.lua`, typed Redis key/result/adapter/arithmetic classes, sealed policy definition and compiled-algorithm unions, in-memory teaching adapter, acceptance-only event-control configuration, integration/property/trace/HTTP tests, and two Phase 5 acceptance programs.
- Extended the strict policy schema and OpenAPI contract to a wrapped, discriminated `FIXED_WINDOW | TOKEN_BUCKET` union. Fixed Window retains its prior shape. Token Bucket requires exactly `capacity`, `initialTokens`, `refillTokens`, `refillPeriod`, and `requestCost`; missing, cross-algorithm, decimal, unknown-algorithm, and unknown fields are rejected.
- Added ADRs 0023–0027 and `docs/architecture/DISTRIBUTED_TOKEN_BUCKET.md`; updated policy/system architecture, command, README, and structure documentation.

### Arithmetic, state, TTL, and headers

- One token is 1,000 millitokens. Capacity, refill quantity, and request cost are integers in 1..100,000; initial tokens are 0..capacity; cost is at most capacity; refill period is 1..86,400,000 ms; empty-to-full is at most 30 days; future-anchor/clock rollback tolerance is 300,000 ms. The maximum bounded product is 8.64e15, below `2^53-1`; unsafe policies fail rather than clamp.
- Refill uses full-period plus remainder arithmetic with a persisted division remainder, conservative floor credit, capacity saturation before unbounded multiplication, and exact bounded retry/reset calculations. A 1,000-case jqwik suite matched a `BigInteger` reference.
- Redis keys are `ratelimit:{p=<base64url-policy-id>:v=<version>:a=token-bucket:i=<sha256>}` and never contain raw identity. The hash contains exactly `tokens`, `last_ms`, and `refill_remainder`. Lua validates canonical arguments and stored values before mutation, obtains Redis server time, atomically refills/decides/persists/expires, deducts only allowed cost, and returns a strictly decoded versioned 13-integer tuple.
- Missing state reconstructs from PostgreSQL's database-authored first-activation timestamp plus Redis time and the configured initial balance. TTL is the bounded time until full including tolerated skew, so an expired idle identity reconstructs full rather than incorrectly resetting to a smaller initial balance.
- Token Bucket `RateLimit-Limit` is configured whole-token capacity; `RateLimit-Remaining` is floor whole-token balance; `RateLimit-Reset` is ceiling seconds until full; rejected `Retry-After` is ceiling seconds until the configured request cost is affordable; JSON `retryAfterMilliseconds` is exact. Fixed Window headers are unchanged.

### Runtime and failure behavior

- Snapshot compilation creates either `CompiledFixedWindowAlgorithm` or `CompiledTokenBucketAlgorithm`; `RateLimitService` pattern-matches this sealed type outside HTTP handlers. Unsupported/unsafe candidates fail as a whole and leave the prior immutable snapshot installed. Match-test identifies the selected algorithm without touching Redis.
- Redis time is authoritative in production. The deterministic compatibility trace's injected timestamp executor is package-private, test-only, and unreachable from production configuration or contracts.
- FAIL_OPEN forwards degraded without local mutation or fabricated remaining values. FAIL_CLOSED returns a structured correlated 503 and does not forward. Timeout, connection, script, malformed-result/state, cache-flush recovery, and recovery-after-Redis-return behavior are covered; only safe `NOSCRIPT` cache recovery re-executes.

### Test and acceptance results

- Gateway: 260 tests, zero failures/errors/skips; 97.46% lines (2,883/2,958), 91.69% branches (817/891), 96.20% methods (583/606).
- Traffic simulator, catalog, orders, payments, and portal: 100% for every supported independent coverage metric. Contract suite: 15 passed.
- PostgreSQL 17.6 focused migration/repository suite: 15 passed, including empty V1+V2 and V1-data upgrade. Redis 7.4.2 integration covered Lua behavior, malformed state, expiry reconstruction, server time, strict decoding, and `SCRIPT FLUSH`.
- Arithmetic property test: 1,000 generated traces matched the higher-precision reference. Shared deterministic traces matched Phase 1, the bounded model, and the real Redis adapter within the documented millitoken precision.
- Concurrency: ten integration trials through three independent Redis clients and three HAProxy acceptance trials through multiple gateway replicas admitted exactly capacity 20. Capacity/refill did not multiply with replica count; TTL and state invariants held.
- Dynamic algorithm changes, stale-event rejection, deliberately missed-event reconciliation, gateway restart/scale change, FAIL_OPEN, and FAIL_CLOSED all passed with exact backend-delivery assertions. Every retained Phase 2–4 and new Phase 5 acceptance program passed and cleaned up.
- `jdeps` reported `limiter -> java.base`.

### Exact closeout commands

The following requested commands were executed successfully and their detailed component output is summarized in `docs/COMMANDS.md`:

```text
scripts/check-repository-structure.sh
scripts/check-ci.sh
scripts/format.sh
scripts/static-checks.sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:compileJava :gateway:compileTestJava --rerun-tasks --no-daemon
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --rerun-tasks --no-daemon
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:jacocoTestReport :gateway:jacocoTestCoverageVerification --rerun-tasks --no-daemon
scripts/test.sh
scripts/coverage.sh
scripts/validate-contracts.sh
scripts/build.sh
ADMIN_BEARER_TOKEN=test-only POSTGRES_PASSWORD=test-only docker compose config --quiet
scripts/lint-dockerfiles.sh
scripts/container-smoke.sh
scripts/phase2-e2e.sh
scripts/phase3-e2e.sh
scripts/phase3-redis-failure-e2e.sh
scripts/phase4-e2e.sh
scripts/phase4-publication-failure-e2e.sh
scripts/phase5-token-bucket-e2e.sh
scripts/phase5-token-bucket-resilience-e2e.sh
scripts/verify.sh
```

Focused commands also ran migration/repository tests, arithmetic unit/property tests, real-Redis adapter/trace/repeated-concurrency tests, snapshot/Pub/Sub/reconciliation tests, both HTTP contract suites, JDK 21 `jdeps`, `git diff --check`, `bash -n` shell validation, placeholder/skipped-test/forbidden-import scans, and final empty-Compose cleanup verification.

### Assumptions, limitations, and deviations

- Assumption: PostgreSQL and Redis clocks differ by no more than the documented five-minute future-anchor tolerance. Larger inconsistencies deliberately fail safely.
- Limitations are the approved non-goals: no Redis Cluster/Sentinel, cross-region enforcement, client-controlled time/cost, floating-point policies, additional distributed algorithms, or operational metrics stack.
- No new production dependency was added. No prior migration, Phase 1 algorithm semantics, coverage threshold, retained acceptance scenario, or externally visible Fixed Window contract was weakened or removed.
- Scope deviations: none.
