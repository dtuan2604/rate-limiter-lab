# Defer true distributed request queueing

**Status:** Accepted

## Context

Queueing proxied HTTP requests across replica failures introduces delivery, timeout, ownership, and duplication problems beyond a rate-meter exercise.

## Decision

First implement a distributed leaky-bucket policing meter. Add true queueing only after a separate approved ADR defines delivery semantics and failure recovery.

## Alternatives considered

Queue inside each gateway; immediate full distributed queue implementation; omit leaky bucket entirely.

## Consequences

The initial algorithm demonstrates stable outflow admission but not delayed request delivery.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
