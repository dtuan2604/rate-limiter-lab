# Product Specification: Distributed Rate Limiter Lab

## 1. Purpose

Build a runnable, production-inspired learning platform for implementing, comparing, and observing rate-limiting algorithms under concurrent and distributed traffic.

The complete request path is:

```text
traffic simulator -> load balancer -> one of several gateway replicas
-> selected rate-limit policy -> mock backend service
```

The system must expose enough internal state, metrics, tests, and controlled experiments to explain why each decision was made and how behavior changes across algorithms.

## 2. Core learning objectives

The project must demonstrate:

1. algorithm mechanics and tradeoffs;
2. burst behavior and sustained-rate behavior;
3. concurrency and atomicity failures;
4. differences between local and distributed state;
5. policy matching and dynamic activation;
6. end-to-end forwarding and rejection behavior;
7. horizontal scaling without sticky sessions;
8. observable failure modes and recovery behavior.

## 3. Required system components

### 3.1 Rate limiter gateway

Implement in Java 21 using Spring Boot WebFlux and Gradle.

Responsibilities:

- accept public HTTP traffic;
- identify the client and request scope;
- match an immutable active policy snapshot;
- evaluate the configured limiter algorithm;
- return a standard rejection or proxy the request;
- preserve method, path, query, selected headers, and body;
- propagate or create a correlation ID;
- emit structured logs and metrics.

The gateway must support multiple replicas behind a load balancer. It must not require sticky sessions.

### 3.2 Administrative control plane

Provide an authenticated admin API and a minimal React/TypeScript portal for:

- creating and editing draft policies;
- validating policies;
- activating a new version;
- disabling and archiving policies;
- viewing version history;
- cloning, importing, and exporting policies;
- simulating which policy matches a sample request;
- viewing sanitized runtime and algorithm information.

PostgreSQL is the source of truth for policy definitions and versions.

### 3.3 Runtime state store

Redis is the authoritative state store for distributed limiter state. All decisions that read and modify shared state must be atomic.

An in-memory mode remains required for algorithm unit tests, educational comparison, and explicit demonstrations of why per-node state fails under horizontal scaling.

### 3.4 Mock backend services

Provide catalog, order, and payment services, preferably as separate Python FastAPI applications or clearly isolated deployable packages.

Each service must support configurable:

- normal response;
- artificial delay;
- random failure rate;
- fixed error response;
- temporary unavailability;
- health reporting.

Backend responses must include service name, client ID, correlation ID, timestamp, and simulated processing duration. Rejected gateway requests must never reach a backend.

### 3.5 Traffic simulator

Implement a Python asyncio client capable of creating many logical clients concurrently.

It must support:

- configurable client and tenant identities;
- target routes, methods, headers, and bodies;
- constant, burst, random, ramp, and spike patterns;
- client-specific patterns;
- deterministic random seeds;
- retries that either respect or ignore `Retry-After`;
- configurable request cost;
- summaries by client, tenant, route, result, and latency percentile.

### 3.6 Observability

Provide:

- JSON structured logs;
- correlation IDs;
- Spring Boot Actuator health;
- Micrometer and Prometheus metrics;
- Grafana dashboards;
- per-replica loaded policy version visibility.

Prometheus labels must use bounded values such as policy ID, algorithm, route template, and decision. Raw client IDs, IP addresses, and untemplated request paths are prohibited as metric labels.

### 3.7 Containerized local deployment

Docker Compose must run:

- a load balancer;
- at least three gateway replicas;
- admin portal;
- catalog, order, and payment backends;
- traffic simulator in optional one-shot mode;
- Redis;
- PostgreSQL;
- Prometheus;
- Grafana.

The environment must include health checks, deterministic startup, persistent database storage, seeded sample policies, and sample traffic scenarios.

## 4. Required algorithms

Every algorithm must have a common decision contract and separate in-memory and Redis-backed implementations where applicable.

The decision result should include at minimum:

- allowed or rejected;
- configured limit;
- remaining capacity when meaningful;
- retry-after duration when meaningful;
- reset time when meaningful;
- matched policy and version;
- algorithm identifier;
- optional sanitized debug explanation in non-production mode.

### 4.1 Fixed window counter

Count requests in fixed intervals. Demonstrate boundary bursts.

Distributed state must use an atomic increment and safe expiration strategy. Tests must prove that simultaneous gateway replicas cannot exceed the expected counter semantics.

### 4.2 Sliding window log

Store request timestamps and remove entries outside the current interval.

Distributed implementation should use a Redis sorted set and one atomic operation to trim, count, conditionally add, expire, and return the result. Enforce configurable maximum entries and document memory cost.

### 4.3 Sliding window counter

Approximate a sliding window using weighted current and previous fixed windows.

Document the weighting formula, time semantics, and approximation error. Window rotation, estimation, and increment must be atomic.

### 4.4 Token bucket

Support capacity, initial token count, refill quantity, refill period, and request cost.

Distributed state contains available tokens and last refill time. Use Redis server time for distributed decisions. Prefer scaled integer arithmetic over floating point.

### 4.5 Leaky bucket

Implement first as a distributed policing meter that admits at a stable rate or rejects. Add true request queueing only after an ADR defines delivery semantics, ownership, timeouts, duplicate prevention, failure recovery, and gateway restart behavior.

Do not hold authoritative queues inside an individual gateway replica.

## 5. Client identity and limiter keys

Policies may derive identity from:

- `X-Client-Id`;
- API key or authenticated user identifier;
- `X-Tenant-Id`;
- source IP;
- route template;
- HTTP method;
- backend service;
- global scope.

Example logical scopes:

```text
client:client-17
client:client-17:route:orders.create
tenant:tenant-a:service:payment
global:service:catalog
```

Raw secrets and sensitive identifiers must not appear in Redis keys. Normalize and hash them.

Forwarded IP headers must only be trusted when the immediate sender is a configured trusted proxy.

## 6. Policy requirements

Policies are declarative versioned data. JSON is the API representation and PostgreSQL persistence format; YAML import/export is optional convenience.

Required lifecycle:

```text
DRAFT -> ACTIVE -> DISABLED -> ARCHIVED
```

Activating an edited policy creates a new immutable version. Requests use only a complete immutable active snapshot.

Policies can match route template, path pattern, method, backend, tenant, client, and required header predicates. Selection is deterministic:

1. highest priority;
2. most specific route;
3. most specific identity;
4. stable policy ID tie-breaker.

Policy activation flow:

1. validate and store the new version in PostgreSQL;
2. commit activation transaction;
3. publish a Redis Pub/Sub invalidation containing policy ID and version only;
4. each gateway fetches authoritative content from PostgreSQL;
5. each gateway validates and compiles a new immutable snapshot;
6. each gateway atomically swaps snapshots;
7. periodic polling reconciles missed Pub/Sub messages.

Gateways continue using the previous valid snapshot until the new snapshot is fully ready. A malformed new snapshot must not partially replace the old one.

## 7. Rate-limit response

Rejected requests return HTTP 429 with a structured JSON body and applicable standard headers:

```json
{
  "status": 429,
  "error": "RATE_LIMIT_EXCEEDED",
  "message": "Request limit exceeded",
  "policy": "client-orders-token-bucket",
  "policyVersion": 3,
  "retryAfterMilliseconds": 720,
  "correlationId": "..."
}
```

Applicable headers include `Retry-After`, `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`, `X-RateLimit-Policy`, and `X-Correlation-Id`.

Never expose Redis keys, raw sensitive identities, stack traces, or internal script details.

## 8. Failure behavior

Redis failure mode must be explicit globally and optionally overridden per policy:

- `FAIL_OPEN`: forward while recording that enforcement was bypassed;
- `FAIL_CLOSED`: reject conservatively.

Never silently switch to per-node local state.

The project must demonstrate and test:

- Redis unavailable or timing out;
- PostgreSQL unavailable;
- backend unavailable;
- policy refresh failure;
- invalid candidate policy;
- gateway restart;
- adding and removing replicas;
- missed Pub/Sub notification and polling recovery;
- clock-skew considerations;
- load balancer redistribution.

## 9. Security constraints

- Public proxy and administrative endpoints must be separated.
- Admin endpoints require authentication, even if local development initially uses a simple mechanism.
- Policy inputs require schema and semantic validation.
- Cap rates, durations, capacities, request cost, queue size, and log size.
- Do not execute arbitrary policy scripts.
- Do not accept unrestricted backend URLs.
- Do not commit secrets.
- Sanitize logs and debug state.

## 10. Testing and development requirements

Every executable codebase follows strict RED-GREEN-REFACTOR.

Each executable codebase independently enforces at least:

- 90% line coverage;
- 90% branch coverage;
- 90% function or method coverage where supported;
- 90% statement coverage where supported.

Coverage does not replace concurrency, property, integration, contract, failure, container, or end-to-end tests. Detailed requirements are authoritative in `docs/quality/TEST_STRATEGY.md`.

## 11. Phased delivery

### Phase 0 — repository and quality foundation

Establish builds, test frameworks, coverage gates, static analysis, CI, container validation, schemas, command documentation, and first ADRs. No product behavior.

### Phase 1 — algorithm domain and in-memory implementations

Define common contracts and invariants. Implement all algorithms with injectable time and concurrency tests.

### Phase 2 — first end-to-end vertical slice

Implement client -> load balancer -> multiple gateways -> fixed-window Redis decision -> one backend. Use a static policy initially.

### Phase 3 — remaining distributed algorithms

Implement and verify one algorithm at a time, starting with token bucket, then sliding counter, sliding log, and finally leaky-bucket policing.

### Phase 4 — policy control plane

Add PostgreSQL versioning, admin API, policy activation, Pub/Sub invalidation, polling reconciliation, and per-replica snapshot visibility.

### Phase 5 — traffic simulator and all mock services

Complete scenarios and experiment reporting.

### Phase 6 — admin portal

Implement portal flows against tested schemas and APIs.

### Phase 7 — observability and failure experiments

Complete dashboards, controlled failures, scaling demonstrations, and documented experiments.

### Phase 8 — optional distributed request queue

Proceed only after approval of delivery-semantics ADR and proof that the policing implementation is complete.

## 12. Explicit non-goals for the initial project

- Kubernetes;
- multi-region globally consistent limiting;
- billing-grade quotas;
- service mesh integration;
- general authorization engine;
- OAuth provider implementation;
- Kafka;
- CQRS or event sourcing;
- machine-learning traffic classification;
- production-complete API gateway functionality.

## 13. Project completion outcomes

A user must be able to:

1. start the platform with Docker Compose;
2. observe requests distributed across at least three gateway replicas;
3. apply one shared client limit regardless of replica;
4. create and activate a policy without restarting gateways;
5. run concurrent traffic scenarios;
6. prove rejected traffic never reaches a backend;
7. restart or scale gateways without resetting distributed state;
8. compare algorithm behavior through metrics and experiment output;
9. run all automated quality gates successfully;
10. inspect documented limitations rather than receiving unsupported production-readiness claims.
