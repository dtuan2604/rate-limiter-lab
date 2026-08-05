# Architecture Decision Records

Create one numbered Markdown file per durable decision. Do not edit a superseded decision to pretend it was never made; add a new ADR and mark the prior one superseded.

Required sections:

- Status
- Context
- Decision
- Alternatives considered
- Consequences
- Verification
- Known limitations

Initial records in this directory capture already-approved project decisions. Codex may refine implementation details but must use a new ADR for material changes.

## Phase 4 decisions

- `0016` — PostgreSQL authority and the Phase 4/5 roadmap sequence.
- `0017` — Flyway plus normalized typed policy/algorithm tables.
- `0018` — serialized transactional activation and monotonic versions.
- `0019` — durable transactional outbox with leased at-least-once publication.
- `0020` — Redis Pub/Sub invalidation plus PostgreSQL reconciliation.
- `0021` — complete immutable snapshots installed by atomic reference swap.
- `0022` — development-only bearer authentication for admin/internal routes.

## Phase 5 decisions

- `0023` — bounded exact millitoken arithmetic and conservative refill.
- `0024` — missing-state reconstruction from database activation time and Redis time.
- `0025` — semantically safe Token Bucket TTL.
- `0026` — closed typed multi-algorithm policy representation.
- `0027` — Token Bucket-specific response-header meanings.
