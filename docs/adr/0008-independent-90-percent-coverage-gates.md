# Enforce independent 90% coverage gates

**Status:** Accepted

## Context

Aggregate coverage can hide undertested services.

## Decision

Each executable codebase must independently maintain at least 90% line and branch coverage, plus method/function and statement coverage where supported.

## Alternatives considered

One repository aggregate threshold; no threshold; line-only threshold.

## Consequences

Coverage is necessary but insufficient; concurrency, contract, integration, and end-to-end tests remain mandatory.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
