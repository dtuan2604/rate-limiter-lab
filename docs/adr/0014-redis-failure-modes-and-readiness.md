# Make Redis failure behavior and readiness explicit

**Status:** Accepted

## Context

Redis timeouts, connection loss, script errors, and malformed results are
state-service failures, not ordinary rate-limit rejections. Silently using a
local counter would multiply capacity by replica count and conceal degradation.
Different deployments may prefer availability or enforcement safety.

## Decision

Support `FAIL_OPEN` and `FAIL_CLOSED`; the development default is
`FAIL_CLOSED`. Neither mode updates or consults in-memory authoritative state.

FAIL_OPEN returns `DEGRADED_ALLOW`, forwards the request, omits capacity/reset
headers, adds `X-RateLimit-Degraded: true`, and reports readiness UP with
sanitized degraded details. FAIL_CLOSED returns structured HTTP 503 with error
`RATE_LIMIT_STATE_UNAVAILABLE`, correlation and development instance headers,
does not construct or subscribe to backend forwarding, and reports readiness
DOWN. Liveness remains process-only. IN_MEMORY mode does not contact Redis for
readiness.

Failures are sanitized as TIMEOUT, CONNECTION_FAILURE, SCRIPT_ERROR,
MALFORMED_STATE, MALFORMED_RESPONSE, or WINDOW_MISMATCH_EXHAUSTED. Mutations
receive no application-level automatic retry.

## Alternatives considered

- Silent local fallback.
- Returning 429 when state is unavailable.
- Always fail open or always fail closed.
- Making fail-open gateways unready and therefore unavailable behind HAProxy.

## Consequences

Availability and enforcement posture are explicit and observable. Fail-open
traffic is deliberately not charged and recovery continues surviving Redis
state. Fail-closed removes replicas from HAProxy until Redis recovers. A command
timeout may follow a server-side mutation; it is not replayed.

## Verification

Unit tests cover every sanitized failure outcome in both modes, correlation,
headers, 503 shape, and backend subscription counts. Readiness tests cover
IN_MEMORY, Redis success, fail-open degradation, fail-closed DOWN, and timeout.
The outage acceptance pauses Redis and proves both modes plus recovery and exact
catalog delivery counts.

## Known limitations

Failure mode is global in Phase 3; per-policy overrides remain future control
plane work. Readiness details are sanitized and are not a full metrics system.
