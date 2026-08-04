# Install complete policy snapshots with one atomic reference swap

**Status:** Accepted

## Context

Mutating collections while requests read them can expose partially loaded or
inconsistent policy sets.

## Decision

Build and validate a complete immutable `PolicySnapshot`, then replace one
`AtomicReference`. Each request captures a snapshot once. Startup, events, and
reconciliation share one serialized refresh coordinator.

## Alternatives considered

Mutable concurrent maps; per-policy swaps; database reads during matching.

## Consequences

In-flight requests may finish on the old snapshot and new requests see the new
one. Failed refresh preserves the last valid snapshot.

## Verification

Concurrent reader, failed compilation, startup, event, and reconciliation tests
prove all-or-nothing visibility.

## Known limitations

Activation is not instantaneous across all gateway processes.
