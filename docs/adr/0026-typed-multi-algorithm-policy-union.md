# Represent policy algorithms as a closed typed union

**Status:** Accepted

## Context

Phase 4 assumed Fixed Window throughout API, persistence conversion, snapshots, and enforcement. Generic maps or handler string switches would move validation into the request path and permit conflicting configuration.

## Decision

Use closed sealed unions containing exactly `FIXED_WINDOW` and `TOKEN_BUCKET` across administrative DTOs, control-plane domain definitions, persistence conversion, and compiled runtime adapters. The JSON contract uses `algorithm.type` as an explicit discriminator and a strict algorithm-specific `configuration`. V2 uses normalized subtype rows tied to the version discriminator. Candidate snapshot compilation is complete and atomic.

## Alternatives considered

Generic maps/JSONB; one wide nullable table; string switches in HTTP handlers; separate policy APIs.

## Consequences

Unknown, missing, cross-algorithm, decimal, and unknown fields fail before runtime. Fixed Window retains its existing external shape. Changing algorithm requires a new mutable draft/version and activated definitions stay immutable.

## Verification

Schema/OpenAPI, serialization, validation, migration, repository, immutability, match-test, snapshot compilation, propagation, and algorithm-switch tests.

## Known limitations

Adding another distributed algorithm requires deliberately extending every union and database constraint; generic extensibility is a non-goal.
