# Combine Redis Pub/Sub invalidation with PostgreSQL reconciliation

**Status:** Accepted

## Context

Pub/Sub is prompt but lossy; PostgreSQL is authoritative and must repair missed
messages without distributing full policy payloads through Redis.

## Decision

Publish event-version, type, policy ID/version, active-set revision, ID, and
time only. Consumers reload PostgreSQL for a newer revision. A serialized
periodic reconciliation compares the authoritative revision and uses the same
refresh operation.

## Alternatives considered

Pub/Sub payload authority; durable Redis Streams; polling only; direct pushes
to known replicas.

## Consequences

Propagation is eventually consistent, duplicate-safe, observable, and repaired
after a missed message.

## Verification

Real Redis event tests and a three-replica missed-event acceptance scenario
prove convergence without restart.

## Known limitations

Gateways may briefly enforce different valid versions.
