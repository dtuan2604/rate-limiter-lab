# System Architecture

## 1. Architectural shape

The platform separates a high-frequency data plane from a lower-frequency control plane.

```text
                                    CONTROL PLANE
                         ┌────────────────────────────┐
                         │ Admin client and Admin API │
                         └──────────────┬─────────────┘
                                        │
                                        v
                              ┌──────────────────┐
                              │   PostgreSQL     │
                              │ policy versions  │
                              └────────┬─────────┘
                                       │ activation notice
                                       v
                              ┌──────────────────┐
                              │ Redis Pub/Sub    │
                              └────────┬─────────┘
                                       │
              ─────────────────────────┼─────────────────────────
                                       │
                                      DATA PLANE
                                       │
clients -> load balancer -> gateway replicas -> mock backends
                                │
                                v
                           Redis runtime state
```

## 2. State ownership

### PostgreSQL

Authoritative for:

- policy identity and metadata;
- immutable versions;
- lifecycle state;
- activation history;
- backend route definitions;
- audit fields.

PostgreSQL must not participate in each proxied request.

### Redis

Authoritative for distributed algorithm runtime state:

- counters;
- timestamps;
- token balance and refill time;
- leaky-bucket meter state;
- key expiration;
- policy invalidation notifications.

Redis Pub/Sub is a notification mechanism, not an authoritative policy payload store.

### Gateway-local memory

Permitted for:

- immutable compiled policy snapshot;
- route matchers;
- connection pools;
- request-scoped context;
- bounded caches whose loss does not alter correctness;
- non-authoritative debug information.

Forbidden in distributed mode:

- authoritative per-client counters;
- token balances;
- queues whose loss changes enforcement;
- policy mutations performed in place.

## 3. Request path

The implemented Phase 4 catalog path is:

```text
client -> HAProxy -> gateway-1|gateway-2|gateway-3
       -> captured PostgreSQL-loaded snapshot -> fixed-window state adapter
       -> Redis Lua -> mock catalog service
```

HAProxy is round-robin without affinity and admits only readiness-healthy
replicas. `IN_MEMORY` remains an explicit single-instance comparison mode;
`REDIS` is the distributed default.

For each public request:

1. The load balancer selects any healthy gateway replica.
2. The gateway creates or validates a correlation ID.
3. Trusted-proxy handling derives the safe source address if needed.
4. The gateway matches one active policy from its immutable snapshot.
5. The gateway constructs a normalized identity and hashes sensitive components.
6. The algorithm adapter performs one atomic Redis decision.
7. If rejected, the gateway returns 429 and does not open a backend request.
8. If allowed, the gateway proxies to the configured backend.
9. The gateway records decision and backend metrics with bounded labels.
10. Logs include correlation ID, replica ID, policy ID/version, route template, algorithm, and decision.

## 4. Atomicity model

A distributed decision must be linearizable with respect to one limiter key under the documented algorithm semantics: concurrent requests must observe a single atomic state transition order.

The default mechanism is a versioned Lua script executed by Redis. A script must:

- validate expected argument count and basic ranges;
- obtain Redis server time when time is required;
- read current state;
- remove or rotate expired state;
- calculate the decision;
- write the next state only as appropriate;
- apply deterministic TTL;
- return a stable, documented tuple or object.

Java code must not duplicate the authoritative decision calculation after Redis returns. It may translate the result into the common domain response.

Every script requires:

- pure domain contract tests against an in-memory reference model;
- Redis integration tests;
- concurrent multi-instance tests;
- expiry tests;
- malformed-state tests;
- script result decoding tests.

## 5. Redis key requirements

The implemented fixed-window pattern is:

```text
ratelimit:{p=<base64url-policy-id>:v=<version>:a=fixed-window:i=<sha256>}:w=<window-id>
```

Requirements:

- deterministic identity normalization;
- no raw secrets;
- policy version isolation;
- bounded length;
- deterministic TTL;
- cluster-compatible hash tag for all keys touched by one operation;
- documented cardinality expectations;
- metrics for key creation and stale-state cleanup where feasible.

## 6. Time semantics

- Distributed limiter scripts use Redis server time.
- In-memory algorithms use an injected clock.
- Tests use fake time or explicit timestamps and never sleep to advance a limiter window.
- User-visible reset times are calculated consistently from the decision result.
- Wall-clock movement and precision assumptions are documented per algorithm.

## 7. Policy snapshot propagation

Activation sequence:

1. Admin API validates a draft against JSON Schema and semantic rules.
2. PostgreSQL transaction stores the immutable version and marks it active.
3. The same transaction inserts a versioned minimal event in the durable outbox.
4. A leased outbox worker publishes only after commit.
5. Each gateway subscriber fetches the authoritative active set.
6. Gateway validates, compiles, and builds a complete new immutable snapshot.
7. One atomic reference swap makes the snapshot visible to new requests.
8. In-flight requests continue with their captured previous snapshot.
9. Periodic reconciliation compares `policy_set_state.revision` with PostgreSQL
   and repairs missed notifications through the same refresh coordinator.

A failed reload leaves the previous valid snapshot active and emits a health degradation, metric, and structured error.

## 8. Horizontal scaling proof

The automated scaling experiment must verify:

- traffic reaches at least three replica IDs;
- a single logical client receives one global limit;
- allowed count does not multiply when replicas are added;
- a replica restart does not reset runtime state;
- no sticky session configuration is present;
- policy versions converge after activation;
- missed notification recovery occurs through polling.

The experiment should run once with Redis state and once with intentionally selected in-memory mode to demonstrate the failure of per-node limiting.

### Phase 4 development topology

Compose runs HAProxy on 8080, catalog observation on 8101, and direct gateway
ports 8081..8083. HAProxy uses round-robin and active readiness checks with no
session affinity. Redis, PostgreSQL, and HAProxy images are pinned. PostgreSQL
uses a persistent development volume and a health check. Gateways wait for
healthy Redis/PostgreSQL containers and do not report ready until Flyway and the
initial authoritative snapshot load succeed. `X-Gateway-Instance` is enabled
only for this development topology and does not enter identity construction.

| Property | `IN_MEMORY` | `REDIS` |
| --- | --- | --- |
| Intended use | Unit tests, education, explicit single instance | Distributed runtime default |
| Authoritative time | Injected Java clock | Redis TIME |
| Authoritative state | One process | Redis string counter + TTL |
| Replica correctness | Limit multiplies per process | One shared logical limit |
| Redis failure | Not applicable | Explicit fail open or fail closed |
| Restart | Local counter is lost | Counter survives gateway restart |

Operationally, this remains a local lab: Redis is a single unreplicated node,
HAProxy does not terminate TLS, and there is no Cluster, Sentinel, Kubernetes,
cross-region clock strategy, or authentication infrastructure.

## 9. Failure model

### Redis unavailable

Apply explicit `FAIL_OPEN` or `FAIL_CLOSED`. Emit a distinct decision reason.
Never use local state as a silent substitute. The matched Phase 4 policy owns
the failure mode. A Redis outage makes readiness DOWN when any active policy is
fail-closed; an empty snapshot or all-fail-open snapshot remains UP/degraded.
Fail closed returns 503 without forwarding. Fail open forwards with a degraded
header and omits capacity metadata.

### PostgreSQL unavailable during normal proxying

Existing valid gateway snapshots continue to serve. Policy updates fail cleanly. Reconciliation health degrades.

At startup, migration failure, PostgreSQL unavailability, a missing authoritative
revision row, or invalid active policy data fails initialization. The gateway
does not fall back to YAML. A migrated database with no active rows installs an
empty revision-zero snapshot; unmatched proxy requests keep the structured 404.

### Pub/Sub unavailable

Periodic reconciliation restores convergence. Policy activation remains
committed and its outbox row is retained for retry. The protected internal
snapshot reports `REDIS_POLICY_SUBSCRIPTION_UNAVAILABLE`; it never claims
instantaneous propagation.

### Backend unavailable

Return a gateway error according to the proxy contract. Do not refund consumed rate-limit capacity unless an approved policy semantics ADR explicitly requires it.

### Gateway terminated

No authoritative limiter state or policy source of truth is lost. In-flight requests may fail according to normal proxy behavior.

## 10. Backpressure and resource limits

- Set bounded HTTP body, connection, pending-acquire, and timeout limits.
- Do not block Netty event-loop threads.
- Leaky-bucket queueing cannot hold open unbounded client connections.
- Policy debug endpoints must be protected and bounded.
- Sliding-log maximum cardinality must be validated.
- Traffic simulator concurrency must have an explicit ceiling.

## 11. Observability contract

Every request decision should make these facts reconstructable without logging sensitive values:

- correlation ID;
- gateway replica;
- route template;
- policy and version;
- algorithm;
- allowed/rejected/bypassed-on-failure;
- decision latency;
- backend outcome and latency if forwarded.

Metric names and labels must be centrally defined and tested to prevent accidental cardinality growth.

Policy-control logs use sanitized structured fields including `policyEventId`,
`policyEventType`, `requestedPolicyVersion`, `installedPolicyVersion`,
`snapshotRevision`, `reloadTrigger`, `reloadOutcome`,
`reconciliationOutcome`, `databaseOutcome`, `publicationOutcome`, and
`convergenceDelay`. Client IDs and bearer tokens are never logged.

## 12. Consistency and convergence

Activation is eventually consistent, not a global atomic cutover:

```text
PostgreSQL commit -> outbox claim/publication -> event receipt
                  -> authoritative reload -> atomic snapshot installation
```

The outbox polls every 250 ms by default. Local event convergence targets five
seconds; reconciliation runs every 30 seconds by default and bounds a missed
event's recovery delay when PostgreSQL is available. These intervals are
configurable. During the interval, replicas may use different valid snapshots.
Each replica exposes its revision and versions, ignores old events, and retains
its previous snapshot if a refresh fails. At-least-once events may duplicate;
exactly-once delivery and strong consistency are not claimed.
