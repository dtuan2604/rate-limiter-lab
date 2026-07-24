# Rate Limiter Lab

This repository is in Phase 0: the build, test, coverage, contract, container, and CI foundations are implemented, but no rate-limiting product behavior exists yet.

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

See `docs/COMMANDS.md` for the verified focused commands and `docs/requirements/PROJECT_SPEC.md` for later product phases.
