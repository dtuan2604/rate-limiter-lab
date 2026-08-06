# Distributed Sliding Window Counter

## Contract and semantics

Phase 6 adds `SLIDING_WINDOW_COUNTER` beside Fixed Window and Token Bucket. Its typed configuration is:

```yaml
algorithm:
  type: SLIDING_WINDOW_COUNTER
  configuration:
    limit: 100
    window: 60s
    requestCost: 1
```

`limit` is 1..1,000,000, `window` is a positive integer plus `ms|s|m|h|d` whose converted value is 1..86,400,000 milliseconds, and `requestCost` is 1..`limit`. Decimal values are not accepted. The duration literal is retained in the API and normalized into amount and unit columns in PostgreSQL.

Redis `TIME`, truncated to nonnegative epoch milliseconds, selects epoch-aligned half-open windows. For window length `W`, elapsed time `e`, previous count `p`, current count `c`, configured limit `L`, and request cost `C`, the exact scaled usage is:

```text
N = c*W + p*(W-e)
allow iff N + C*W <= L*W
```

Admission never uses floating-point division. The post-decision `weightedEstimate` is `ceil(N/W)`. `remainingCapacity` is `max(0,floor((L*W-N)/W))`, the maximum whole cost immediately admissible. The zero saturation is observable only for a conservative same-window clock rollback whose previous contribution can temporarily make scaled usage exceed the configured limit.

Counts never exceed `L`. `L*W` is at most `8.64e13`, and the documented maximum admission/result intermediate `3*L*W` is `2.592e14`, below Lua's exact-integer ceiling `2^53-1`. Configuration, adapter arguments, stored state, and returned values are rejected if they violate the bounds; arithmetic is never silently clamped.

## Rotation and rollback

The hash stores the represented current window ID plus current and previous counts. One Lua execution handles four constant-time cases:

- missing state initializes both counts at zero in the current Redis window;
- the same window retains both counts;
- one-window advancement moves old current to previous and clears current;
- advancement by two or more windows clears both counts without looping.

Rotation precedes admission at an exact boundary. A rejected request persists a valid rotation but never increments the current count. A timestamp earlier within the same stored window increases the previous contribution and is conservatively evaluated without regressing state. A Redis window older than the stored window returns `CLOCK_ROLLBACK` without mutation and follows the policy's normal FAIL_OPEN or FAIL_CLOSED behavior.

Policy version is part of the state namespace, so activation in the middle of a wall-clock window starts empty. Switching algorithms also changes the namespace.

## Redis representation and atomic operation

The exact key is:

```text
ratelimit:{p=<base64url-policy-id>:v=<version>:a=sliding-window-counter:i=<sha256>}
```

The hash fields are exactly `window_id`, `current_count`, and `previous_count`. The normalized identity is SHA-256 hashed; raw identity is absent from keys and logs.

`redis/sliding-window-counter-v1.lua` obtains server time, validates all arguments and existing fields before mutation, rotates, compares exact weighted integers, conditionally increments, persists state, applies absolute expiry, and returns a strict versioned 18-integer tuple. The tuple contains outcome, echoed bounds, window ID/start/elapsed, post-decision counts, numerator and ceiling estimate, remaining capacity, retry/reset durations, Redis time, TTL, and rotation. Java recomputes derived relationships while decoding. Spring may reload and retry only after an unambiguous `NOSCRIPT`; timeout, connection, script, malformed state/result, and rollback outcomes are never automatically retried.

## Expiration

Lua uses `PEXPIREAT` at the instant the represented usage stops contributing:

- if current count is nonzero, current-window start plus `2W`;
- if only previous count is nonzero, current-window start plus `W`.

An allowed new current request may extend expiry to its semantic horizon. A rejection may persist rotation but does not extend expiry merely because it was attempted. Expiry is therefore equivalent to empty state and requires neither scans nor permanent keys. Returned TTL must be positive whenever state remains.

## Retry and reset

For a rejection, define `T=(L-C)W`, pre-decision `N=cW+p(W-e)`, and time to the boundary `b=W-e`.

- If `cW <= T`, exact retry is `ceil((N-T)/p)` milliseconds within the current window.
- Otherwise retry is `b + ceil((cW-T)/c)` milliseconds in the following window.

Zero-divisor cases follow the corresponding analytical branch. No millisecond iteration occurs in production. Property tests compare this result with `BigInteger` arithmetic and bounded brute force. `Retry-After` is the HTTP ceiling in seconds, so it never advises retrying early. Reset is the ceiling duration until both represented contributions become zero with no further requests, not simply the next boundary.

## HTTP and failures

- `RateLimit-Limit`: configured weighted-window limit.
- `RateLimit-Remaining`: immediate admissible whole cost described above.
- `RateLimit-Reset`: ceiling seconds until represented usage becomes zero.
- `Retry-After`: present on rejection and conservative for that request's configured cost.
- `X-RateLimit-Policy`, policy version, gateway instance, and correlation semantics are shared with existing algorithms.

FAIL_OPEN forwards during Redis failure with degraded metadata and no fabricated remaining value. FAIL_CLOSED returns a correlated 503 and does not forward; it never converts infrastructure failure into 429. Neither mode creates authoritative local state.

## Persistence consistency

V3 extends the parent algorithm discriminator and creates `sliding_window_counter_configurations`. The child row repeats the constant discriminator and references `(policy_id, version, algorithm_type)` through a deferrable composite foreign key. The parent discriminator is unique, subtype primary keys prevent duplicates, repository loading requires exactly one of the three supported subtype joins, and activation validation requires the matching child. Check constraints enforce basic numeric and unit invariants. A trigger makes the child immutable after activation. V1 and V2 are unchanged; V2 Fixed Window and Token Bucket records upgrade without conversion.

## Operational limitations

This lab uses one pinned Redis node. Redis Cluster, Sentinel, cross-region limiting, client timestamps, variable per-request cost expressions, state migration between versions, a production Sliding Window Log, and Leaky Bucket Redis implementations remain out of scope. The counter is an approximation of a trailing log; observed error is documented in `docs/experiments/SLIDING_WINDOW_COUNTER_APPROXIMATION.md` and is not asserted as a universal bound.
