# Distributed Token Bucket

## Semantics and compatibility

Phase 5 adds Token Bucket beside Fixed Window; it does not generalize the distributed runtime to other Phase 1 algorithms. A bucket has an integer-token capacity, initial balance, refill quantity and period, and one policy-configured integer request cost. Refill is continuous and conservative: newly available fractional tokens are floored to millitokens, while a persisted division remainder prevents repeated calls from losing sub-millitoken credit.

The Phase 1 in-memory and Phase 5 Redis implementations share these behavioral rules: balances are capped at capacity, a request is allowed exactly when its cost is available, rejected requests do not consume balance, cost is deducted exactly once, reset means time until full, and retry means time until the rejected cost is affordable. Their intentional differences are:

| Concern | Phase 1 in-memory | Phase 5 distributed |
| --- | --- | --- |
| Time | Injected Java clock | Redis `TIME` inside Lua |
| Numeric representation | `BigInteger`, scaled by period milliseconds | Bounded integer millitokens plus division remainder |
| Precision | Exact rational credit at millisecond observations | Floor to 1/1000 token, with remainder carried |
| Missing state | Process creates configured initial state | Reconstructs from database activation anchor and Redis time |
| Expiration | None before process loss | TTL equals bounded time until full |
| Concurrency | Synchronized one-process transition | One atomic Redis Lua transition across replicas |

These differences can make the Redis balance conservatively lower by less than one millitoken at an observation boundary; shared traces compare decisions and the documented precision projection. The Phase 1 domain package remains independent of Spring, Redis, PostgreSQL, and gateway adapter packages.

## Bounded arithmetic

One token is 1,000 millitokens. Capacity, refill quantity, and request cost are 1..100,000 tokens; initial balance is 0..capacity; cost cannot exceed capacity. Refill period is a positive integer plus `ms|s|m|h|d` and 1..86,400,000 milliseconds. Empty-to-full time is at most 30 days. Redis rollback or a future database activation anchor is tolerated for at most five minutes.

For elapsed milliseconds `e`, period `p`, refill millitokens `r`, and carried remainder `q`:

```text
wholePeriods = e / p
partialMs = e % p
partialNumerator = partialMs * r + q
credited = wholePeriods * r + floor(partialNumerator / p)
nextRemainder = partialNumerator % p
```

Before using that formula, the transition calculates bounded time-to-full. If elapsed time reaches it, balance saturates to capacity and remainder becomes zero. Thus unbounded elapsed time is never multiplied. The largest supported bounded product is `100,000,000 × 86,400,000 = 8.64e15`, below Lua's exact integer limit `2^53 - 1`. Unsafe configuration or stored state is rejected; it is never clamped silently.

## Redis representation and atomic operation

The key is:

```text
ratelimit:{p=<base64url-policy-id>:v=<version>:a=token-bucket:i=<sha256>}
```

The hash has exactly `tokens`, `last_ms`, and `refill_remainder`, all canonical nonnegative integers. Raw client identity is never stored in the key. Policy versions and algorithms have distinct namespaces.

`redis/token-bucket-v1.lua` validates arguments and state, obtains Redis time, reconstructs or loads the bucket, clamps tolerated negative elapsed time without moving `last_ms` backward, performs bounded refill, makes the decision, persists the next state, refreshes TTL, and returns one strict versioned 13-integer tuple. Malformed state fails before mutation. Spring's script execution recovers from `NOSCRIPT`; timeouts, connection failures, and other ambiguous mutating outcomes are not retried.

## Missing state and TTL

PostgreSQL `CURRENT_TIMESTAMP` records first activation. For a missing key, Redis time is applied to the configured initial balance from that anchor. A new activation therefore starts at its configured initial balance, while an identity first seen after enough idle time reconstructs full. TTL is the exact bounded time until the represented post-decision bucket becomes full, including tolerated rollback delay. Expiry is consequently equivalent to a full bucket and cannot reset an old identity to a smaller initial balance.

## HTTP headers

- `RateLimit-Limit`: configured whole-token capacity.
- `RateLimit-Remaining`: floor of remaining whole tokens, not affordable-request count.
- `RateLimit-Reset`: ceiling seconds until the bucket is full.
- `Retry-After`: on rejection, ceiling seconds until the configured request cost is affordable.
- JSON `retryAfterMilliseconds`: exact bounded millisecond retry duration.
- `X-RateLimit-Policy`, policy version, correlation, and gateway-instance behavior remain shared with Fixed Window.

FAIL_OPEN forwards with degraded metadata and no normal remaining balance. FAIL_CLOSED returns correlated 503 and does not forward. Neither mode creates local fallback state.

## Operational limitations

Redis is a single pinned node in this lab. Cluster, Sentinel, cross-region operation, client-controlled time/cost, floating-point policy values, and state migration between policy versions are not supported. A new version always receives a fresh namespace. Old state disappears through its algorithm-specific TTL.
