# Use Testcontainers for stateful integration tests

**Status:** Accepted

## Context

Mocks cannot prove Redis scripts, TTLs, database constraints, or transactional behavior.

## Decision

Run Redis and PostgreSQL integration tests against disposable real containers.

## Alternatives considered

Embedded substitutes; shared developer databases; mocked clients.

## Consequences

Tests are slower and require Docker, so unit and integration suites must remain separately runnable.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
