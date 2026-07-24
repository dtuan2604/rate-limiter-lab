# Use atomic Redis Lua decisions

**Status:** Accepted

## Context

Separate read/calculate/write operations race across gateway replicas.

## Decision

Use versioned Redis Lua scripts as the initial atomic decision mechanism. Script outputs have tested stable contracts.

## Alternatives considered

Optimistic transactions; distributed locks; local calculation.

## Consequences

Scripts require careful versioning, observability, and integration tests. Complex long-running scripts are prohibited.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
