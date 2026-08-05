# Policy Model

## 1. Design principles

Policies are versioned declarative data, not executable code. PostgreSQL is authoritative. The gateway evaluates only validated immutable active snapshots.

JSON is the canonical API representation. PostgreSQL persistence is normalized
and typed rather than storing an unvalidated JSON map. A checked-in JSON Schema
defines structural validity; DTO/domain conversion and snapshot compilation
repeat semantic checks.

## 2. Conceptual model

```json
{
  "policyId": "catalog-client-fixed-window",
  "name": "Catalog client fixed window",
  "version": 1,
  "status": "DRAFT",
  "revision": 0,
  "description": "Catalog requests per client and route",
  "priority": 100,
  "match": {
    "routeId": "catalog.items",
    "path": "/proxy/catalog/items",
    "methods": ["GET"]
  },
  "identity": {
    "components": [
      {"type": "HEADER", "name": "X-Client-Id"},
      {"type": "ROUTE"}
    ]
  },
  "algorithm": {
    "type": "FIXED_WINDOW",
    "configuration": {
      "limit": 5,
      "windowMilliseconds": 10000
    }
  },
  "failureMode": "FAIL_CLOSED",
  "createdAt": "2026-08-03T12:00:00Z",
  "createdBy": "local-admin",
  "activatedAt": null,
  "activatedBy": null
}
```

Phase 5 supports only exact normalized `/proxy/**` paths, `GET`, identity
`[HEADER:X-Client-Id, ROUTE]`, and the typed `FIXED_WINDOW | TOKEN_BUCKET`
algorithm union. The persisted
`ROUTE` component compiles to the existing internal `ROUTE_ID` canonical tag
and normalized route ID, preserving Redis identity/key compatibility.

## 3. Relational model

| Table | Responsibility |
| --- | --- |
| `policies` | Stable ID/name, creation audit, highest activated version. |
| `policy_versions` | Lifecycle, exact matcher, algorithm type, failure mode, priority, optimistic revision, lifecycle audit. |
| `policy_version_methods` | Normalized method members. |
| `policy_version_identity_components` | Ordered typed identity members. |
| `fixed_window_configurations` | Typed limit/window subtype for a policy version. |
| `token_bucket_configurations` | Typed capacity/initial/refill-period/cost subtype for a policy version. |
| `policy_set_state` | Singleton authoritative active-set revision. |
| `policy_audit` | Append-only action, actor, correlation, state change, validation outcome. |
| `policy_event_outbox` | Durable publication intent, lease, attempt, retry, and outcome metadata. |

The V1 and V2 Flyway migrations are forward-only. A partial unique index enforces at
most one active version per policy. Check constraints bound values and supported
enums. Triggers reject definition, method, identity, and algorithm-subtype mutations
after first activation, including after disable/archive. Future algorithms use
new typed subtype tables instead of nullable columns or runtime maps.

## 4. Lifecycle and versioning

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

The exact transition table is:

| Current | Operation | Result | Rule |
| --- | --- | --- | --- |
| none | create | DRAFT | Creates the stable row and explicit first version; never activates. |
| DRAFT | update | DRAFT | Full replacement requires matching `If-Match`; revision increments. |
| DRAFT | activate | ACTIVE | Must validate and not be older than the highest activated version. |
| DRAFT | archive | ARCHIVED | Does not change active-set revision or emit an event. |
| ACTIVE | disable | DISABLED | Removes it from the active set and emits `POLICY_DISABLED`. |
| DISABLED | activate | ACTIVE | Only the latest previously activated version may be re-enabled. |
| DISABLED | archive | ARCHIVED | Already inactive, so no active-set event. |
| ARCHIVED | restore | DRAFT | Applies only when the version has never been active. |
| ARCHIVED | restore | DISABLED | Previously active definitions remain immutable. |

Every unlisted transition is rejected with `409 INVALID_POLICY_TRANSITION`.
Active definitions cannot be updated, archived, restored, or reactivated.
Archival requires disable first. Archived versions require explicit restore.
Concurrent activation locks the stable row; monotonic highest-version checking
makes versions 2 and 3 deterministically converge on version 3 regardless of
lock order.

## 5. Validation layers

### Structural validation

The JSON Schema rejects missing fields, unknown fields where appropriate, incorrect types, invalid enums, and out-of-range values.

### Semantic validation

Phase 4 bounds and rules are:

- policy ID is 1..128 UTF-8 bytes; name is 1..128 characters;
- description is optional and at most 1,024 characters;
- version is a positive `long`; optimistic revision is nonnegative;
- route ID uses normalized dotted identifiers and path is one normalized exact
  absolute `/proxy/**` path no longer than 512 UTF-8 bytes;
- methods are exactly `GET` once;
- identity is exactly `HEADER:X-Client-Id` followed by `ROUTE`;
- Fixed Window limit is 1..1,000,000 and window is 1..86,400,000 whole milliseconds;
- Token Bucket capacity/refill/cost are 1..100,000 whole tokens, initial is
  0..capacity, cost is at most capacity, refill period is an exact positive
  integer plus `ms|s|m|h|d` and at most one day, and empty-to-full is at most 30 days;
- failure mode is `FAIL_OPEN` or `FAIL_CLOSED`; priority is 0..1,000;
- duplicate identity/method members, unsupported values, and unknown JSON
  fields are rejected.

### Compilation validation

Before activation or snapshot swap, route matchers, identity extractors, and algorithm configuration are compiled into immutable runtime objects. Any failure aborts the candidate snapshot.

## 6. Deterministic matching

Candidate policies are filtered by enabled status and predicates. Selection order:

1. descending numeric priority;
2. ascending stable policy ID.

Route and identity specificity do not vary in Phase 4 because only one exact
matcher shape is supported. Never rely on database row order, map iteration,
or registration order.

A debug-only match explanation should include:

- candidate policy IDs;
- failed predicates;
- specificity score;
- final tie-break;
- chosen policy/version.

It must not expose secret header values.

## 7. Identity construction

Identity components are normalized into an unambiguous canonical representation before hashing.

Requirements:

- include component type and length boundaries to prevent concatenation collisions;
- normalize header names;
- define whether values are case-sensitive;
- reject missing required identity components or apply an explicit documented fallback;
- use a keyed hash if identities have low entropy and offline guessing is a concern;
- support key rotation without unexpectedly sharing or losing state only through an ADR.

## 8. Activation and distribution

The activation transaction establishes the authoritative version. Pub/Sub is best-effort invalidation.

Notification payload contains only stable metadata such as:

```json
{
  "eventVersion": 1,
  "eventType": "POLICY_ACTIVATED",
  "policyId": "catalog-client-fixed-window",
  "version": 2,
  "policySetRevision": 3,
  "eventId": "11111111-1111-1111-1111-111111111111",
  "occurredAt": "2026-08-03T12:00:00Z"
}
```

A gateway never trusts policy content from the event. It loads from PostgreSQL, validates, compiles, and swaps an entire snapshot.

`POLICY_ACTIVATED` and `POLICY_DISABLED` are supported. Unknown versions/types,
oversized/malformed payloads, duplicates, and older revisions cannot change a
snapshot. Periodic reconciliation compares the lightweight active-set revision
and invokes the same serialized refresh path when it differs.

## 9. Runtime state across versions

Redis keys include policy version by default. This prevents a changed policy from accidentally reusing incompatible state.

Consequences:

- activation starts fresh limiter state unless a specific migration policy is designed;
- old keys expire through TTL;
- changing a policy can temporarily alter client allowance as expected from a fresh version;
- any state carryover mechanism requires a dedicated ADR and migration tests.

## 10. Administrative API contract

The admin API must be described by OpenAPI and tested against it.

Minimum operations:

- create policy;
- create draft version;
- replace draft with `If-Match`;
- activate/disable/archive/restore a version;
- list policies and versions;
- simulate policy matching;
- report per-gateway loaded snapshot metadata.

Optimistic concurrency control is required for draft updates to prevent silent lost updates.

All admin/internal paths require a configured bearer token; missing and wrong
tokens both return 401. This mechanism is development-only and supplies one
configured audit actor, not a client-supplied identity. Match-test reads a
candidate or active snapshot but never invokes Redis runtime state.

## 11. Auditability

Record at least:

- actor identity;
- action;
- policy ID and version;
- timestamp;
- previous and resulting lifecycle state;
- validation outcome;
- request correlation ID.

Do not store secrets or raw API keys in audit records.
