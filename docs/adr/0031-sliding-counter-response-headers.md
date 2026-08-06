# Give Sliding Window Counter headers weighted-window meanings

**Status:** Accepted

## Context

Fixed Window reset semantics falsely imply that all Sliding Counter history disappears at the next boundary. A rounded weighted estimate also needs a stable interpretation for remaining capacity.

## Decision

For Sliding Window Counter, `RateLimit-Limit` is configured weighted-window limit; `RateLimit-Remaining` is `max(0,floor((L*W-N)/W))`, the maximum immediately admissible whole request cost; `RateLimit-Reset` is ceiling seconds until represented weighted usage becomes zero with no more arrivals; and rejected `Retry-After` is ceiling seconds until the configured request cost can be accepted. The zero saturation is required when conservative clock rollback makes weighted usage temporarily exceed the limit. Structured retry milliseconds retain the exact analytical value. Policy/version/correlation headers and Fixed Window/Token Bucket semantics do not change.

## Alternatives considered

Remaining request count; ceiling weighted capacity; next-boundary Reset; omitting reset metadata.

## Consequences

Headers are conservative and do not promise a full reset at the next boundary. With configured cost greater than remaining whole capacity, rejection is expected.

## Verification

Three-algorithm HTTP contract tests cover allowed/rejected boundaries, cost greater than one, reset/retry calculations, failure behavior, and unchanged existing headers.

## Known limitations

These laboratory headers are not a claim of conformance with a future external rate-limit-header standard.
