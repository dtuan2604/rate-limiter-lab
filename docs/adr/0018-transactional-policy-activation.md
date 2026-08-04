# Serialize policy activation in PostgreSQL

**Status:** Accepted

## Context

Concurrent admin requests must not create two active versions or let an older
version overwrite a newer activation.

## Decision

Lock the stable policy row, validate stored content, disable the prior active
version, activate the target, increment active-set revision, append audit, and
insert outbox metadata in one transaction. Track the highest activated version;
only monotonic activation or re-enabling that latest version is allowed.

## Alternatives considered

Application-only synchronization; last-write-wins without monotonic versions;
multiple active rows resolved during matching.

## Consequences

Concurrent activations serialize and converge on the highest version. Invalid
activation rolls back every state and event change.

## Verification

Real PostgreSQL transaction and synchronized activation tests prove the partial
unique index and deterministic final version.

## Known limitations

Rollback to an older version requires creating a new higher version.
