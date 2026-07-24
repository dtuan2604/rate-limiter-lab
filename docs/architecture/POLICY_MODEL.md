# Policy Model

## 1. Design principles

Policies are versioned declarative data, not executable code. PostgreSQL is authoritative. The gateway evaluates only validated immutable active snapshots.

JSON is the canonical API and persistence representation. A checked-in JSON Schema defines structural validity. Additional semantic validation handles cross-field rules.

## 2. Conceptual model

```json
{
  "name": "client-orders-token-bucket",
  "description": "Per-client order creation limit",
  "priority": 100,
  "match": {
    "routeIds": ["orders.create"],
    "methods": ["POST"],
    "requiredHeaders": {
      "X-Tenant-Id": "*"
    }
  },
  "identity": {
    "components": [
      {"type": "HEADER", "name": "X-Tenant-Id"},
      {"type": "HEADER", "name": "X-Client-Id"},
      {"type": "ROUTE_ID"}
    ]
  },
  "algorithm": {
    "type": "TOKEN_BUCKET",
    "capacity": 20,
    "refillTokens": 10,
    "refillPeriodMilliseconds": 1000,
    "requestCost": 1
  },
  "failureMode": "FAIL_CLOSED",
  "response": {
    "includeRetryAfter": true
  }
}
```

The implementation may refine field names only through an ADR and schema-first change.

## 3. Lifecycle and versioning

Policy identity and version are separate:

- A policy has stable ID, name, ownership metadata, and history.
- Every edit produces a draft version.
- Activation marks one immutable version active.
- Previous active versions remain queryable.
- Active rows are never mutated in place.
- Archiving does not delete historical audit records.

Required states:

```text
DRAFT
ACTIVE
DISABLED
ARCHIVED
```

Database constraints must prevent ambiguous active versions for a policy where the intended model allows only one.

## 4. Validation layers

### Structural validation

The JSON Schema rejects missing fields, unknown fields where appropriate, incorrect types, invalid enums, and out-of-range values.

### Semantic validation

Examples:

- token refill values and periods must be positive;
- request cost cannot exceed allowed configured bounds;
- queue settings are invalid for algorithms that do not queue;
- source-IP identity requires trusted-proxy configuration to use forwarding headers;
- route IDs must exist;
- header names must be allowed and case-normalized;
- sliding-log maximum entries must be present and bounded;
- failure mode must be explicit;
- unsupported algorithm options are rejected rather than ignored.

### Compilation validation

Before activation or snapshot swap, route matchers, identity extractors, and algorithm configuration are compiled into immutable runtime objects. Any failure aborts the candidate snapshot.

## 5. Deterministic matching

Candidate policies are filtered by enabled status and predicates. Selection order:

1. descending numeric priority;
2. descending route specificity;
3. descending identity specificity;
4. ascending stable policy ID.

Specificity must be calculated by a documented deterministic function and covered by table-driven tests. Never rely on database row order, hash-map iteration order, or registration order.

A debug-only match explanation should include:

- candidate policy IDs;
- failed predicates;
- specificity score;
- final tie-break;
- chosen policy/version.

It must not expose secret header values.

## 6. Identity construction

Identity components are normalized into an unambiguous canonical representation before hashing.

Requirements:

- include component type and length boundaries to prevent concatenation collisions;
- normalize header names;
- define whether values are case-sensitive;
- reject missing required identity components or apply an explicit documented fallback;
- use a keyed hash if identities have low entropy and offline guessing is a concern;
- support key rotation without unexpectedly sharing or losing state only through an ADR.

## 7. Activation and distribution

The activation transaction establishes the authoritative version. Pub/Sub is best-effort invalidation.

Notification payload contains only stable metadata such as:

```json
{
  "eventType": "POLICY_ACTIVATED",
  "policyId": "...",
  "version": 3,
  "policySetGeneration": 18
}
```

A gateway never trusts policy content from the event. It loads from PostgreSQL, validates, compiles, and swaps an entire snapshot.

Periodic reconciliation compares generation and active version metadata. Reconciliation interval and jitter must be configurable and tested with fake time where possible.

## 8. Runtime state across versions

Redis keys include policy version by default. This prevents a changed policy from accidentally reusing incompatible state.

Consequences:

- activation starts fresh limiter state unless a specific migration policy is designed;
- old keys expire through TTL;
- changing a policy can temporarily alter client allowance as expected from a fresh version;
- any state carryover mechanism requires a dedicated ADR and migration tests.

## 9. Administrative API contract

The admin API must be described by OpenAPI and tested against it.

Minimum operations:

- create policy;
- create draft version;
- validate draft;
- activate draft;
- disable policy;
- archive policy;
- list policies and versions;
- retrieve active policy set generation;
- simulate policy matching;
- report gateway propagation status.

Optimistic concurrency control is required for draft updates to prevent silent lost updates.

## 10. Auditability

Record at least:

- actor identity;
- action;
- policy ID and version;
- timestamp;
- previous and resulting lifecycle state;
- validation outcome;
- request correlation ID.

Do not store secrets or raw API keys in audit records.
