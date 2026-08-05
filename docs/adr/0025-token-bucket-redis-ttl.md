# Expire Token Bucket state only when reconstruction is semantically full

**Status:** Accepted

## Context

Token Bucket state must not accumulate indefinitely, but an early expiry followed by initial-balance reconstruction changes admission behavior.

## Decision

After every atomic decision, set TTL to the exact bounded ceiling time until the represented bucket is full, including any tolerated rollback/future-anchor delay. When already full, retain a small deterministic positive TTL. Discard refill remainder when capacity is reached. Because missing-state reconstruction uses the original activation anchor, expiry at or after time-to-full reconstructs the same full state.

## Alternatives considered

Fixed arbitrary TTL; sliding idle TTL shorter than refill; no expiry; resetting to initial balance after expiry.

## Consequences

Stale identities disappear while admission semantics remain stable. Active non-full buckets refresh TTL on each decision.

## Verification

Real Redis TTL presence/bounds, partial refill, full-bucket, rollback, key-expiry reconstruction, and concurrent initialization tests.

## Known limitations

Redis may delete expired keys later than their nominal TTL; delayed deletion is safe but can retain state longer.
