# Use Flyway and typed relational policy configuration

**Status:** Accepted

## Context

Policy history needs database constraints and future algorithms must not create
algorithm-specific nullable columns or unvalidated runtime maps.

## Decision

Use forward-only Flyway migrations and Spring R2DBC runtime access. Store common
version data relationally and fixed-window parameters in a required subtype
table. Future algorithms receive their own subtype tables. Do not use Hibernate
auto-DDL or production seed migrations.

## Alternatives considered

Liquibase; Hibernate schema generation; one JSONB policy blob; nullable columns
for every algorithm.

## Consequences

The schema uses more joins but keeps configuration typed and constraint-backed.
Flyway uses JDBC only during startup; request-time access remains reactive.

## Verification

An empty pinned PostgreSQL Testcontainer must migrate and repository/constraint
tests must pass.

## Known limitations

Adding an algorithm requires a new migration and typed adapter.
