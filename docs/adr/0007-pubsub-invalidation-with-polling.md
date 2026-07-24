# Use Pub/Sub invalidation with polling reconciliation

**Status:** Accepted

## Context

Gateways need prompt updates, but Redis Pub/Sub does not guarantee durable delivery.

## Decision

After PostgreSQL activation commits, publish policy ID/version metadata through Redis Pub/Sub. Gateways reload from PostgreSQL and atomically swap immutable snapshots. Periodic polling repairs missed events.

## Alternatives considered

Polling only; policy payload in Pub/Sub; direct push from admin to every gateway.

## Consequences

Propagation is eventually consistent and must be observable. Gateways retain the prior valid snapshot on reload failure.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
