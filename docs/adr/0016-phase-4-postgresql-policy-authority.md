# Make PostgreSQL policy control the Phase 4 data plane

**Status:** Accepted

## Context

Phase 3 reads one static YAML policy. Runtime updates must survive restarts and
converge across replicas without placing PostgreSQL on the proxy request path.
The roadmap previously scheduled remaining Redis algorithms before this work.

## Decision

Deliver the PostgreSQL policy control plane as Phase 4 and move remaining
distributed algorithms to Phase 5. PostgreSQL is authoritative for policy
identity, versions, lifecycle, audit, and active-set revision. Gateways serve
requests from local immutable snapshots only.

## Alternatives considered

Keep YAML authoritative; store policies in Redis; complete other algorithms
first; query PostgreSQL on each request.

## Consequences

Startup depends on PostgreSQL and propagation is eventually consistent. Redis
continues to own high-frequency limiter state.

## Verification

Migration, startup, request-path, restart, and multi-replica acceptance tests
are recorded in the Phase 4 ExecPlan.

## Known limitations

This is a local single-region control plane, not a globally consistent service.
