# Insert a local gateway slice before distributed enforcement

**Status:** Accepted

## Context

The original delivery sequence in `PROJECT_SPEC.md` described Phase 2 as a
load-balanced, multi-replica, Redis-backed fixed-window slice. Phase 1 produced
the storage-independent in-memory algorithms, but no HTTP policy, identity,
forwarding, response-mapping, mock-backend, or container integration boundary.
Introducing all gateway integration boundaries at the same time as Redis
atomicity and horizontal scaling would make failures harder to isolate.

The repository owner approved a narrower Phase 2 that integrates the existing
in-memory fixed-window reference implementation into one locally runnable
Spring WebFlux gateway and one catalog backend. Redis, PostgreSQL, a load
balancer, and multiple replicas are explicitly excluded from this phase.

## Decision

Insert a single-gateway local integration phase before distributed
enforcement. This Phase 2 uses:

- one Spring WebFlux gateway;
- one declaratively configured fixed-window policy;
- one process-local limiter instance per normalized client/route identity;
- one FastAPI catalog backend;
- Docker Compose containing only the services required by that slice.

The local limiter registry is an explicit educational single-node mode. It is
not a Redis failure fallback and must not be described as horizontally
correct. The existing Phase 1 algorithm API and semantics remain unchanged.

The previously planned Redis-backed, load-balanced, multi-replica proof is
deferred to the next distributed-enforcement phase. ADRs 0002 through 0005
remain authoritative for that later work.

## Alternatives considered

- Add Redis, multiple gateways, and a load balancer in the same phase as the
  first HTTP integration.
- Change the Phase 1 domain API to expose HTTP-oriented behavior.
- Skip a real backend and fabricate catalog responses in the gateway.

## Consequences

The first real request path can prove gateway boundaries and Phase 1 semantic
reuse independently of distributed storage. Local state resets with the
gateway process and limits are not shared across replicas. A later phase must
replace only the state adapter with an atomic Redis implementation and prove
multi-replica behavior without weakening the HTTP contract.

## Verification

The Phase 2 ExecPlan records RED-GREEN-REFACTOR evidence, independent gateway
and catalog coverage, `jdeps` output, container health, and an end-to-end test
that proves rejected traffic never reaches the catalog.

## Known limitations

This decision changes delivery order, not the final system architecture.
Process-local enforcement is correct only for the single gateway instance
started by this phase.
