# Use Redis Lua and Redis server time for distributed fixed windows

**Status:** Accepted

## Context

Phase 3 must enforce one fixed-window limit across stateless gateway replicas.
Separate Redis reads and writes lose updates, and gateway clocks can disagree at
window boundaries. ADRs 0004 and 0005 establish atomic Lua and Redis TIME as
project-wide direction; this record fixes the concrete Phase 3 contract.

## Decision

Store the reviewed operation in `gateway/src/main/resources/redis/fixed-window-v1.lua`
and execute it with Spring Data Redis's reactive script executor. The executor
uses EVALSHA and safely falls back to EVAL after NOSCRIPT. The application does
not maintain its own script cache and never retries a completed mutation.

Java obtains Redis TIME only to choose a candidate key. The script obtains TIME
again, converts seconds and microseconds to truncated epoch milliseconds, and
computes `floor(nowMilliseconds / windowMilliseconds)`. It validates the
candidate window and key suffix before any mutation. A mismatch is
non-mutating and can be retried twice; exhaustion is an explicit state failure.

The script validates contract version 1, limit 1..1,000,000, window 1 ms..24 h,
cost exactly one, and canonical integer state. It permits when
`cost <= limit - current`, increments allowed requests only, applies PEXPIREAT
at the exclusive boundary on every existing valid counter, and returns exactly
ten integers: version, outcome, count, remaining, limit, retry milliseconds,
reset epoch milliseconds, Redis-now milliseconds, window ID, and TTL
milliseconds. Java strictly validates the entire tuple and does not recalculate
the allow/reject decision.

## Alternatives considered

- GET, calculate in Java, and SET.
- Redis transactions driven by gateway wall clocks.
- A custom EVALSHA cache and NOSCRIPT handler.
- Charging rejected requests or extending TTL from the last request.

## Consequences

One Redis execution is the linearization point and all replicas share epoch
window semantics. A timeout is ambiguous because Redis may have executed the
script; the gateway neither refunds nor replays it. Missing TTL is repaired
without extending the window. Lua integer values stay below the exact integer
range of Redis's Lua number representation.

## Verification

Real `redis:7.4.2-alpine` Testcontainers tests cover limit edges, reset/retry
values, expiry, TTL repair, malformed state, and SCRIPT FLUSH recovery. Twenty
unique-key repetitions of 100 simultaneous calls through three Lettuce clients
each admit exactly 50, store 50, and retain positive TTL. Composed acceptance
proves five total admissions and one rejection through three gateways.

## Known limitations

Request cost is one. Redis clock rollback can revisit an earlier epoch window,
unlike the clamped in-memory teaching implementation. Only fixed window has a
Redis implementation in Phase 3.
