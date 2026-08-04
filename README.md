# Rate Limiter Lab

Phase 4 provides a PostgreSQL-backed policy control plane and a distributed
fixed-window data plane:

```text
Admin client -> authenticated gateway admin API -> PostgreSQL -> durable outbox
                                                           -> Redis Pub/Sub
                                                           -> all gateways

GET /proxy/catalog/items -> HAProxy -> gateway snapshot -> Redis Lua -> catalog
```

PostgreSQL is authoritative for policy identity, versions, lifecycle, audit,
and active-set revision. Redis is authoritative for fixed-window counters and
expiration; Pub/Sub carries invalidation metadata only. Each gateway serves
requests from one atomically replaceable immutable snapshot and never queries
PostgreSQL on the proxy request path.

## Prerequisites

- Java 21;
- Node.js 24.10.0 and npm 11.6.0;
- the Conda environment `rate-limiter` using Python 3.12.13;
- Docker Engine 27+ with Docker Compose 2.32+;
- `curl` and `jq`.

Install the pinned Python dependencies and local packages:

```bash
conda run -n rate-limiter python -m pip install --requirement requirements-dev.lock
conda run -n rate-limiter python -m pip install --no-deps --editable traffic-simulator --editable mock-services
```

Install the frontend dependencies:

```bash
npm --prefix admin-portal ci
```

Run the complete local CI-equivalent workflow:

```bash
scripts/verify.sh
```

Generate local-only credentials and start HAProxy, three gateways, Redis,
PostgreSQL, and the catalog service:

```bash
export ADMIN_BEARER_TOKEN="$(openssl rand -hex 32)"
export POSTGRES_PASSWORD="$(openssl rand -hex 32)"
docker compose up --build
```

No admin or PostgreSQL password has a source-controlled default. Flyway applies
the forward-only schema on gateway startup. An empty active policy set is valid,
so create the catalog draft and activate version 1 explicitly:

```bash
scripts/bootstrap-catalog-policy.sh
```

Then call the proxy with a trusted local simulation identity:

```bash
curl --header 'X-Client-Id: demo-client' \
  http://localhost:8080/proxy/catalog/items
```

Inspect one replica's sanitized loaded revision with the development token:

```bash
curl --header "Authorization: Bearer ${ADMIN_BEARER_TOKEN}" \
  http://localhost:8081/internal/policy-snapshot
```

The admin API is under `/admin/api/v1/policies`; its exact strict request and
response contract is in `contracts/admin-api.openapi.yaml`. The shared bearer
token is deliberately development-only: it has no user identities, roles,
rotation protocol, or production secret-management guarantees.

Stop and remove the local environment:

```bash
docker compose down --volumes --remove-orphans
```

Run the retained and Phase 4 acceptance proofs:

```bash
scripts/phase3-e2e.sh
scripts/phase3-redis-failure-e2e.sh
scripts/phase4-e2e.sh
scripts/phase4-publication-failure-e2e.sh
```

The Phase 4 suites prove dynamic activation without gateway restart, convergence
across three replicas, version-isolated Redis keys, missed-Pub/Sub recovery,
startup reload, invalid-activation safety, and publication-failure recovery.
See `docs/COMMANDS.md` and the completed Phase 4 ExecPlan for exact evidence.
