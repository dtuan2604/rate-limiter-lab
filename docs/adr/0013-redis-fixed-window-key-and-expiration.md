# Use versioned hashed fixed-window keys with boundary expiration

**Status:** Accepted

## Context

Distributed counters must isolate policies, versions, identities, routes, and
windows without exposing raw client identifiers or accumulating stale state.
The one-key Lua operation should remain compatible with Redis Cluster key-slot
rules even though Cluster is not deployed in Phase 3.

## Decision

Hash a canonical, length-delimited UTF-8 identity containing component
type/name/value fields for normalized header name `x-client-id`, the
case-sensitive client value, and normalized route ID. Use SHA-256 lowercase hex.
Client values are not trimmed or case-folded after blank/length validation.

The exact key is:

```text
ratelimit:{p=<base64url-policy-id>:v=<version>:a=fixed-window:i=<sha256>}:w=<window-id>
```

Policy IDs are bounded before URL-safe base64 encoding. The full identity hash
appears only in Redis; logs use its first 16 hexadecimal characters. The hash
tag groups windows for one policy/version/identity in one Cluster slot rather
than grouping every limiter key together. Policy versions never share state.
Each key expires with PEXPIREAT at `(windowId + 1) * windowMilliseconds`.

## Alternatives considered

- Raw API/client values in keys.
- Omitting policy version.
- One hash containing all identities or all windows.
- A relative TTL refreshed on access.
- One global Redis Cluster hash tag.

## Consequences

There is at most one key per active policy/version/identity/window. Old state
expires at its exclusive boundary and needs no migration. Route changes and
policy-version changes intentionally start fresh counters. Operators cannot
recover client values from key names, although policy IDs are deliberately
visible in encoded form.

## Verification

Unit tests pin the exact key and canonical digest. Real Redis tests prove
identity, route, and version isolation plus TTL assignment, preservation,
repair, and expiration. Composed acceptance checks count five, a positive TTL
bounded by ten seconds, and absence of the raw test identity in Redis keys.

## Known limitations

Redis Cluster is not deployed, so Phase 3 proves compatible key shape rather
than Cluster operation. SHA-256 is stable but changing canonical components in
a later phase is a state-breaking change requiring a policy version or ADR.
