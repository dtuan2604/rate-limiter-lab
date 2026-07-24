# Use versioned declarative policies

**Status:** Accepted

## Context

Runtime policy updates must be manageable, auditable, validated, and safe across replicas.

## Decision

Represent policies as schema-validated JSON data, persist immutable versions, and activate complete versions.

## Alternatives considered

Hard-coded Java policies; arbitrary policy scripts; general-purpose OPA in the first version.

## Consequences

Schema evolution and semantic validation are required. Active versions are not mutated in place.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
