# Rate Limiter Lab

Phase 2 provides one locally runnable vertical slice:

```text
GET /proxy/catalog/items
  -> Spring WebFlux gateway
  -> static client/route policy
  -> in-memory fixed-window limiter
  -> FastAPI catalog backend
```

The configured simulation policy allows five requests per client in each
epoch-aligned ten-second window. This phase deliberately uses one gateway and
process-local state; it does not claim distributed or multi-replica
enforcement.

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

Start the gateway and catalog:

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

Run the real five-allowed/one-rejected acceptance proof:

```bash
scripts/phase2-e2e.sh
```

See `docs/COMMANDS.md` for verified focused commands and the completed Phase 2
ExecPlan for design, TDD, coverage, and container evidence.
