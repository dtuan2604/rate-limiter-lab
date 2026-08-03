# Run three stateless gateways behind health-checking HAProxy

**Status:** Accepted

## Context

The Phase 3 acceptance goal requires proof that adding gateway replicas does
not multiply a logical limit. The development environment needs a small pinned
load balancer with no sticky sessions and dependency-aware backend removal.

## Decision

Pin `haproxy:3.0.8-alpine` and run `load-balancer`, `gateway-1`, `gateway-2`,
`gateway-3`, `redis:7.4.2-alpine`, and `mock-catalog-service` in Compose.
HAProxy uses explicit round-robin balancing and active HTTP checks against each
gateway readiness endpoint; only HTTP 200 is healthy. It has no cookie,
stick-table, source balancing, or other affinity configuration.

HAProxy publishes port 8080, catalog observation uses 8101, and development
gateway ports 8081..8083 permit direct readiness and fail-closed assertions.
Every composed gateway response exposes `X-Gateway-Instance`; that value does
not participate in limiter identity. Compose dependencies require only
`service_started`; application readiness determines usability.

## Alternatives considered

- Nginx or a custom proxy.
- Sticky sessions.
- One gateway with Compose scaling but no stable development instance IDs.
- Kubernetes.

## Consequences

Gateway containers hold no authoritative runtime counters and can be removed,
restarted, or enabled without changing Redis-backed capacity. HAProxy continues
serving through healthy replicas. The direct ports and instance header are
development-only exposure.

## Verification

HAProxy config validation and an affinity-directive scan pass. Automated
acceptance proves responses from multiple instances, five total admissions and
one 429, restart continuity, enabling a third replica without added capacity,
unhealthy removal, and correct behavior when fail-closed readiness removes all
replicas.

## Known limitations

This is a local development topology, not production orchestration. It does not
deploy Kubernetes, Redis Cluster, Sentinel, TLS termination, or cross-region
traffic management.
