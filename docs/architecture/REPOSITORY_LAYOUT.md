# Intended Repository Layout

```text
rate-limiter-lab/
├── AGENTS.md
├── README.md
├── compose.yaml
├── gradle/
├── settings.gradle.kts
├── build.gradle.kts
├── .agents/
│   └── skills/
│       ├── tdd-vertical-slice/
│       │   └── SKILL.md
│       └── distributed-rate-limiter/
│           └── SKILL.md
├── docs/
│   ├── index.md
│   ├── requirements/
│   ├── architecture/
│   ├── quality/
│   ├── adr/
│   ├── exec-plans/
│   │   ├── active/
│   │   └── completed/
│   ├── experiments/
│   ├── PLANS.md
│   ├── COMMANDS.md
│   └── CODEX_PROMPTS.md
├── contracts/
│   ├── policy.schema.json
│   ├── traffic-scenario.schema.json
│   ├── admin-api.openapi.yaml
│   └── error.schema.json
├── gateway/
│   ├── AGENTS.md
│   ├── Dockerfile
│   ├── build.gradle.kts
│   └── src/
├── admin-portal/
│   ├── AGENTS.md
│   ├── Dockerfile
│   └── src/
├── traffic-simulator/
│   ├── AGENTS.md
│   ├── Dockerfile
│   ├── pyproject.toml
│   ├── scenarios/
│   └── src/
├── mock-services/
│   ├── AGENTS.md
│   ├── shared/
│   ├── catalog/
│   ├── orders/
│   └── payments/
├── monitoring/
│   ├── prometheus.yml
│   └── grafana/
├── load-balancer/
│   └── configuration/
└── scripts/
    ├── verify.sh
    ├── run-demo.sh
    ├── seed-policies.sh
    └── run-experiments.sh
```

## Module boundaries

The Java gateway should preserve internal modules or packages for:

```text
domain
  limiter contracts and algorithm-independent value objects

application
  policy evaluation and request decision orchestration

adapters.in.http
  proxy and admin HTTP boundaries

adapters.out.redis
  Redis scripts, execution, and result translation

adapters.out.postgres
  policy persistence and activation

proxy
  backend routing and forwarding

observability
  bounded metrics and structured event definitions
```

Domain packages must not import Spring WebFlux, Redis clients, JPA/R2DBC repositories, or HTTP request classes.

## Mock service isolation

Catalog, orders, and payments must each be independently startable and independently testable. Shared behavior may live in a small tested shared package, but service tests must still prove service-specific routing, configuration, and responses.

## Generated files

Generated OpenAPI clients or schema code must be placed in clearly marked generated directories and excluded from hand editing. Coverage exclusions for generated files must be narrow and documented.
