# Test Strategy

## 1. Quality gates per executable codebase

Each executable codebase independently enforces:

- line coverage >= 90%;
- branch coverage >= 90%;
- method/function coverage >= 90% where supported;
- statement coverage >= 90% where supported.

A high aggregate result cannot compensate for an undertested service.

Configuration, Compose, and dashboards use validation and smoke tests rather than artificial unit coverage.

## 2. Java gateway test layers

Recommended tools:

- JUnit 5;
- AssertJ;
- Reactor Test;
- Spring Boot test support;
- Testcontainers for Redis and PostgreSQL;
- JaCoCo;
- ArchUnit;
- Mockito only at clear external seams.

### Unit tests

Cover:

- algorithm invariants;
- policy parsing and semantic validation;
- deterministic matching and tie-breaks;
- identity normalization and hashing;
- decision response mapping;
- failure mode selection;
- route and header handling;
- metrics label bounding;
- serialization and script result decoding.

### Shared algorithm contract tests

Every implementation must satisfy applicable behavior tables for:

- first request;
- exact limit boundary;
- request after limit;
- expiry or refill;
- request cost greater than one;
- invalid configuration;
- no negative remaining capacity;
- no capacity above configured maximum;
- deterministic reset and retry values;
- concurrent calls.

### Property-based tests

Use generated sequences to verify invariants such as:

- token count remains within `[0, capacity]`;
- accepted request cost equals state decrease;
- removing expired log entries cannot increase usage;
- changing request order only affects results where algorithm semantics permit;
- policy matcher always selects the same winner for the same candidate set regardless of input iteration order.

### Redis integration tests

Use a real Redis container. Do not mock Redis when proving:

- Lua atomicity;
- TTL behavior;
- Redis server time;
- sorted-set trimming;
- concurrent requests from independent clients;
- malformed or missing state recovery;
- script loading and fallback execution;
- cluster slot key compatibility if cluster support is in scope.

### PostgreSQL integration tests

Use a real PostgreSQL container to prove:

- policy lifecycle constraints;
- optimistic concurrency;
- immutable active versions;
- activation transaction behavior;
- audit records;
- active generation queries.

### Reactive HTTP tests

Prove:

- rejected requests never execute backend forwarding;
- accepted requests preserve contractually required request data;
- cancellation and timeouts release resources;
- error mapping is stable;
- correlation IDs propagate;
- body size limits are enforced;
- untrusted forwarding headers are ignored.

## 3. Distributed concurrency tests

The core correctness test must launch multiple gateway application instances or independent limiter clients against one Redis instance and one limiter key.

Test shape:

1. initialize one policy and empty state;
2. synchronize many request tasks with a barrier;
3. send requests through multiple independent gateway instances;
4. collect all decisions;
5. assert the documented maximum accepted count;
6. inspect Redis state;
7. repeat enough times to detect races;
8. run under CI with deterministic bounds rather than relying on timing luck.

A test that calls one singleton limiter concurrently does not prove horizontal correctness by itself.

## 4. Policy propagation tests

Prove:

- activation stores a version before publishing notification;
- each gateway swaps only a complete valid snapshot;
- requests in flight may finish under the prior captured snapshot;
- malformed candidates do not replace the prior snapshot;
- a missed Pub/Sub event is repaired by polling;
- all replicas eventually report the active generation;
- no request sees a partially mutated policy collection.

## 5. Python traffic simulator tests

Use pytest, pytest-asyncio, pytest-cov, and Hypothesis where useful.

Cover:

- schema parsing and helpful validation failures;
- all traffic patterns;
- deterministic seeded randomness;
- concurrency limits;
- request scheduling;
- retry and `Retry-After` handling;
- aggregation by client, tenant, route, and result;
- latency percentile calculations;
- cancellation and partial failure;
- fake transport behavior;
- result export.

Unit tests must not depend on external internet access.

## 6. Mock service tests

Each service independently tests:

- normal endpoint behavior;
- service-specific routes and response names;
- delay configuration using controllable time where possible;
- deterministic failure injection;
- fixed error behavior;
- header and correlation propagation;
- request validation;
- health endpoints;
- configuration errors.

Shared mock-service utilities require their own tests.

## 7. Admin portal tests

Use Vitest or Jest, React Testing Library, and MSW.

Cover:

- create and edit draft flows;
- algorithm-specific fields;
- structural and semantic validation display;
- optimistic-concurrency conflict handling;
- activation and propagation status;
- loading, empty, failure, and stale states;
- import/export;
- policy simulation;
- keyboard-accessible major flows;
- sanitized runtime-state displays.

## 8. Contract tests

Schemas are executable specifications.

Required contract suites:

- policy examples validate against policy JSON Schema;
- invalid policies fail with stable error locations;
- admin API responses conform to OpenAPI;
- standardized error responses conform to schema;
- traffic scenarios conform to their schema;
- producer and consumer agree on policy invalidation event metadata;
- Java Redis script result decoder matches script output shape.

## 9. Container and infrastructure validation

CI must run:

- Dockerfile linting;
- YAML parsing;
- `docker compose config`;
- image builds;
- health-check startup;
- one accepted proxy request;
- one rejected proxy request;
- proof that rejected request is absent from backend records;
- clean shutdown;
- optional persistent-volume restart test.

## 10. End-to-end scenarios

### Global limit across replicas

Send one client through a load balancer to at least three replicas. Assert one shared configured limit and multiple observed replica IDs.

### Policy update without restart

Activate a stricter policy version, wait for reported propagation convergence within the configured bound, and verify behavior changes without restarting gateways.

### Redis failure mode

Interrupt Redis and verify explicit fail-open or fail-closed behavior, metrics, logs, and recovery. Assert no local-state fallback.

### Replica restart

Restart one gateway during traffic. Assert runtime limiter state remains and healthy replicas continue.

## 11. Mutation testing and stronger checks

After baseline stability, add mutation testing for high-risk domain and policy-matching packages. Mutation score is informative and may later become a gate through an ADR; it does not replace the initial 90% coverage gates.

## 12. Test reliability rules

- No arbitrary sleep-based unit tests.
- No shared mutable state between tests.
- Use fixed seeds and report them on failure.
- Give integration containers explicit readiness checks.
- Avoid assertions based on nondeterministic request ordering unless order is part of the contract.
- Quarantine is not an acceptable permanent solution for flaky tests.
