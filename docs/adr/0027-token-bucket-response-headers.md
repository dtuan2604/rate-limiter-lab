# Give Token Bucket headers algorithm-specific meanings

**Status:** Accepted

## Context

Fixed Window reset and remaining calculations do not describe a continuously refilling bucket, especially when request cost exceeds one.

## Decision

For Token Bucket, `RateLimit-Limit` is configured whole-token capacity; `RateLimit-Remaining` is the floor of whole tokens currently available, not affordable-request count; `RateLimit-Reset` is ceiling seconds until full; and rejected `Retry-After` is ceiling seconds until the configured request cost is affordable. Structured `retryAfterMilliseconds` retains exact bounded milliseconds. Fixed Window headers do not change.

## Alternatives considered

Affordable-request count; time until one token; time until one request for Reset; reusing Fixed Window values.

## Consequences

Clients can distinguish burst capacity, current balance, full recovery, and retry timing. A positive balance smaller than request cost may still be rejected.

## Verification

Algorithm-specific HTTP contract tests cover allowed/rejected responses, request cost greater than one, exact retry timing, failure modes, and unchanged Fixed Window headers.

## Known limitations

These laboratory headers are not a claim of conformance with a future revision of any external rate-limit-header standard.
