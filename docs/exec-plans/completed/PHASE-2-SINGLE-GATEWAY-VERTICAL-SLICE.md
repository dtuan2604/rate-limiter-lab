# Phase 2 ExecPlan — Single Gateway Vertical Slice

**Status:** Complete  
**Owner:** Codex, supervised by repository owner  
**Last updated:** 2026-07-26 (completed)

## Purpose

Deliver one complete local request path:

```text
HTTP client
  -> Spring WebFlux gateway
  -> static policy match
  -> client-and-route identity
  -> Phase 1 in-memory fixed-window decision
  -> FastAPI catalog backend
```

A developer will be able to start the gateway and catalog with Docker Compose,
send `GET /proxy/catalog/items`, observe five forwarded requests and one
rate-limited response for the same client in one ten-second window, and prove
from the catalog's protected development counter that the rejected request was
not forwarded.

This phase proves integration without changing the Phase 1 algorithm
semantics. It does not claim distributed or multi-replica correctness.

## Scope and non-scope

Included:

- exactly one gateway proxy route, `GET /proxy/catalog/items`;
- forwarding to catalog `GET /catalog/items`;
- one static declarative policy named `catalog-client-fixed-window`;
- startup validation and conversion to `FixedWindowPolicy`;
- deterministic route matching with explicit unmatched-route behavior;
- trusted local `X-Client-Id` simulation identity combined with normalized
  route ID;
- generated or propagated `X-Correlation-Id`;
- one in-memory fixed-window limiter per policy-version/identity;
- allowed, rejected, invalid-request, unmatched-route, and unavailable-backend
  HTTP mapping;
- rate-limit and correlation response headers;
- FastAPI catalog health, product response, bounded delay, protected
  development counter, and sanitized internal-error handling;
- gateway and catalog container images, minimal two-service Compose, health
  checks, and a real end-to-end acceptance script;
- independent gateway and catalog quality and coverage gates.

Excluded:

- Redis and distributed state;
- PostgreSQL and dynamic policy propagation;
- load balancing and multiple gateway replicas;
- sliding-window, token-bucket, and leaky-bucket HTTP integration;
- order and payment service behavior or containers;
- admin APIs, portal, traffic simulator, authentication infrastructure,
  Prometheus, Grafana, retries, and backend discovery.

## Current repository state

Inspection on 2026-07-26 found:

- Phase 1 is complete under
  `docs/exec-plans/completed/PHASE-1-CORE-ALGORITHMS.md`.
- The worktree was clean before Phase 2 planning.
- The gateway uses Java 21, Spring Boot WebFlux 3.5.16, Gradle 8.14.3,
  Checkstyle, Spotless, and JaCoCo.
- The gateway exposes only Actuator health. It has no proxy route, policy
  configuration, identity extraction, orchestration, forwarding, or gateway
  error mapping.
- `lab.ratelimiter.gateway.domain.limiter` contains the immutable Phase 1
  contract and five in-memory algorithms. `InMemoryFixedWindowRateLimiter`
  already provides the required injected-clock, epoch-aligned, synchronized
  semantics.
- One limiter object represents one already-selected policy-version/identity;
  therefore Phase 2 needs a gateway adapter that owns a bounded set of limiter
  instances rather than a domain API change.
- The catalog module exposes only `GET /health`. Its image already uses Python
  3.12.13, FastAPI 0.139.2, and Uvicorn 0.51.0.
- The existing Compose file is a Phase 0 skeleton that starts unrelated
  services. It must become the requested minimal Phase 2 gateway/catalog
  environment.
- `contracts/error.schema.json` describes only the earlier 429 example and
  requires `policyVersion`; the approved Phase 2 429 body omits that field and
  also introduces structured 400, 404, and 502 bodies. The contract must change
  before their producers.
- `PROJECT_SPEC.md` originally assigned distributed Redis behavior to Phase 2.
  ADR 0011 records the approved insertion of this single-gateway local phase;
  the final distributed architecture remains unchanged.

## Independent Phase 1 verification

The following commands were rerun against the current checkout on 2026-07-26.
No prior completion summary was treated as proof.

1. Explicit compilation:

   `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:compileJava :gateway:compileTestJava --no-daemon`

   Result: exit 0; main and test compilation successful.

2. Fresh gateway tests:

   `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --rerun-tasks --no-daemon`

   Result: exit 0; 69 tests, 0 failures, 0 errors, 0 skipped.

3. Formatting and static analysis:

   `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:spotlessCheck :gateway:checkstyleMain :gateway:checkstyleTest --rerun-tasks --no-daemon`

   Result: exit 0; Spotless, main Checkstyle, and test Checkstyle passed.

4. Fresh coverage:

   `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:jacocoTestReport :gateway:jacocoTestCoverageVerification --rerun-tasks --no-daemon`

   Result: exit 0. Current report counters are 349/354 lines (98.59%),
   87/96 branches (90.62%), 67/67 methods (100%), and 28/28 classes (100%).

5. Fresh full gateway build:

   `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:build --rerun-tasks --no-daemon`

   The first sandboxed attempt exited before Gradle with a denied wrapper-cache
   lock and is not a product failure. The approved rerun exited 0 with all 15
   tasks executed, including compile, test, Checkstyle, Spotless, JaCoCo
   verification, JAR, and boot JAR.

6. Domain dependency boundary:

   `/Users/duongtuanhoang/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home/bin/jdeps -recursive -verbose:package gateway/build/classes/java/main/lab/ratelimiter/gateway/domain/limiter`

   Result: exit 0; the limiter package references only `java.base` packages and
   itself.

These fresh results verify the Phase 1 baseline on which Phase 2 will build.

## Proposed design

### Request and component flow

1. The HTTP adapter accepts only `GET /proxy/catalog/items` and creates or
   propagates a correlation ID before returning any response.
2. The static policy matcher selects one compiled immutable policy by HTTP
   method and exact normalized proxy path. A catch-all proxy route returns a
   structured 404.
3. The identity adapter rejects a missing or blank `X-Client-Id` and otherwise
   creates a collision-safe canonical identity from the case-sensitive client
   value and normalized route ID. The local registry stores only a SHA-256
   identity digest, not the raw client value.
4. The orchestration adapter obtains or creates one
   `InMemoryFixedWindowRateLimiter` for policy ID, version, and identity, then
   calls the unchanged Phase 1 `decide(new RateLimitRequest(1))`.
5. A rejected decision is mapped immediately to 429. The forwarding publisher
   is neither invoked nor subscribed.
6. An allowed decision is forwarded through a non-blocking WebClient to the
   configured catalog base URL. Query parameters, `X-Client-Id`,
   `X-Correlation-Id`, and `Accept` are forwarded.
7. Connection, timeout, and other transport failures map to structured 502
   without automatic retry. The consumed fixed-window unit is not refunded.
8. Allowed/backend-error and rejected responses receive decision-derived
   headers. `RateLimit-Reset` and HTTP `Retry-After` use non-negative
   whole-second ceiling delays; the JSON retry value preserves the exact
   decision duration in milliseconds.

### Static configuration

`application.yaml` contains a list of declarative policies and catalog client
settings. The one production policy has:

```text
id: catalog-client-fixed-window
version: 1
routeId: catalog.items
path: /proxy/catalog/items
method: GET
algorithm: FIXED_WINDOW
limit: 5
window: 10s
```

Spring configuration binding stays outside the domain package. A compiler
validates required IDs, positive limits and windows, supported algorithms,
allowed methods, normalized absolute proxy paths, route identifiers, duplicate
routes, backend URI, and positive timeouts. It converts the external values
into the immutable Phase 1 `FixedWindowPolicy` and one immutable snapshot.
Invalid configuration prevents application startup.

### Gateway package boundaries

```text
config
  external binding, validation, bean wiring
policy
  compiled policy and exact static matcher
identity
  trusted simulation-header validation and canonical digest
application
  per-identity local limiter registry and decision orchestration
proxy
  non-blocking catalog forwarding and readiness probe
http
  route handling, structured errors, and response headers
domain.limiter
  unchanged Phase 1 contracts and algorithms
```

No Spring, Reactor, HTTP, serialization, Redis, or database type enters
`domain.limiter`.

### Catalog service

The catalog app is created from an immutable validated configuration object.
`GET /catalog/items` requires the propagated client and correlation headers,
increments a concurrency-safe request counter on arrival, executes a bounded
injectable delay, and returns:

- service name;
- received client ID;
- correlation ID;
- UTC request timestamp;
- configured simulated delay;
- received query parameters.

`GET /health` remains process health. Protected development-only counter read
and reset endpoints are registered only when explicitly enabled. Unit tests
inject a fake delay and timestamp provider; no unit test sleeps. An unhandled
exception maps to a stable sanitized 500 body.

### Readiness and containers

Compose contains only `catalog` and `gateway`. Catalog becomes healthy through
`GET /health`. Gateway starts after the catalog is healthy and reports ready
only when its reactive catalog health probe succeeds. Gateway publishes port
8080 and catalog publishes 8101 for observation. The container acceptance
script enables the catalog test counter and always tears down with volumes and
orphans removed.

## Invariants

- Phase 1 algorithm public types and fixed-window semantics remain unchanged.
- The domain limiter package has no framework or storage dependency.
- Exactly five unit-cost requests for one identity are allowed per epoch-aligned
  ten-second window; the sixth is rejected.
- Different client identities do not share local state.
- Rejected requests never invoke or subscribe to backend forwarding.
- Missing or blank client ID never falls back to source IP.
- Every gateway response generated by the proxy path contains one non-blank
  correlation ID.
- Existing correlation IDs are propagated unchanged.
- Retry milliseconds come directly from the decision and are not hardcoded.
- Raw client identifiers are not limiter-registry keys.
- Backend transport failures are not retried and do not refund capacity.
- Configuration is either a complete immutable valid snapshot or application
  startup fails.
- Catalog request counting reflects requests that reached the product endpoint,
  not fabricated gateway responses.
- No unit test sleeps to move limiter time or simulate catalog delay.
- Only the gateway and catalog containers are required or started by Phase 2.

## Milestone 1 — External error contract

Observable behavior: executable schema tests accept the approved 400, 404, 429,
and 502 response shapes and reject missing or invalid fields.

Files expected to change:

- `contracts/error.schema.json`;
- `contracts/examples/error.*.json`;
- `contracts/tests/test_contracts.py`;
- this plan.

RED: add examples and schema assertions first.

Expected failure: the current 429-only schema rejects the approved Phase 2 body
and cannot validate the new structured errors.

GREEN: minimally generalize the error contract with status-specific conditional
requirements and stable error codes.

REFACTOR: keep shared fields centralized and retain strict
`additionalProperties: false`.

Focused command:

`conda run -n rate-limiter python -m pytest contracts/tests/test_contracts.py`

Broader command:

`scripts/validate-contracts.sh`

## Milestone 2 — Static policy configuration and matching

Observable behavior: valid YAML compiles to the immutable fixed-window domain
policy; invalid IDs, routes, methods, algorithms, limits, windows, duplicates,
backend URIs, and timeouts fail context startup; matching is deterministic and
unmatched input is explicit.

Files expected to change:

- `gateway/src/main/resources/application.yaml`;
- `gateway/src/main/java/lab/ratelimiter/gateway/config/**`;
- `gateway/src/main/java/lab/ratelimiter/gateway/policy/**`;
- corresponding tests;
- this plan.

RED: write configuration-context and matcher tests before production types.

Expected failure: configuration and matcher types are absent.

GREEN: add minimal binding, compiler, immutable snapshot, and exact matcher.

REFACTOR: keep external configuration records separate from compiled/domain
types and centralize normalization.

Focused command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*StaticPolicy*' --tests '*GatewayProperties*' --no-daemon`

Broader command: full gateway tests.

## Milestone 3 — Identity and fixed-window orchestration

Observable behavior: missing/blank client input is rejected, client/route
identities are collision-safe, first five requests pass, sixth fails,
different clients are independent, and an injected clock opens the next
window.

Files expected to change:

- `gateway/src/main/java/lab/ratelimiter/gateway/identity/**`;
- `gateway/src/main/java/lab/ratelimiter/gateway/application/**`;
- corresponding tests;
- this plan.

RED: write identity and orchestration tests first.

Expected failure: the gateway adapters do not exist.

GREEN: add a canonical identity digest, per-identity registry, and unit-cost
fixed-window orchestration around the existing domain implementation.

REFACTOR: keep storage ownership and identity construction out of the domain
algorithm package.

Focused command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*Identity*' --tests '*RateLimitService*' --no-daemon`

Broader command: full gateway tests.

## Milestone 4 — Reactive HTTP route, response mapping, and forwarding

Observable behavior: the complete in-process gateway HTTP contract covers
missing client ID, correlation creation/propagation, five allowed requests,
sixth rejection, independent clients, window changes, forwarding,
short-circuit rejection, query/header propagation, unavailable backend,
rate-limit headers, and structured schemas.

Files expected to change:

- `gateway/src/main/java/lab/ratelimiter/gateway/http/**`;
- `gateway/src/main/java/lab/ratelimiter/gateway/proxy/**`;
- gateway bean wiring and resources;
- corresponding HTTP and forwarding tests;
- this plan.

RED: add route/handler tests using a recording forwarding seam and fixed clock
before production HTTP types.

Expected failure: no proxy route or response mapping exists.

GREEN: implement the smallest functional WebFlux route, non-blocking WebClient
forwarder, decision header mapper, error mapper, and catalog readiness probe.

REFACTOR: ensure rejected branches return before creating a forward operation;
bound the response body and timeout; keep transport details outside
orchestration.

Focused command:

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests '*GatewayHttp*' --tests '*CatalogForwarder*' --no-daemon`

Broader commands: gateway suite, static checks, and gateway coverage.

## Milestone 5 — Catalog backend

Observable behavior: catalog health, response schema, client/correlation
propagation, bounded configurable delay, protected request counting, invalid
configuration failure, and sanitized internal error behavior all pass without
real sleeps in unit tests.

Files expected to change:

- `mock-services/src/rate_limiter_mock_services/catalog.py`;
- `mock-services/tests/test_catalog.py`;
- `mock-services/README.md`;
- corresponding package configuration if required;
- this plan.

RED: replace the foundation-only catalog test with product behavior tests before
changing catalog production code.

Expected failure: `/catalog/items`, delay configuration, counter endpoints, and
error mapping are absent.

GREEN: implement the application factory, validated settings, injectable delay
and timestamp providers, response, counter, and exception handler.

REFACTOR: keep test endpoints opt-in and service-specific behavior isolated.

Focused command:

`conda run -n rate-limiter python -m pytest mock-services/tests/test_catalog.py`

Broader commands: Ruff format/check, strict mypy, catalog coverage, and package
build.

## Milestone 6 — Containers and real end-to-end acceptance

Observable behavior: `docker compose up --build` starts two healthy containers,
and a script proves five real catalog successes, a sixth 429, exactly five
catalog arrivals, a different-client success, and correlation propagation.

Files expected to change:

- `compose.yaml`;
- `gateway/Dockerfile`;
- `mock-services/Dockerfile` if needed;
- `scripts/phase2-e2e.sh`;
- `scripts/container-smoke.sh`;
- `scripts/verify.sh`;
- `scripts/lint-dockerfiles.sh` if scope changes;
- this plan.

RED: write and execute the acceptance script against the pre-Phase-2 Compose
contract.

Expected failure: the existing Compose environment has unrelated services,
three gateways, no catalog test counter, and no configured catalog dependency.

GREEN: reduce Compose to gateway/catalog, add required environment and health
checks, and align images and script orchestration.

REFACTOR: centralize bounded readiness polling and guarantee cleanup through a
trap.

Focused commands:

- `docker compose config`
- `docker compose build gateway catalog`
- `scripts/phase2-e2e.sh`

Broader command: `scripts/verify.sh`.

## Milestone 7 — Full gates, documentation, and closeout

Observable behavior: all applicable repository and Phase 2 gates pass from the
documented commands, and the plan contains exact final evidence.

Files expected to change:

- `docs/COMMANDS.md`;
- `docs/index.md` if needed;
- this plan;
- only on-scope corrections revealed by gates.

RED: any final gate failure becomes a focused correction with its expected
reason recorded before implementation.

GREEN: make only the minimum on-scope correction.

REFACTOR: remove duplication and stale Phase 0 command wording without lowering
thresholds or adding broad exclusions.

Validation commands:

- `scripts/check-repository-structure.sh`
- `scripts/check-ci.sh`
- `scripts/format.sh`
- `scripts/static-checks.sh`
- `scripts/test.sh`
- `scripts/coverage.sh`
- `scripts/validate-contracts.sh`
- `scripts/build.sh`
- `docker compose config`
- `scripts/lint-dockerfiles.sh`
- `docker compose build gateway catalog`
- `scripts/phase2-e2e.sh`
- `git diff --check`
- domain forbidden-import scan
- JDK `jdeps` domain-boundary verification

## Contract and schema changes

- `contracts/error.schema.json` expands from one 429-only shape to strict Phase
  2 status-specific 400, 404, 429, and 502 shapes.
- The 429 contract removes the previously required `policyVersion` to match the
  approved body. Policy version remains internal decision metadata.
- `application.yaml` gains the static gateway policy and backend configuration
  contract. It is tested through Spring binding/startup behavior rather than a
  public JSON Schema.
- The catalog JSON response and protected development counter responses become
  tested HTTP contracts local to the mock service.
- No admin OpenAPI, Redis result, event, database, metric, or traffic-scenario
  contract changes.

## Data and migration considerations

There is no PostgreSQL, Redis key, persistent data, or migration. Local limiter
state is discarded on gateway restart. The policy version participates in the
local registry key so a future immutable version cannot accidentally reuse
incompatible state. The catalog counter is process-local development evidence
and resets on restart or through its protected reset endpoint.

## Security and failure analysis

- `X-Client-Id` is trusted only as an explicit local simulation input in this
  phase; no source-IP or forwarding-header fallback exists.
- The limiter registry uses a digest of length-delimited identity components;
  raw client values are forwarded to the mock backend as required but are not
  used as registry keys.
- The sole backend base URI comes from validated application configuration;
  requests cannot choose arbitrary destinations.
- WebClient timeout and response buffering are bounded.
- Backend failures are sanitized to 502, never retried, and do not expose stack
  traces.
- Catalog exception responses are sanitized.
- Counter endpoints are absent unless a development flag is enabled.
- Process-local state is not safe for multiple gateway replicas. Compose starts
  exactly one.
- There is no silent fallback behavior because Redis is not configured or
  attempted in this explicit local mode.

## Validation plan

Commands are listed per milestone and in Milestone 7. Every Python invocation
uses the required `rate-limiter` Conda environment. Coverage must independently
pass:

- gateway: line >= 90%, branch >= 90%, method >= 90%;
- catalog module: line/statement >= 90%, branch >= 90%; Python function
  coverage is not separately gated by the pinned coverage tool.

No threshold will be lowered and no broad production exclusion will be added.

## Progress log

- 2026-07-26 — Read both required repository skills; root, gateway, and
  mock-service `AGENTS.md`; product, architecture, policy, algorithm semantics,
  engineering, test strategy, definition-of-done, planning, command, repository
  layout, and index documents; every accepted ADR; the completed Phase 1 plan;
  current gateway/domain tests and production source; current catalog source
  and tests; contracts; build files; Compose; Dockerfiles; and verification
  scripts.
- 2026-07-26 — Independently reran and recorded Phase 1 compilation, 69 tests,
  formatting/static analysis, coverage, full build, and `jdeps` results above.
- 2026-07-26 — Identified the delivery-sequence conflict between the original
  project specification and the approved requested phase. Added ADR 0011 before
  production changes; final distributed architecture remains unchanged.
- 2026-07-26 — Created this active plan before Phase 2 production
  implementation.
- 2026-07-26 — Milestone 1 RED: the focused contract suite ran 10 tests and
  failed four expected assertions because the old schema required the removed
  `policyVersion` field and accepted only status 429.
- 2026-07-26 — Milestone 1 GREEN: generalized the strict error schema to the
  approved status-specific 400, 404, 429, and 502 shapes and removed
  `policyVersion` from the Phase 2 429 examples.
- 2026-07-26 — Milestone 1 focused and broader GREEN: the focused contract
  command and `scripts/validate-contracts.sh` each passed all 10 tests.
- 2026-07-26 — Milestone 2 RED: the focused gateway command reached test
  compilation and failed with six expected missing configuration, compiled
  policy, and snapshot symbols. An initial sandbox wrapper-cache denial was
  rerun with approved cache access and is not counted as RED.
- 2026-07-26 — Milestone 2 GREEN/REFACTOR: added external configuration
  records, strict compiler validation, immutable compiled policy/snapshot,
  exact method/path matching, duplicate detection, and the catalog fixed-window
  YAML policy. The focused configuration suite and then the complete gateway
  suite passed. One intermediate implementation compile failure exposed an
  `Integer`/`Long` validation mismatch; it was corrected before GREEN and is not
  counted as behavioral evidence.
- 2026-07-26 — Milestone 3 RED: the focused identity/orchestration command
  reached test compilation and failed with 23 expected missing extractor,
  digest, and service symbols.
- 2026-07-26 — Milestone 3 GREEN/REFACTOR: implemented bounded,
  length-delimited SHA-256 client/route identities and a concurrent
  policy-version/identity registry that delegates every unit-cost decision to
  the unchanged Phase 1 fixed-window limiter. The focused suite and complete
  gateway suite passed, including five/six, independent-client/route, and
  fake-clock rollover behavior.
- 2026-07-26 — Milestone 4 RED: the focused HTTP/forwarding/readiness command
  reached test compilation and failed with 35 expected missing route, handler,
  backend-client, request/response, WebClient adapter, and readiness symbols.
- 2026-07-26 — Milestone 4 GREEN/REFACTOR: added the functional proxy and
  catch-all routes, structured error records, decision-derived header mapping,
  non-blocking bounded WebClient adapter without retry, catalog readiness
  indicator, and explicit runtime wiring. The focused handler, transport, and
  readiness suites passed, followed by the complete gateway suite. The tests
  prove correlation creation/propagation, exact five/six behavior, independent
  clients and window rollover, query/header forwarding, one backend attempt on
  failure, and zero backend attempts for missing, unmatched, or rejected
  traffic.
- 2026-07-26 — Milestone 5 RED: the catalog focused command failed during
  collection with the expected missing `CatalogSettings` and `create_app`
  symbols; the existing module exposed only health.
- 2026-07-26 — Milestone 5 GREEN/REFACTOR: implemented validated immutable
  settings, application factory, required metadata response, injected delay and
  UTC timestamp providers, concurrency-safe request counter, opt-in counter
  routes, and sanitized internal-error handling. The initial 9-test suite
  passed, followed by Ruff and strict mypy.
- 2026-07-26 — Raw coverage inspection found that the combined 96.10% catalog
  score masked 83.33% branch coverage. Added meaningful invalid-flag and UTC
  provider assertions; the resulting 11-test catalog suite reports 100% lines,
  statements, branches, and functions. A per-metric Python gate remains to be
  wired into the repository coverage script.
- 2026-07-26 — The first integrated gateway coverage gate failed as designed at
  82.45% branches. Expanded meaningful startup validation variants and routed
  all proxy requests through the matcher. The repeated full run passed 102
  tests with 626/635 lines (98.58%), 174/186 branches (93.55%), and 139/139
  methods (100%). Added the missing 90% METHOD rule to the JaCoCo gate.
- 2026-07-26 — Milestone 6 RED: the new acceptance script exited before
  startup because the Phase 0 Compose file exposed ten services instead of the
  approved catalog/gateway pair.
- 2026-07-26 — Milestone 6 GREEN: reduced Compose to one catalog and one
  gateway, added catalog dependency/readiness health, enabled only the
  development counter, and updated container smoke/verification orchestration.
  `docker compose config` passed.
- 2026-07-26 — The first acceptance execution could not connect because Docker
  Desktop was stopped; the approved escalated rerun confirmed the same state.
  Started Docker Desktop, observed Docker Engine 27.5.1, and reran the script.
  Both images built, both containers became healthy, five requests reached the
  real catalog, the sixth returned 429 without increasing the catalog count, a
  different client was allowed, correlation propagation passed, and automatic
  `docker compose down --volumes --remove-orphans` cleanup succeeded.
- 2026-07-26 — Added reusable raw coverage JSON checks for independent Python
  line, statement, branch, and function thresholds; final root coverage
  execution remains to be recorded.
- 2026-07-26 — The first root coverage-script run failed because raw JSON
  generation did not reuse each pytest codebase's coverage configuration; this
  re-counted the traffic simulator's already documented entry-point exclusion.
  Passed the same per-codebase configuration to JSON generation without
  changing any threshold or exclusion.
- 2026-07-26 — Repeated `scripts/coverage.sh` exited 0. Gateway JaCoCo passed;
  traffic simulator, catalog, orders, and payments each emitted 100% line,
  statement, branch, and function coverage from the new independent checks;
  portal coverage reported 100% statements, branches, functions, and lines.
- 2026-07-26 — `scripts/verify.sh` exited 0 on the completed implementation.
  It passed structure and CI checks, formatting, all static/type checks, every
  unit and contract suite, every independent coverage gate, application
  artifact builds, Compose validation, Dockerfile linting, container health
  smoke, and a second real end-to-end acceptance run.
- 2026-07-26 — After correcting stale Phase 0 README/package/build labels,
  repeated `scripts/format.sh`, `scripts/static-checks.sh`, and
  `scripts/build.sh`; all exited 0 and the build emitted
  `All application artifacts built successfully.`
- 2026-07-26 — Final `docker compose down --volumes --remove-orphans` exited 0
  and `docker compose ps --all` printed only the empty table header.
- 2026-07-26 — Final `jdeps` again reported only `java.base` and the limiter
  package itself. Final `git diff --check`, forbidden domain-import scan, and
  skipped-test/placeholder scan all exited cleanly.

## Decision log

- 2026-07-26 — Insert a local integration phase before Redis and horizontal
  scaling; recorded in ADR 0011.
- 2026-07-26 — Reuse the Phase 1 fixed-window public API unchanged through a
  per-identity gateway adapter.
- 2026-07-26 — Use exact method/path matching for the only approved route. A
  general path-pattern abstraction is unnecessary in this phase.
- 2026-07-26 — Hash a length-delimited client/route identity before using it as
  a local registry key. Storing raw client identifiers was rejected.
- 2026-07-26 — Express `RateLimit-Reset` and `Retry-After` as ceiling whole
  seconds while preserving exact retry milliseconds in the JSON body.
- 2026-07-26 — Do not refund an allowed limiter decision when the backend fails,
  consistent with `SYSTEM_ARCHITECTURE.md`.
- 2026-07-26 — Use an opt-in catalog counter rather than a fabricated gateway
  response or always-on observation endpoint.
- 2026-07-26 — Do not invent registry eviction or an identity-capacity HTTP
  error. Bound identity component length and record process-lifetime local
  cardinality as a limitation until Redis TTL semantics are designed.

## Discoveries and surprises

- The independently generated current JaCoCo report contains 349 covered of 354
  lines, while the historical Phase 1 plan recorded 366 of 371. Both ratios are
  above the gate; the current report is authoritative for this run.
- The existing Compose file starts the eventual full platform skeleton by
  default, which conflicts with the requested minimum two-service phase.

## Risks and limitations

- The local registry grows with unique identities for the lifetime of the
  process. A hard cap would require an additional overload response contract,
  while eviction could reset enforcement early. Both are deferred to the
  Redis-backed phase, where TTL and cardinality behavior must be designed
  explicitly. Phase 2 bounds each identity component's length.
- A process restart resets all limiter state and catalog counter state.
- The trusted client header is intentionally not an authentication mechanism.
- Exact fixed-window boundary bursts remain by design.
- Docker validation depends on a functioning local Docker engine.

## Final outcome

Phase 2 is complete. The repository now provides one real locally runnable
request path from `GET /proxy/catalog/items` through a Spring WebFlux gateway,
declarative static policy matching, a length-delimited client/route identity,
the unchanged Phase 1 in-memory fixed-window implementation, and the FastAPI
catalog `GET /catalog/items` endpoint.

### Files changed

- contracts: `contracts/error.schema.json`, four error examples, and
  `contracts/tests/test_contracts.py`;
- gateway configuration/policy: `application.yaml`, `GatewayProperties`,
  `StaticPolicyCompiler`, `StaticPolicyConfiguration`,
  `GatewayRuntimeConfiguration`, `CompiledPolicy`, and
  `StaticPolicySnapshot`;
- gateway identity/application: `ClientIdentityExtractor`,
  `LimiterIdentity`, and `RateLimitService`;
- gateway HTTP/proxy: `GatewayHttpHandler`, `GatewayRoutes`, structured error
  records, `RateLimitHeaders`, catalog request/response/client types, the
  WebClient adapter, and readiness indicator;
- gateway tests: application readiness, static configuration/matching,
  identity, orchestration, HTTP behavior, WebClient transport, and readiness;
- catalog: `catalog.py`, `test_catalog.py`, package description, and README;
- containers/scripts: minimal `compose.yaml`, Phase 2 health smoke and E2E
  scripts, independent coverage checks, verification orchestration, and build
  output wording;
- documentation: root README, verified commands, ADR 0011, and this ExecPlan.

### Final command and evidence log

All commands ran from the repository root.

1. Phase 1 baseline verification:

   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:compileJava :gateway:compileTestJava --no-daemon`
   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --rerun-tasks --no-daemon`
   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:spotlessCheck :gateway:checkstyleMain :gateway:checkstyleTest --rerun-tasks --no-daemon`
   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:jacocoTestReport :gateway:jacocoTestCoverageVerification --rerun-tasks --no-daemon`
   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:build --rerun-tasks --no-daemon`
   - JDK `jdeps` command recorded in the baseline section.

   Final baseline results are recorded in that section. One sandbox cache-lock
   failure and one stopped-Docker-daemon failure were environmental and were
   rerun with the required access; neither is claimed as product RED.

2. Contract slice:

   - `conda run -n rate-limiter python -m pytest contracts/tests/test_contracts.py`
   - `scripts/validate-contracts.sh`

   RED failed four expected examples. GREEN passed 10/10 tests in both focused
   and broader invocations.

3. Gateway slices:

   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests "*StaticPolicyConfigurationTest" --no-daemon`
   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests "*ClientIdentityExtractorTest" --tests "*RateLimitServiceTest" --no-daemon`
   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --tests "*GatewayHttpHandlerTest" --tests "*WebClientCatalogBackendClientTest" --tests "*CatalogReadinessIndicatorTest" --no-daemon`
   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test --no-daemon`
   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:spotlessCheck :gateway:checkstyleMain :gateway:checkstyleTest --rerun-tasks --no-daemon`
   - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:jacocoTestReport :gateway:jacocoTestCoverageVerification --rerun-tasks --no-daemon`

   Each focused RED failed on the expected absent types. Final GREEN is 102
   tests, 0 failures/errors/skips, 98.58% lines, 93.55% branches, and 100%
   methods.

4. Catalog slice:

   - `conda run -n rate-limiter python -m pytest mock-services/tests/test_catalog.py`
   - `conda run -n rate-limiter python -m ruff format --check mock-services`
   - `conda run -n rate-limiter python -m ruff check mock-services`
   - `conda run -n rate-limiter python -m mypy mock-services/src`
   - `conda run -n rate-limiter python -m pytest mock-services/tests/test_catalog.py --cov=rate_limiter_mock_services.catalog --cov-branch --cov-fail-under=90`

   RED failed on missing settings/app-factory symbols. Final GREEN is 11 tests
   and 100% lines, statements, branches, and functions.

5. Container slice:

   - `docker compose config`
   - `scripts/phase2-e2e.sh`
   - `docker compose down --volumes --remove-orphans`
   - `docker compose ps --all`

   RED rejected the ten-service Phase 0 Compose topology. GREEN built both
   images, made both services healthy, proved exactly five backend arrivals for
   six same-client attempts, allowed a different client, propagated
   correlation IDs, and cleaned up. Docker Engine 27.5.1 and Compose
   v2.32.4-desktop.1 were observed.

6. Full closeout:

   - `scripts/coverage.sh`
   - `scripts/verify.sh`
   - `scripts/format.sh`
   - `scripts/static-checks.sh`
   - `scripts/build.sh`
   - `git diff --check`
   - final forbidden-import, skipped-test, placeholder, and JDK `jdeps` scans.

   Every command exited 0. `scripts/verify.sh` included successful Compose
   config, pinned Hadolint Dockerfile validation, image builds, health smoke,
   and a repeated end-to-end acceptance.

### Architecture and limitations

The gateway now has explicit HTTP, policy, identity, orchestration, algorithm,
forwarding, response-mapping, and readiness boundaries. The Phase 1 domain
package was not changed and remains Java-only by `jdeps`.

Known limitations are intentional and documented: local limiter and catalog
counter state reset on restart; the registry retains unique local identities
for the process lifetime; `X-Client-Id` is a trusted simulation header rather
than authentication; fixed-window boundary bursts remain; and correctness is
single-gateway only. Redis, PostgreSQL, dynamic policies, multiple replicas,
load balancing, other algorithms, other backend product routes, metrics, and
authentication were not implemented.

There was no deviation from the approved Phase 2 scope. The only conflict with
the older delivery sequence was resolved before production work by ADR 0011.
