# Use Spring Boot WebFlux for the gateway

**Status:** Accepted

## Context

The gateway must handle many concurrent proxied requests and must make non-blocking Redis and backend calls.

## Decision

Use Java 21 and Spring Boot WebFlux. Keep domain limiter logic framework-independent and prohibit blocking work on event-loop threads.

## Alternatives considered

Spring MVC; a prebuilt gateway limiter that hides algorithm mechanics; a non-Java gateway.

## Consequences

Reactive cancellation, backpressure, body limits, and scheduler boundaries require explicit tests. Codex must verify selected client libraries are genuinely non-blocking.

## Verification

Implementation must be linked to executable tests and exact commands in the applicable ExecPlan.

## Known limitations

This ADR records the architectural direction, not proof that the implementation already exists or is production-ready.
