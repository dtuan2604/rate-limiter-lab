# Phase 4 ExecPlan — Versioned Policy Control Plane and Dynamic Propagation

**Status:** Complete  
**Owner:** Codex, supervised by repository owner  
**Last updated:** 2026-08-03

## Purpose

Replace the static YAML catalog policy with PostgreSQL-backed, versioned policy
control. An authenticated administrator can create, edit, activate, disable,
archive, and restore policy versions. Activation commits in PostgreSQL, creates
a durable outbox event, invalidates gateways through Redis Pub/Sub, and is
repaired by periodic PostgreSQL reconciliation. Each request reads one complete
immutable local snapshot and continues using the existing atomic Redis
fixed-window runtime state.

The decisive acceptance proof starts three gateways behind HAProxy, enforces
version 1 at five requests per ten seconds, activates version 2 at two requests
without restarting a gateway, observes all replicas converge, proves separate
versioned Redis keys, and repairs a deliberately missed event by reconciliation.

## Scope and non-scope

Included: Flyway migrations; PostgreSQL/R2DBC persistence; policy validation,
versioning, lifecycle, audit, and optimistic locking; bearer-protected admin and
internal APIs; transactional activation and outbox; Redis Pub/Sub invalidation;
periodic reconciliation; atomic immutable snapshots; startup/readiness rules;
policy match testing; per-replica visibility; contracts, Compose, Testcontainers,
and multi-replica acceptance.

Excluded: React portal work, additional Redis algorithms, traffic simulator
features, order/payment integration, Prometheus/Grafana, OAuth, roles, Kafka,
Kubernetes, arbitrary policy engines, strong consistency, and synchronous
cleanup or migration of old Redis counters.

## Starting repository state and verified baseline

- Phase 3 is complete. `application.yaml`, `StaticPolicyCompiler`, and
  `StaticPolicySnapshot` currently own the one active catalog policy.
- PostgreSQL, R2DBC, Flyway, admin routes, policy events, and reconciliation do
  not exist. The admin OpenAPI has no paths and the policy schema is the stale
  Phase 0 token-bucket example.
- Redis fixed-window keys already include policy version and require no Lua or
  key-format change.
- The independent 2026-08-03 baseline passed compilation; 140 gateway tests;
  all repository tests and static checks; 959/975 lines, 316/338 branches, and
  195/196 methods; real-Redis race tests; image/Compose/HAProxy validation; both
  Phase 3 acceptance programs; builds; `jdeps`; hygiene scans; and cleanup.
- The first baseline Testcontainers run failed because Docker Desktop was
  stopped. Docker 27.5.1 was started and the fresh rerun passed. This was an
  environment prerequisite failure, not product RED evidence.
- `postgres:17.6-alpine` resolves to multi-platform digest
  `sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94`.

## Implemented design

### Storage and schema

Every gateway embeds the admin API and uses non-blocking Spring R2DBC runtime
access. Flyway and the PostgreSQL JDBC driver run forward-only migrations at
startup; JPA/Hibernate auto-DDL is not used. The schema contains `policies`,
`policy_versions`, `policy_version_methods`,
`policy_version_identity_components`, `fixed_window_configurations`,
`policy_set_state`, `policy_audit`, and `policy_event_outbox`.

Algorithm parameters use typed subtype tables rather than nullable columns or
unvalidated maps. Critical constraints include composite version uniqueness,
positive and bounded configuration, foreign keys, one active version per
stable policy, and a trigger preventing definition mutation after first
activation. Production migrations contain no sample policy. A separate
idempotent bootstrap script may create the catalog policy through the admin API.

### Policy and lifecycle

Phase 4 supports exact normalized `/proxy/**` paths, normalized route IDs, GET,
identity `[HEADER:X-Client-Id, ROUTE]`, FIXED_WINDOW, limits 1..1,000,000,
windows 1..86,400,000 ms, priorities 0..1,000, and explicit failure mode.
Unknown fields are rejected. Validation runs before persistence and again from
stored state before activation/compilation.

Lifecycle transitions are:

| From | Action | To | Rule |
| --- | --- | --- | --- |
| none | create | DRAFT | Creates stable ID and explicit first version only. |
| DRAFT | update | DRAFT | Requires matching `If-Match`; increments revision. |
| DRAFT | activate | ACTIVE | Valid and monotonic. |
| DRAFT | archive | ARCHIVED | No active-set event. |
| ACTIVE | disable | DISABLED | Removes from snapshot and emits invalidation. |
| DISABLED | activate | ACTIVE | Only the highest previously activated version. |
| DISABLED | archive | ARCHIVED | Already absent from snapshot. |
| ARCHIVED | restore | DRAFT or DISABLED | Never-activated returns to DRAFT; otherwise immutable DISABLED. |

All other transitions fail with structured 409. Activating a newer version
locks the stable row, disables the prior active version, and updates
`highest_activated_version`; concurrent versions therefore converge on the
highest version. A partial unique index remains the database invariant.

### APIs and security

All `/admin/api/v1/**` and `/internal/**` paths require the configured bearer
token. Missing and incorrect tokens return 401 plus `WWW-Authenticate: Bearer`.
The actor is configured with the token, comparison is constant-time, and the
token is never logged. Proxy routes remain public and admin/internal paths can
never invoke backend forwarding.

The admin surface provides create policy, clone version, list/retrieve policy
and versions, optimistic draft update, activate, disable, archive, restore, and
match-test. List endpoints use `page`/`size` with default 0/50 and maximum 100.
Activation returns 202 with event ID, active-set revision, pending propagation,
and an explicit warning that the new version starts fresh Redis state.

`/internal/policy-snapshot` returns gateway ID, active policy IDs/versions,
snapshot revision/load time, last reconciliation/event, and sanitized degraded
status. An acceptance-profile-only protected control pauses/resumes event
consumption to prove missed-event reconciliation.

### Activation, event, and reconciliation

The activation transaction locks the policy, reloads and validates the target,
performs lifecycle changes and audit writes, increments the active-set revision,
inserts event metadata in the outbox, and commits. No Redis call occurs in that
transaction. Multi-replica dispatchers claim rows with `FOR UPDATE SKIP LOCKED`
and bounded leases, publish after commit, and retry with bounded exponential
backoff. A crash may duplicate an event; exactly-once is not claimed.

Event version 1 contains only event version/type, policy ID/version, policy-set
revision, event ID, and occurrence time. Consumers reject unknown or malformed
events, ignore duplicate/older revisions, and reload authoritative PostgreSQL
state for newer revisions. `POLICY_ACTIVATED` and `POLICY_DISABLED` are supported.

Startup loads and compiles the complete active set before readiness. PostgreSQL
or invalid-active-data failure prevents startup. No active policies is a valid
empty snapshot. Pub/Sub failure after initial load degrades propagation but
reconciliation continues. Later refresh failures preserve the previous valid
snapshot.

Startup, Pub/Sub, and reconciliation call one serialized refresh coordinator.
It reads one consistent active-set revision and definition set, builds a full
immutable candidate, and atomically swaps an `AtomicReference` only after
validation succeeds. It tracks the highest pending requested revision so an
event arriving during refresh is not lost. Requests capture one snapshot once;
in-flight requests may complete on the old version.

Defaults: 250 ms outbox poll, five-second local event convergence target,
30-second reconciliation with bounded jitter, five-second database timeout,
and exponential failure backoff capped at five minutes. Unit tests use manual
or virtual scheduling and never sleep.

### Data plane and consistency

The Redis Lua contract and key format remain unchanged. Persisted policy failure
mode replaces the global policy mode. Matching is exact method/path, descending
priority, then stable policy ID. Disabled policies are absent. `ROUTE` compiles
to the existing canonical `ROUTE_ID` identity component, preserving the hash.

Decision responses add `X-RateLimit-Policy-Version`; 429 JSON again includes
`policyVersion`. Version changes intentionally create new Redis enforcement
state. Old keys expire naturally and are not scanned or deleted.

The system is eventually consistent: database commit, outbox publication,
event receipt, snapshot load, and installation are separate observable times.
Gateways may briefly use different valid versions, always expose the loaded
version, never install invalid/partial state, never regress on an older event,
and retain the previous snapshot on failure.

## Invariants

- PostgreSQL is authoritative for definitions and lifecycle; Redis Pub/Sub is
  only invalidation and Redis remains authoritative for runtime counters.
- PostgreSQL is never queried per proxied request.
- At most one version of a stable policy is active.
- A definition that was activated is immutable.
- Activation publishes nothing before commit; rollback leaves no event.
- A failed refresh cannot mutate or replace the current snapshot.
- Requests observe complete old or complete new snapshots, never partial state.
- Older/duplicate/malformed events cannot regress or remove valid policy state.
- Rejected requests never reach the catalog backend.
- Policy versions never share Redis counter state.
- No credential, raw client identity, Redis key, or sensitive payload is logged.

## Milestones and TDD checkpoints

1. **Schema-first contracts and ADRs.** Add failing Phase 4 policy/admin/event/
   match/snapshot examples and assertions. GREEN strict JSON Schemas, populated
   OpenAPI, error/version contracts, and ADRs 0016..0022. Focused command:
   `conda run -n rate-limiter python -m pytest contracts/tests`.
2. **Flyway and persistence.** RED empty-database migration, round-trip,
   uniqueness, immutability, audit, and constraints against pinned PostgreSQL.
   GREEN dependencies, V1 migration, typed repositories, and transactions.
3. **Validation, lifecycle, optimistic locking, and auth.** RED exhaustive
   rules/transitions, concurrent activation, bearer token, no-forwarding, and
   redaction. GREEN domain/service and protected admin routes.
4. **Startup snapshot and request migration.** RED startup/failure/empty-state,
   atomic readers, disabled policy, dynamic failure mode, and response version.
   GREEN remove static policy authority and install atomic snapshot access.
5. **Outbox and Pub/Sub.** RED commit ordering, rollback, retry, duplicates,
   stale/malformed events, database failure, and atomic install. GREEN leased
   dispatcher, strict event codec, consumer, and shared refresh.
6. **Reconciliation, match-test, and visibility.** RED missed event, no-change,
   non-overlap, recovery/backoff, zero Redis mutation, and sanitized metadata.
   GREEN scheduler, internal API, and acceptance-only pause control.
7. **Compose and acceptance.** Add pinned PostgreSQL and ephemeral credentials;
   adapt retained Phase 2/3 tests; add Phase 4 propagation and publication-
   failure scripts with bounded polling and reliable cleanup.
8. **Documentation and closeout.** Swap roadmap Phases 4/5, update authoritative
   architecture/policy/commands/auth/consistency docs, run every gate, record
   exact evidence, and move this file to `completed/` only after acceptance.

For every milestone, update the progress log with the RED command and expected
failure, minimum GREEN, focused pass, refactor, affected suite, and coverage.

## Contract and schema changes

Define strict schemas/OpenAPI for policy and version representations; create,
clone, update, pagination, and lifecycle actions; validation/lifecycle/auth/
precondition errors; invalidation event v1; match-test; internal snapshot
metadata; and the decision version header/body. Contract tests validate actual
implementation serialization. No endpoint permits undeclared extensions.

## Data, migration, security, and failure considerations

Flyway migrations are forward-only and automatically applied in development
and tests. Tests prove migration from an empty real database. PostgreSQL and
admin secrets have no committed default; scripts create ephemeral values.
Development Compose uses a persistent PostgreSQL volume, which acceptance
cleanup removes. Normal startup never seeds or overwrites administrator data.

The admin token mechanism is explicitly development-only. PostgreSQL loss at
startup fails initialization; later loss preserves current policy and degrades
control-plane health. Redis enforcement failure continues the established
per-policy fail-open/fail-closed behavior without local fallback. Outbox and
reconciliation repair post-commit publication failure.

## Validation plan

Run focused contract, PostgreSQL, lifecycle, API, event, reconciliation,
snapshot, and concurrency commands for each milestone, followed by:

```text
scripts/check-repository-structure.sh
scripts/check-ci.sh
scripts/format.sh
scripts/static-checks.sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:compileJava :gateway:compileTestJava --rerun-tasks --no-daemon
scripts/test.sh
scripts/coverage.sh
scripts/validate-contracts.sh
scripts/build.sh
docker compose config --quiet with ephemeral credentials
scripts/lint-dockerfiles.sh
HAProxy configuration validation
scripts/container-smoke.sh
scripts/phase2-e2e.sh
scripts/phase3-e2e.sh
scripts/phase3-redis-failure-e2e.sh
scripts/phase4-e2e.sh
scripts/phase4-publication-failure-e2e.sh
JDK jdeps domain verification
git diff --check
shell syntax, placeholder, skipped-test, and forbidden-import scans
docker compose ps --all after cleanup
```

Acceptance proves initial five-of-six enforcement, dynamic two-of-three
version 2 without restarts, multiple replicas, per-replica convergence,
version-isolated Redis keys, missed-event reconciliation, startup reload,
invalid-activation stability, and publication-failure recovery.

## Decision log

- 2026-08-03 — Embed admin behavior in every gateway; the approved topology has
  no separate control-plane service.
- 2026-08-03 — Use R2DBC for runtime access and Flyway/JDBC only at startup.
- 2026-08-03 — Use normalized subtype tables for algorithm configuration.
- 2026-08-03 — Use a transactional outbox plus reconciliation, with idempotent
  at-least-once event handling.
- 2026-08-03 — Archived versions have the explicitly selected restore action:
  never-active to DRAFT, previously active to DISABLED.
- 2026-08-03 — Swap roadmap Phase 4 and Phase 5; do not add Redis algorithms in
  this phase.

## Progress log

- 2026-08-03 — Read root/component instructions, both repository skills,
  planning/requirements/architecture/quality documents, completed Phase 1–3
  plans, all ADRs, contracts, scripts, and current implementation.
- 2026-08-03 — Independently reran and recorded the complete Phase 3 baseline
  above. Worktree remained clean and Compose cleanup completed.
- 2026-08-03 — Verified the PostgreSQL 17.6 Alpine tag/digest and activated this
  ExecPlan before the first Phase 4 RED test.
- 2026-08-03 — Milestone 1 contract RED: `conda run -n rate-limiter python -m
  pytest contracts/tests/test_contracts.py` ran 13 tests and failed five
  expected assertions. The stale policy schema rejected the Phase 4 fixed-
  window document, event/snapshot schemas were absent, and the admin OpenAPI
  still exposed no paths.
- 2026-08-03 — Milestone 1 GREEN: replaced the stale policy schema, added
  strict invalidation-event and snapshot schemas, populated all approved admin
  and internal OpenAPI paths with bearer security, and added ADRs 0016..0022.
  The focused contract command passed all 13 tests. The roadmap now records the
  approved Phase 4/5 swap.
- 2026-08-03 — Milestone 2 migration RED: the focused Gradle command failed at
  test compilation with five expected missing Flyway and PostgreSQL
  Testcontainers symbols. No database dependency or migration existed.
- 2026-08-03 — Milestone 2 migration GREEN: added Boot-managed R2DBC, Flyway,
  JDBC/R2DBC PostgreSQL, and PostgreSQL Testcontainers dependencies plus the V1
  schema. Three real-PostgreSQL migration/constraint tests passed. Resolved
  versions are Flyway 11.7.2, PostgreSQL JDBC 42.7.11, and R2DBC PostgreSQL
  1.0.9.RELEASE.
- 2026-08-03 — Milestone 2 repository RED: the focused repository command
  failed at compilation with 16 expected missing typed policy and repository
  symbols.
- 2026-08-03 — Milestone 2 repository GREEN: implemented typed R2DBC policy
  persistence, transactional creation/activation, audit writes, policy-set
  revision increments, active-set reads, and durable outbox insertion. The
  first GREEN run exposed an invalid R2DBC Testcontainer URL fixture; after
  constructing explicit connection options, `JAVA_HOME=$(/usr/libexec/
  java_home -v 21) ./gradlew :gateway:test --tests
  '*PostgresPolicyRepositoryTest' --rerun-tasks --no-daemon` passed both
  focused real-PostgreSQL tests.
- 2026-08-03 — Milestone 3 lifecycle/validation RED added exhaustive policy
  bounds, every lifecycle transition, optimistic draft replacement, immutable
  activated rows and child collections, duplicate IDs/versions, audit actors,
  concurrent activation, bearer-token, no-forwarding, and strict JSON tests.
  The first focused runs failed on absent domain/lifecycle/admin types and then
  exposed database trigger and R2DBC mapping defects.
- 2026-08-03 — Milestone 3 GREEN implemented typed validation, stable-row
  locking, monotonic activation, partial uniqueness, immutable definitions,
  archive restore, structured 401/400/404/409/412/422/428 errors, constant-time
  token comparison, ETags, pagination, and all approved admin routes. Focused
  validation, lifecycle, activation concurrency, authentication, API, migration,
  and repository suites passed against real PostgreSQL where state mattered.
- 2026-08-03 — Milestone 4 RED proved the existing handler captured a static
  policy and could not change versions atomically. GREEN added `PolicySnapshot`,
  an `AtomicReference` store, complete candidate compilation, a serialized
  refresh coordinator, per-request capture, exact priority matching,
  per-policy failure mode, version headers/body, mandatory PostgreSQL startup,
  and valid empty-snapshot behavior. Concurrent readers observed only complete
  revision/version pairs and failed/older refreshes preserved current state.
- 2026-08-03 — Milestone 5 RED covered rollback/no-publication, leased outbox
  claims, Redis failure retry, strict event payloads, duplicate/older/malformed
  handling, database load failure, and atomic install. GREEN added the durable
  outbox dispatcher, version-1 Redis channel, strict codec/consumer, bounded
  retry lease, and the shared authoritative refresh path. Real PostgreSQL and
  Redis were used for transaction and messaging behavior.
- 2026-08-03 — Milestone 6 RED covered missed events, no-change comparison,
  non-overlap, database recovery, match-test without Redis, and sanitized
  snapshot metadata. GREEN added periodic reconciliation, acceptance-only
  pause/resume, match-test, per-replica visibility, subscription degradation,
  and snapshot-derived Redis readiness. Focused event, outbox, reconciliation,
  snapshot, endpoint, lifecycle, and readiness suites passed.
- 2026-08-03 — Reconciliation scheduling now uses deterministic per-replica
  initial jitter bounded to ten percent of the configured interval, serial
  repetition after success, and exponential retry after failure capped at five
  minutes. The scheduler lifecycle/jitter test and full gateway suite passed;
  no unit test waits on wall-clock time.
- 2026-08-03 — Admin boundary RED found malformed/non-positive route versions
  escaping the reactive error boundary and negative quoted `If-Match` revisions
  returning 500. Centralized parsing now returns the documented structured 422
  and 400 responses before persistence; the full admin suite passes.
- 2026-08-03 — Milestone 7 extended Compose to seven services with pinned
  `postgres:17.6-alpine`, health check, persistent development volume, Flyway,
  and required environment-only admin/database secrets. The idempotent catalog
  bootstrap calls the admin API and never overwrites an active policy. Retained
  Phase 2/3 scripts were adapted to the database-backed policy source.
- 2026-08-03 — `scripts/phase4-e2e.sh` passed all primary scenarios and cleaned
  up: version 1 allowed five of six with exactly five backend deliveries;
  version 2 activated without any gateway container ID change and allowed two
  of three; HAProxy reached all three replicas; all snapshots converged; Redis
  retained distinct version-1/version-2 keys; a paused gateway stayed on the
  old valid snapshot then reconciled; restart loaded PostgreSQL state and kept
  Redis counts; and invalid update changed neither snapshots nor outbox.
- 2026-08-03 — `scripts/phase4-publication-failure-e2e.sh` passed and cleaned
  up: invalid mutation published nothing; Redis stopped after PostgreSQL commit;
  the outbox attempt remained retryable; reconciliation converged all gateways;
  Redis restart led to `PUBLISHED`; no partial snapshot became visible.
- 2026-08-03 — Initial Phase 4 JaCoCo passed line coverage but failed branch
  coverage at 84.51%. Boundary tests were added for meaningful validation,
  event, API, snapshot, repository, lifecycle, and subscription branches; no
  exclusions or thresholds changed. Final measured gateway coverage is
  2,405/2,452 lines (98.08%), 609/660 branches (92.27%), and 502/522 methods
  (96.17%). Traffic simulator, catalog, orders, payments, and portal remain
  100% for supported metrics.
- 2026-08-03 — Final non-container gates passed after the last code change:
  repository formatting; Checkstyle, Ruff, strict mypy, ESLint, strict
  TypeScript; clean Java compilation; 214 gateway tests with zero failures,
  errors, or skips; all other repository tests; every independent coverage
  gate; 13 contracts; all application artifact builds; repository/CI structure;
  `git diff --check`; shell syntax; scoped placeholder/skipped-test scans; and
  forbidden domain-import scan. `jdeps -s` reports only `limiter -> java.base`.
- 2026-08-03 — Acceptance-harness RED: the first post-change
  `scripts/phase4-e2e.sh` rerun exited 1. A trace proved version 1 and version 2
  enforcement were correct, but the script could straddle the epoch-aligned
  fixed-window boundary and later asserted that the version-1 Redis key still
  existed after deliberately waiting beyond its TTL. Minimum GREEN aligned each
  burst to a fresh Redis server-time window, added observed-status diagnostics,
  and checked non-deletion immediately after activation while natural expiry
  remained allowed. The focused rerun exited 0 and passed dynamic activation,
  missed-event reconciliation, restart, independent-version state, and invalid
  input scenarios.
- 2026-08-03 — Retained-suite RED: the first aggregate `scripts/verify.sh`
  rerun reached Phase 2 and observed 404 on its first allowed request. The
  database policy had committed, but the retained script sent HAProxy traffic
  before all replicas installed the eventually consistent snapshot. Minimum
  GREEN added bounded polling of all three protected snapshot endpoints and
  Redis-server-time burst alignment. The focused `scripts/phase2-e2e.sh` rerun
  exited 0.
- 2026-08-03 — Final container GREEN: `scripts/container-smoke.sh`, retained
  Phase 2 and Phase 3 distributed scenarios, Phase 3 fail-open/fail-closed Redis
  outage scenarios, `scripts/phase4-e2e.sh`, and
  `scripts/phase4-publication-failure-e2e.sh` all passed on the seven-service
  topology and removed containers, network, and PostgreSQL volume through
  cleanup traps.
- 2026-08-03 — Final uninterrupted `scripts/verify.sh` exited 0 with
  `Phase 4 CI-equivalent verification passed.` It covered repository/CI
  structure, formatting, static analysis, all tests and coverage, contracts,
  builds, Compose/Dockerfile validation, container smoke, and every retained
  and new acceptance scenario. The final domain command reported
  `limiter -> java.base`; shell syntax, placeholder, skipped-test, and forbidden
  domain-import scans passed; `git diff --check` passed after closeout edits;
  and `docker compose ps --all` showed only the empty table header.

## Risks and limitations

- Propagation is eventual and briefly mixed valid versions are expected.
- A published event may be duplicated after a dispatcher crash.
- Embedded outbox workers require tested database leases across three replicas.
- The development bearer token is not production authentication.
- Only exact catalog fixed-window policies are externally supported.
- PostgreSQL, Redis, and container integration gates require Docker.

## Final outcome

Phase 4 is complete. PostgreSQL is the authoritative versioned policy source;
Redis remains the atomic fixed-window runtime store and carries only minimal
invalidation events. Flyway migration `V1__create_policy_control_plane.sql`
creates the normalized policy/version/method/identity/fixed-window tables,
singleton policy-set revision, append-only audit table, and durable leased
outbox with database constraints and activated-definition immutability.

The changed files comprise the gateway policy-control domain, R2DBC repository,
Flyway migration, admin/internal WebFlux routes and security, outbox/Pub/Sub/
reconciliation/snapshot runtime, request-path integration and tests; strict
OpenAPI/JSON schemas and examples; Compose and acceptance/bootstrap scripts;
ADRs 0016..0022; and the roadmap, architecture, policy model, commands, README,
and this ExecPlan.

The authenticated API implements create, clone, list/get, optimistic draft
replace, activate, disable, archive, restore, and side-effect-free match-test.
Activation locks the stable policy row, validates stored typed data, atomically
changes lifecycle state, increments the policy-set revision, records audit
metadata and the outbox event, then publishes only after commit. Dispatch is
idempotent at-least-once with lease/retry; consumers and reconciliation call the
same serialized full-snapshot refresh and atomically install only a completely
validated immutable candidate.

Acceptance proved version 1 admitted exactly five of six requests and delivered
exactly five to the backend; version 2 admitted two of three without any gateway
container restart; HAProxy reached multiple replicas; all three replicas
reported the same version/revision; versioned Redis state was independent and
old state was not synchronously deleted; a deliberately missed event reconciled
without restart; a restarted gateway loaded current PostgreSQL state; invalid
activation changed neither outbox nor snapshots; and Redis publication failure
left a retryable outbox row while PostgreSQL reconciliation converged every
gateway without a partial install.

The final current-code evidence is 214 passing gateway tests; 13 passing
contract tests; all other repository tests passing; gateway coverage of
2,405/2,452 lines (98.08%), 609/660 branches (92.27%), and 502/522 methods
(96.17%); and 100% supported coverage for traffic simulator, catalog, orders,
payments, and portal. `scripts/verify.sh` exited 0, `jdeps -s` reported only
`limiter -> java.base`, hygiene scans passed, and Compose cleanup is empty.

Known limitations are the documented eventual-consistency interval,
at-least-once duplicate possibility, development-only shared bearer token, and
exact catalog-route fixed-window feature scope. There were no deviations from
the approved product scope; only acceptance-harness timing/assertion corrections
were required during closeout.
