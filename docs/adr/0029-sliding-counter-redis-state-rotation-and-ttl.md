# Store and rotate Sliding Window Counter state atomically

**Status:** Accepted

## Context

Multiple gateways can initialize, admit, and cross a boundary concurrently. Separate Redis reads and writes lose counts or rotate twice, and permanent keys retain stale policy state.

## Decision

Use one reviewed Lua operation driven by Redis `TIME`. The key is `ratelimit:{p=<base64url-policy-id>:v=<version>:a=sliding-window-counter:i=<sha256>}` and the hash fields are exactly `window_id`, `current_count`, and `previous_count`. Missing state initializes empty; the same window retains counts; one-window advancement rotates current to previous; larger advancement clears both without looping. An older current window returns `CLOCK_ROLLBACK` before mutation. Persist valid rotation even on rejection, but increment current only on allowance. Use `PEXPIREAT`: current usage expires at current-window start plus `2W`; previous-only usage at current-window start plus `W`. Only admitted current usage may extend expiry.

## Alternatives considered

Transactions around client-side arithmetic; sorted-set logs; one key per window; relative TTL refreshed by every request; regressing state on clock rollback.

## Consequences

Replica count does not multiply capacity, rejected requests do not increment, and stale state expires without scans. Policy versions and algorithms never share state; raw identity is absent.

## Verification

Pinned Redis integration tests cover all rotations, malformed state, TTL/expiry, namespace isolation, server time, `SCRIPT FLUSH`, and repeated coordinated multi-client boundary races.

## Known limitations

An operational Redis clock rollback into an older epoch window fails through configured FAIL_OPEN/FAIL_CLOSED behavior rather than attempting repair.
