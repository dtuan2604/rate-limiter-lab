# Publish policy invalidations through a transactional outbox

**Status:** Accepted

## Context

Publishing after commit has a crash window; publishing before commit can expose
policy state that does not exist.

## Decision

Insert minimal versioned event metadata in `policy_event_outbox` inside the
policy transaction. Replica workers lease committed rows with `FOR UPDATE SKIP
LOCKED`, publish after commit, and retry with capped backoff. Mark success only
after Redis acknowledges publication.

## Alternatives considered

Publish before commit; an in-memory after-commit callback; polling only; Kafka.

## Consequences

No committed activation loses its durable publication intent. Publish may be
duplicated after a crash, so consumers are idempotent and exactly-once is not
claimed.

## Verification

Transaction visibility, rollback, lease, retry, duplicate, and paused-Redis
tests are recorded in the Phase 4 ExecPlan.

## Known limitations

Outbox rows require bounded retention and local operational inspection.
