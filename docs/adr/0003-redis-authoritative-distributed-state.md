# Use Redis as authoritative distributed limiter state

**Status:** Accepted

## Context

Horizontal scaling requires all gateway replicas to share one logical client state without sticky sessions.

## Decision

Use Redis for distributed counters, timestamps, token balances, and leaky-bucket meter state. Local state is allowed only in explicit educational single-node mode.

## Alternatives considered

Sticky sessions; local replicated caches; PostgreSQL counters.

## Consequences

Redis availability becomes part of enforcement availability, requiring explicit fail-open/fail-closed behavior.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
