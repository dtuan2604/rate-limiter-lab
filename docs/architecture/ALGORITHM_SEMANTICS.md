# Rate Limiter Algorithm Semantics

## Purpose and boundary

This document defines the Phase 1 Java reference semantics for fixed window,
sliding window log, sliding window counter, token bucket, and leaky-bucket
policing. Later Redis implementations must satisfy the same shared contract
where storage does not intentionally change behavior.

The in-memory implementations are educational, single-process limiters. One
limiter object represents one already-selected policy version and limiter
identity. They do not select policies, derive identities, proxy HTTP requests,
persist state, or provide a fallback when Redis fails.

## Shared contract

Every implementation accepts a `RateLimitRequest` containing a positive whole
unit cost and returns a `RateLimitDecision` with:

- whether the request was allowed;
- the configured limit or capacity;
- non-negative whole units remaining immediately after the decision;
- request-specific retry-after duration when a rejected cost can become
  admissible;
- full-recovery reset time;
- policy ID and version;
- algorithm identifier.

An accepted cost changes state immediately. A rejected cost is never charged.
Time-derived cleanup, rotation, refill, or leakage still becomes the new state
even when the request is rejected.

Costs greater than one are supported by all five algorithms. A cost greater
than the configured limit or capacity can never fit under an unchanged policy,
so it is rejected without retry-after.

Policy, request, state, log-entry, and decision types are immutable. Each
limiter serializes decisions for its own in-memory state, so concurrent callers
observe one local transition order. This does not provide cross-process or
multi-replica atomicity.

## Time model

- Time comes only from an injected `java.time.Clock`.
- Algorithms use epoch-millisecond precision. Sub-millisecond clock values are
  truncated.
- Unit tests advance a mutable clock and never sleep.
- If the clock moves backward, the limiter reuses its last observed timestamp.
  Expiry, refill, or leakage pauses until wall time catches up; capacity is not
  restored twice.
- Fixed and weighted-counter windows are epoch-aligned half-open intervals
  `[start, start + window)`.
- The sliding log retains timestamps in `(now - window, now]`. An entry exactly
  one window old is expired before the current decision.

`retryAfter` and `resetAt` have different meanings:

- `retryAfter` is the earliest whole millisecond when the rejected request's
  cost can fit if no other requests occur.
- `resetAt` is full recovery: the next empty fixed window, expiry of all log
  usage, zero weighted estimate, full token capacity, or empty leaky backlog.

## Summary

| Algorithm | State | Decision time | State space | Burst characteristic | Main limitation |
| --- | --- | ---: | ---: | --- | --- |
| Fixed window | window start, used units | O(1) | O(1) | Up to twice the limit around a boundary | Coarse boundary artifact |
| Sliding log | accepted timestamp/cost entries | O(n) | O(n) | Exact over the trailing window | Per-request memory and copying |
| Sliding counter | previous/current counts | O(1) | O(1) | Smoother than fixed window | Weighted approximation error |
| Token bucket | scaled token balance | O(1) | O(1) | Immediate burst up to available tokens | Permits bursts by design |
| Leaky-bucket meter | scaled backlog level | O(1) | O(1) | Burst up to empty capacity, then stable drainage | Polices; it does not queue delivery |

The O(1) labels treat configured-width integer arithmetic as constant time. The
reference model uses `BigInteger` for scaled values, so arithmetic cost grows
with unusually large numeric operands.

## Fixed Window Counter

### Policy and state

- `limit`: maximum accepted cost units in one window;
- `window`: positive whole-millisecond duration;
- state: epoch-aligned window start, used cost, last observed time.

### Transition

1. Align `now` to its epoch window.
2. If that start differs from the stored start, set used cost to zero.
3. Allow when `cost <= limit - used`.
4. Add allowed cost; leave rejected cost out.

Remaining is `limit - usedAfter`. Reset is the exclusive end of the active
window. A rejected non-oversize cost retries at that same boundary.

### Burst behavior and limitations

The counter permits the full limit at the end of one window and the full limit
again at the beginning of the next. Nearly `2 * limit` cost can therefore pass
in a very short real interval. State and transition cost are constant, making
this the simplest algorithm, but it does not model a continuously trailing
window.

### Phase 3 Redis representation

Distributed mode preserves the same allow/reject/count semantics for request
cost one while changing the authoritative time and storage representation.
Redis TIME, truncated to epoch milliseconds, selects the epoch-aligned
half-open window. One Lua execution validates the candidate key/window, reads
canonical integer state, increments allowed requests only, and applies
PEXPIREAT at the exclusive boundary. A rejection leaves the stored count
unchanged. Retry-after and reset delay are the returned boundary TTL; Java does
not subtract its local wall clock.

The key and ten-integer script contract are specified by ADRs 0012 and 0013.
Distributed configuration accepts limits 1..1,000,000 and windows 1 ms..24 h
so all Lua arithmetic stays within exact integer range. Policy versions,
routes, and normalized hashed identities have independent state. Request cost
remains exactly one because the Phase 4 persisted external policy contract does
not expose variable costs.

Intentional differences from the in-memory reference are Redis server time,
string counter storage, exact Redis key expiry, and clock rollback behavior.
Redis rollback can revisit an older epoch window; the local teaching limiter
instead clamps its injected clock. Neither Redis failure mode uses local state.

## Sliding Window Log

### Policy and state

- `limit`: maximum active accepted cost;
- `window`: trailing duration;
- `maximumEntries`: maximum accepted request events retained;
- state: ordered immutable `(timestamp, cost)` entries and last observed time.

Validation requires `maximumEntries >= limit`, because cost-one requests can
create as many active entries as the limit. This also means the current Java
model's sliding-log limit cannot exceed the positive `int` entry bound.

### Transition

1. Remove entries with `timestamp <= now - window`.
2. Sum active costs.
3. Allow when `cost <= limit - activeCost`.
4. Append one entry for an allowed request, regardless of whether its cost is
   one or greater.

Remaining is `limit - activeCostAfter`. For rejection, oldest entries are
accumulated until enough cost will expire; that expiry time determines retry.
Full reset is the newest active entry's expiry, or now when the log is empty.

### Burst behavior and limitations

The log is exact for the documented trailing interval and therefore avoids the
fixed-window boundary burst. With `n` active events, the current immutable
reference transition takes O(n) time for trim, sum, retry scanning, and list
copying, and O(n) state. It is intentionally clear rather than allocation
optimized. The Redis form will need an atomic bounded sorted-set transition and
explicit TTL behavior.

## Sliding Window Counter

### Policy and state

- `limit`: maximum weighted usage;
- `window`: fixed interval used for approximation;
- state: current window start, previous count, current count, last observed
  time.

At elapsed time `e` within a window of length `W`, estimated usage is:

```text
estimate = current + previous * (W - e) / W
```

The implementation compares the exact scaled numerator:

```text
current * W + previous * (W - e)
```

against `(limit - cost) * W`; it does not use floating point.

### Rotation and metadata

- Advancing exactly one window moves current count to previous and clears
  current.
- Advancing two or more windows clears both counts.
- Remaining whole units are `floor(limit - estimateAfter)`.
- Retry is the first millisecond at which the weighted estimate can fit the
  rejected cost, considering both the current and following window.
- Reset is when both weighted contributions become zero.

### Burst behavior and limitations

The weighted estimate smooths a fixed-window boundary using constant state and
constant transition work. It is not an exact log. It assumes requests in the
previous window were evenly distributed, so clustered real traffic can be
overestimated or underestimated until that previous count decays. Conservative
whole-unit rounding can report less remaining capacity than the fractional
mathematical remainder.

### Phase 6 Redis representation

Distributed mode uses the same scaled admission comparison but Redis server
time and a three-field versioned hash. Rotation, admission, conditional
increment, semantic expiry, and strict result construction occur in one Lua
execution. Remaining is `max(0, floor((limit * W - numerator) / W))`; reset is
when both represented contributions reach zero, not merely the next boundary.
An older Redis window fails without mutation, while an earlier timestamp in the
same window is evaluated conservatively.

The exact key, 18-integer tuple, analytical retry calculation, numeric bounds,
TTL, HTTP headers, and operational differences from the Phase 1 teaching model
are specified in `docs/architecture/DISTRIBUTED_SLIDING_WINDOW_COUNTER.md`.

## Token Bucket

### Policy and state

- `capacity`: maximum tokens;
- `initialTokens`: starting whole-token balance, including zero;
- `refillTokens / refillPeriod`: continuous refill rate;
- state: exact scaled token balance and last observed time.

One whole token is represented by `refillPeriodMilliseconds` scaled units.
Elapsed refill adds:

```text
elapsedMilliseconds * refillTokens
```

The result is capped at:

```text
capacity * refillPeriodMilliseconds
```

### Transition and metadata

The request is allowed when scaled balance covers scaled cost, then its cost is
subtracted. Remaining is the floor of whole tokens in the post-decision
balance. Retry uses ceiling division of the token deficit by the refill rate.
Reset uses the same calculation for the deficit to full capacity.

### Burst behavior and limitations

An initially full bucket admits an immediate burst up to capacity, then permits
the configured sustained refill rate. Idle time restores burst capacity but
never above the cap. Millisecond precision means sub-millisecond refill is not
observable. The `BigInteger` representation is an overflow-safe reference
model; a later Redis implementation must define bounded integer configuration
and prove equivalent rounding.

## Leaky-Bucket Policing Meter

### Policy and state

- `capacity`: maximum backlog;
- `leakUnits / leakPeriod`: continuous drainage rate;
- state: exact scaled backlog level and last observed time.

One backlog unit is represented by `leakPeriodMilliseconds` scaled units.
Before each decision, elapsed drainage subtracts:

```text
elapsedMilliseconds * leakUnits
```

and floors the level at zero.

### Transition and metadata

The request is allowed when `levelAfterDrain + scaledCost <= scaledCapacity`.
Allowed cost is added immediately. Rejected cost is not added. Remaining is the
floor of whole space after the decision. Retry is the ceiling-divided time
required to drain enough space for that request. Reset is the time required to
drain the entire backlog.

### Burst behavior and limitations

An empty meter can accept an immediate burst up to capacity. Once full, new
admission becomes governed by the stable leak rate. This is policing only:
there is no request object, wait queue, scheduler, delayed forwarding,
ownership, timeout, duplicate prevention, or restart recovery in its API or
state. True distributed request queueing remains deferred by ADR 0010.

## Concurrency and future Redis parity

The in-memory base class synchronizes state transition and snapshot access.
Every transition replaces one immutable state object. Shared contract tests run
100 synchronized callers against every implementation and assert that exactly
the configured capacity is admitted when time is fixed.

This proves only one-instance correctness. Phase 3 uses Redis server time and
one atomic server-side operation. Real-Redis tests cover independent clients,
routes and versions, TTL, malformed state, script cache misses, and concurrent
calls through independent clients. Composed tests prove one limit across three
gateway processes. No Redis failure path may silently substitute the in-memory
objects.
