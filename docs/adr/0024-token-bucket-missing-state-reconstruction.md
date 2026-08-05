# Reconstruct missing Token Bucket state from its activation anchor

**Status:** Accepted

## Context

Initializing every missing Redis key to `initialTokens` would incorrectly reduce an old idle bucket after its state expires. Gateway clocks cannot be a distributed authority.

## Decision

PostgreSQL records first activation with `CURRENT_TIMESTAMP`. A missing bucket is reconstructed from that immutable activation timestamp, configured initial balance/refill, and Redis server time. A newly activated version therefore begins at its initial balance; a sufficiently old missing identity reconstructs full. Negative age up to five minutes is treated as zero without moving the anchor; larger future-anchor skew fails safely.

## Alternatives considered

Always reset to initial tokens; never expire keys; gateway-local activation time; store a permanent sentinel for every identity.

## Consequences

Expiry, restart, and replica changes cannot reduce a bucket that should be full. PostgreSQL/Redis clock disagreement beyond five minutes makes enforcement unavailable under the configured failure mode.

## Verification

First-use, low-initial-balance, expiry/reconstruction, future-anchor, restart, scale-change, and Redis-time-authority tests.

## Known limitations

The design assumes PostgreSQL and Redis clocks normally differ by no more than five minutes.
