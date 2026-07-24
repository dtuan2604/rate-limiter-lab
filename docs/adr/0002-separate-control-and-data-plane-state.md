# Separate policy state from runtime limiter state

**Status:** Accepted

## Context

Policy definitions change relatively slowly, while limiter state changes on every request.

## Decision

Store policy definitions and versions in PostgreSQL. Store high-frequency distributed runtime state in Redis. Never query PostgreSQL for every proxied request.

## Alternatives considered

All state in PostgreSQL; all state in Redis; local gateway state.

## Consequences

Two stores add operational complexity but preserve appropriate consistency and performance characteristics.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
