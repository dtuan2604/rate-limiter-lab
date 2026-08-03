# Rate Limiter Lab

Phase 3 provides a distributed fixed-window vertical slice:

```text
GET /proxy/catalog/items
  -> HAProxy round-robin load balancer
  -> one of three stateless Spring WebFlux gateways
  -> static client/route policy
  -> atomic Redis fixed-window limiter using Redis TIME
  -> FastAPI catalog backend
```

The configured simulation policy allows five requests per client in each
epoch-aligned ten-second window across all replicas combined. Redis is the
authoritative runtime state in distributed mode. Explicit `IN_MEMORY` mode is
retained for single-instance education and comparison, never as fallback.

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

Start HAProxy, three gateways, Redis, and catalog:

```bash
docker compose up --build
```

Then call the proxy with a trusted local simulation identity:

```bash
curl --header 'X-Client-Id: demo-client' \
  http://localhost:8080/proxy/catalog/items
```

Stop and remove the local environment:

```bash
docker compose down --volumes --remove-orphans
```

Run the distributed and Redis-failure acceptance proofs:

```bash
scripts/phase3-e2e.sh
scripts/phase3-redis-failure-e2e.sh
```

See `docs/COMMANDS.md` for verified commands and the active Phase 3 ExecPlan
for design, TDD, coverage, concurrency, and container evidence.
