# Mock Services

Phase 2 implements the catalog vertical slice:

- `GET /health`
- `GET /catalog/items`

`CATALOG_DELAY_MILLISECONDS` configures a bounded delay from 0 through 5000
milliseconds. `CATALOG_TEST_ENDPOINTS_ENABLED=true` adds development-only
request-count read and reset endpoints used by the end-to-end acceptance test.

Orders and payments remain Phase 0 process-health foundations and are not
started by the Phase 2 Compose environment.
