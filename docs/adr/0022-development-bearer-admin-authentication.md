# Protect Phase 4 administration with a development bearer token

**Status:** Accepted

## Context

Admin and internal operations cannot be public, while OAuth, accounts, and role
management are explicitly outside Phase 4.

## Decision

Require one opaque bearer token from secret/environment configuration for every
`/admin/api/v1/**` and `/internal/**` request. Compare in constant time, return
401 for missing or incorrect credentials, associate a configured audit actor,
and never log the token.

## Alternatives considered

Public local endpoints; basic authentication; OAuth/OIDC; client-supplied audit
actors.

## Consequences

Local administration has a small explicit boundary without implying production
identity or authorization.

## Verification

HTTP and log-capture tests cover missing, wrong, correct, non-forwarded, and
non-logged token behavior.

## Known limitations

One shared token provides no roles, user identity, rotation protocol, or
production-grade secret lifecycle.
