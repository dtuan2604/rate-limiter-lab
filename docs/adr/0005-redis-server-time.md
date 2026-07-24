# Use Redis server time for distributed decisions

**Status:** Accepted

## Context

Gateway machines may have clock skew, which can corrupt refill or window calculations.

## Decision

Obtain time inside the atomic Redis operation. In-memory algorithms use an injected clock.

## Alternatives considered

Gateway wall-clock timestamps; NTP assumptions only.

## Consequences

Redis time becomes the shared clock for enforcement. Tests must avoid real sleeping.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
