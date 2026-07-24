# Phase 0 ExecPlan — Repository and Quality Foundation

**Status:** Complete
**Owner:** Codex, supervised by repository owner  
**Last updated:** 2026-07-24

## Purpose

Create a clean-checkout development foundation in which each executable codebase can be built, tested, statically checked, and held to an independent 90% line and branch coverage gate. A developer will be able to validate the contracts, build health-checkable skeleton containers, start and stop the Phase 0 Compose environment, and run the same verification commands locally that CI runs.

This phase intentionally implements no rate-limiting, proxying, policy persistence, administration, or traffic-generation product behavior.

## Scope and non-scope

Included:

- pin and verify the Java 21, Gradle, Python, Node.js, npm, framework, test, lint, and build toolchains;
- establish the Gradle root and Java gateway project;
- establish independently testable Python traffic-simulator and mock-service packages;
- establish a strict React/TypeScript admin-portal package;
- configure formatting, linting, static analysis, type checking, tests, and independent 90% coverage gates;
- add non-inventive initial JSON Schema and OpenAPI documents plus executable validators;
- add secure, health-checkable skeleton applications and Dockerfiles only where a Phase 0 test requires them;
- add a Compose foundation for the documented components without implementing product request paths;
- add CI that invokes the same root scripts used locally;
- populate `docs/COMMANDS.md` only with commands actually executed;
- verify the root and nested `AGENTS.md` files and repository skills are discoverable.

Excluded:

- limiter algorithms or limiter decision contracts;
- Redis keys, scripts, state transitions, or failure-mode behavior;
- proxy forwarding and rejected-request handling;
- PostgreSQL policy persistence or activation;
- admin operations, authentication flows, or API endpoint paths;
- traffic scenario execution or scheduling;
- mock-service failure injection and business routes;
- metrics, dashboards, seeded policies, and end-to-end accepted/rejected request proofs, which require product behavior from later phases.

## Current repository state

Inspection on 2026-07-24 found:

- Git `HEAD` is `93da665` on `main`; the worktree was clean before Phase 0 edits.
- The tracked tree contains `.gitignore` and documentation only. The component directories exist but contain only ignored `AGENTS.md` files.
- No Gradle settings, wrapper, Java sources, Python packages, frontend package, contracts, scripts, Compose file, Dockerfiles, monitoring configuration, or CI workflow exists.
- The root `.gitignore` intentionally ignores `.agents/` and every `AGENTS.md`; those operating files are present locally but are not part of a clean checkout.
- Local tools observed: Microsoft OpenJDK 21.0.8, default OpenJDK 25, Node.js 24.10.0, npm 11.6.0, Python 3.12.13 in the required Conda environment `rate-limiter`, Docker CLI 27.5.1, and Docker Compose 2.32.4.
- No system Gradle installation exists, so the repository must carry the Gradle wrapper.
- Docker Desktop was not running during the audit; container validation remains pending until it is started.
- The existing `docs/COMMANDS.md` and this plan contained placeholders rather than verified commands.

### Documentation consistency review

No contradiction requires product-owner approval:

- Redis is authoritative only in distributed mode; the explicitly educational in-memory mode does not conflict with that rule.
- The initial leaky-bucket implementation is a policing meter, consistently deferred from true distributed queueing by ADR 0010.
- The system architecture and intended package layout place public and future admin HTTP adapters in the Java gateway codebase while still requiring endpoint separation.
- Phase 0 health endpoints are permitted by the original plan but cannot be represented as completed proxy, admin, traffic, or backend product behavior.

Missing decisions are resolved conservatively:

- Exact traffic-scenario field names are not approved. Phase 0 will create a valid empty-object schema that rejects invented fields; Phase 5 must expand it schema-first.
- Exact admin endpoint paths are not approved. Phase 0 will create a valid OpenAPI document with no paths and only approved shared error components; Phase 4 must add paths schema-first.
- Only the token-bucket field names shown in `POLICY_MODEL.md` are approved. The Phase 0 policy schema will cover the approved common envelope and token-bucket shape only; other algorithms remain invalid until their Phase 1/3 contract work.
- Numeric upper bounds are required eventually but not specified. Phase 0 will enforce positive lower bounds where approved and will not invent product maxima.
- Authentication mechanism, backend route schema, source-IP fallback, header allowlist, and identity-hash rotation remain later product decisions and are not encoded in Phase 0 behavior.

## Proposed design

### Toolchains and package managers

- Java: Microsoft OpenJDK 21.0.8 locally, with Java language toolchain 21.
- Gradle: wrapper 8.14.3, selected because Spring Boot 3.5 supports Gradle 8.4+ and it runs on Java 21.
- Spring Boot: 3.5.16 with WebFlux and Actuator; Phase 0 exposes Actuator health only.
- Java checks: JUnit 5 through Spring Boot test support, JaCoCo, Checkstyle, and Spotless.
- Python: CPython 3.12.13, always invoked through `conda run -n rate-limiter`; pip and exact direct dependency pins are used because the repository contract mandates the existing Conda environment.
- Python checks: pytest, pytest-cov with branch coverage, Ruff formatting/linting, strict mypy, and `python -m build`.
- Frontend: Node.js 24.10.0, npm 11.6.0, React 19.2.8, strict TypeScript 6.0.3, Vite 8.1.5, Vitest 4.1.10, React Testing Library, ESLint, and Prettier. `package-lock.json` is authoritative.
- CI: GitHub Actions, matching the GitHub origin and using least-privilege read-only repository permissions.

All new production and build dependencies are version-pinned. They exist only to satisfy a documented Phase 0 runtime or verification boundary.

### Module boundaries

- `gateway/`: one Spring Boot WebFlux application. Only the framework entry point and Actuator health exist in Phase 0.
- `traffic-simulator/`: one typed Python distribution with a minimal package/version boundary but no traffic execution.
- `mock-services/`: one Python distribution containing separately startable `catalog`, `orders`, and `payments` FastAPI applications. Each service has its own test and coverage command.
- `admin-portal/`: one Vite/React application that identifies itself as a Phase 0 foundation and has no admin workflow.
- `contracts/`: schemas, approved examples, invalid fixtures, and Python-based contract tests.
- `scripts/`: thin orchestration over module-native commands.
- `compose.yaml`: health-checkable Phase 0 services and infrastructure. It must not expose a fake proxy or admin API.

### Failure behavior

- Verification scripts stop on the first failed command and never convert failures to success.
- Missing tools or an inactive Docker daemon produce explicit nonzero results.
- Skeleton health endpoints report process health only; they do not claim Redis, PostgreSQL, policy, proxy, or backend readiness.
- No service silently substitutes local state for Redis because no limiter state exists in this phase.

## Invariants

- A coverage failure in one executable codebase fails even if all other codebases have full coverage.
- Local verification and CI call the same underlying scripts.
- The default Java compiler and runtime used by repository commands are Java 21.
- Every Python command runs through Conda environment `rate-limiter`.
- Final code contains no temporary under-covered gate fixture.
- Skeleton applications expose no rate-limiting, forwarding, policy mutation, traffic generation, or mock business behavior.
- Contracts reject unknown fields and do not invent unapproved product shapes.
- Containers run as non-root where practical and contain no credentials.
- A command appears in `docs/COMMANDS.md` only after it has been executed and its result inspected.

## Milestone 1 — Toolchain decisions and buildable skeletons

Observable behavior: a repository-structure check discovers all intended modules, and each codebase runs one meaningful foundation test.

Files expected to change:

- `.gitignore`, `.java-version`, `.node-version`, `.nvmrc`, `.python-version`;
- `settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `gradlew`, `gradlew.bat`;
- `gateway/build.gradle.kts`, `gateway/config/checkstyle/checkstyle.xml`, gateway application and test sources;
- `traffic-simulator/pyproject.toml`, package and test sources;
- `mock-services/pyproject.toml`, package and test sources;
- `admin-portal/package.json`, lockfile, TypeScript/Vite/test/lint/format configuration, application and test sources;
- `requirements-dev.lock`;
- `scripts/check-repository-structure.sh`.

RED:

- Add `scripts/check-repository-structure.sh` first and run it against the documentation-only repository.
- Expected failure: required pinned toolchain, build, package, and source files are absent.

GREEN:

- Add only the buildable skeletons and framework health boundaries required by tests.
- Install pinned dependencies and generate the Gradle/npm lock artifacts.

REFACTOR:

- Centralize repeated versions/configuration without merging independent coverage boundaries.

Focused commands:

- `scripts/check-repository-structure.sh`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test`
- `conda run -n rate-limiter python -m pytest traffic-simulator/tests`
- separate mock-service pytest commands;
- `npm --prefix admin-portal test -- --run`

Broader command: `scripts/test.sh`.

Success: each codebase compiles or imports and its foundation behavior test passes.

## Milestone 2 — Independent coverage gates

Observable behavior: each executable codebase independently rejects under-coverage and passes at or above 90% line and branch coverage.

Files expected to change:

- `gateway/build.gradle.kts`;
- Python `pyproject.toml` coverage settings;
- `admin-portal/vite.config.ts`;
- `scripts/coverage.sh`;
- temporary gate fixtures that must be removed before completion.

RED:

- For Java, traffic simulator, each mock service, and admin portal, introduce one temporary two-branch function while testing only one branch.
- Expected failure: that codebase’s native coverage command exits nonzero below 90%.

GREEN:

- Add the missing branch test and confirm each gate passes.

REFACTOR:

- Remove the temporary gate fixture and its tests, retain only meaningful foundation behavior, and rerun each gate.

Focused commands: native coverage commands documented after execution.

Broader command: `scripts/coverage.sh`.

Success: failure and recovery are observed separately for gateway, traffic simulator, catalog, orders, payments, and admin portal.

## Milestone 3 — Formatting, linting, and static analysis

Observable behavior: controlled violations are detected and the clean tree passes all checks.

Files expected to change:

- Gradle Spotless/Checkstyle configuration;
- Python Ruff/mypy configuration;
- frontend ESLint/Prettier/TypeScript configuration;
- `scripts/format.sh`, `scripts/static-checks.sh`.

RED:

- Add one controlled formatting or static violation per language and run the applicable checker.
- Expected failure: Java formatting/checkstyle, Python Ruff or strict mypy, and frontend Prettier/ESLint/typecheck each report the intended violation.

GREEN:

- Correct each violation using the configured formatter or minimum type-safe change.

REFACTOR:

- Remove controlled fixtures and consolidate root orchestration.

Focused commands: module-native format, lint, and typecheck commands.

Broader commands: `scripts/format.sh` and `scripts/static-checks.sh`.

Success: both root commands return zero and a reintroduced violation returns nonzero.

## Milestone 4 — Contracts and executable validation

Observable behavior: approved examples validate, and invalid fixtures fail at asserted JSON paths or OpenAPI validation boundaries.

Files expected to change:

- `contracts/policy.schema.json`;
- `contracts/traffic-scenario.schema.json`;
- `contracts/error.schema.json`;
- `contracts/admin-api.openapi.yaml`;
- `contracts/examples/`, `contracts/tests/`;
- `scripts/validate-contracts.sh`.

RED:

- Write contract tests that reference absent schemas and assert invalid fixture locations.
- Expected failure: schema files are missing.

GREEN:

- Add the smallest non-inventive schemas described in the consistency review.

REFACTOR:

- Share validator helpers while retaining clear contract-specific assertions.

Focused command: `conda run -n rate-limiter python -m pytest contracts/tests`.

Broader command: `scripts/validate-contracts.sh`.

Success: approved examples pass; invalid examples fail at stable paths; OpenAPI validation passes with no invented paths.

## Milestone 5 — Container and Compose validation

Observable behavior: Phase 0 images build, all long-running skeleton services become healthy, the traffic simulator one-shot container exits successfully without generating traffic, and the environment shuts down cleanly.

Files expected to change:

- service Dockerfiles and `.dockerignore` files;
- `compose.yaml`;
- `load-balancer/nginx.conf`;
- `monitoring/prometheus.yml`;
- minimal Grafana provisioning;
- `scripts/lint-dockerfiles.sh`, `scripts/container-smoke.sh`.

RED:

- Run Compose configuration and Dockerfile lint checks before files exist.
- Expected failure: missing Compose and Dockerfile inputs.

GREEN:

- Add version-pinned, non-root, health-checkable container definitions without product routes.

REFACTOR:

- Remove duplicated Docker build steps and centralize health timing in the smoke script.

Focused commands:

- `docker compose config`
- `scripts/lint-dockerfiles.sh`
- `docker compose build`
- `scripts/container-smoke.sh`

Broader command: `docker compose down --volumes --remove-orphans`.

Success: configuration, lint, builds, health checks, one-shot exit, and clean shutdown are observed.

## Milestone 6 — CI, documentation, and clean-state proof

Observable behavior: one local command executes the same gates as CI, and a clean checkout needs no undocumented repository knowledge.

Files expected to change:

- `.github/workflows/ci.yml`;
- `scripts/verify.sh`;
- `docs/COMMANDS.md`;
- this ExecPlan.

RED:

- Add CI jobs that call the not-yet-complete root verification commands.
- Expected failure: missing or incomplete root commands from earlier milestones.

GREEN:

- Wire the verified root scripts into CI with read-only permissions and pinned action revisions/tags.

REFACTOR:

- Remove duplicate command bodies from CI and keep documentation aligned with scripts.

Focused command: syntax/structure validation of the workflow and each root script.

Broader command: `scripts/verify.sh`.

Success: the full local verification command passes, exact results are documented, and this plan is moved to `docs/exec-plans/completed/`.

## Contract and schema changes

Phase 0 creates:

- `policy.schema.json`: approved policy common fields plus the documented token-bucket shape only;
- `traffic-scenario.schema.json`: an empty-object v0 scaffold that rejects unapproved fields;
- `error.schema.json`: the documented 429 response fields, with no internal details;
- `admin-api.openapi.yaml`: OpenAPI metadata, empty `paths`, and the shared error schema component.

These are version-zero foundations. Expanding them is a contract-first behavior change in later phases.

## Data and migration considerations

No PostgreSQL schema, migration, Redis key, or runtime-state format is created in Phase 0. Compose may start empty PostgreSQL and Redis processes to prove infrastructure wiring only. There is therefore no data migration, state carryover, or rollback behavior to claim.

## Security and failure analysis

- GitHub Actions permissions are read-only.
- No credentials or default application secrets are committed; local infrastructure uses explicit development-only values isolated to Compose.
- Images use versioned bases and non-root runtime users where the image supports it.
- Health endpoints are bounded and reveal only service identity and health.
- Admin paths do not exist, so Phase 0 makes no authentication claim.
- Contracts disallow unknown fields and avoid unrestricted URLs or executable expressions.
- Verification fails closed on missing tools, inactive infrastructure, invalid schemas, and insufficient coverage.

## Validation plan

Commands become authoritative only after successful execution and recording in `docs/COMMANDS.md`.

Required groups:

- `scripts/format.sh`
- `scripts/static-checks.sh`
- `scripts/test.sh`
- `scripts/coverage.sh`
- `scripts/validate-contracts.sh`
- Java gateway build and JaCoCo verification;
- Python package builds, strict mypy, Ruff, pytest branch coverage for traffic simulator and each mock service;
- frontend Prettier, ESLint, strict TypeScript, Vitest coverage, and production build;
- Dockerfile lint, `docker compose config`, image builds, health smoke, and clean shutdown;
- `scripts/verify.sh`.

## Progress log

- 2026-07-24 — Activated Phase 0 from the owner’s explicit execution instruction. Read the root contract, `tdd-vertical-slice` skill, all required product/architecture/quality documents, `docs/PLANS.md`, every ADR, and every nested component `AGENTS.md`.
- 2026-07-24 — Audited the tracked and working trees. Confirmed documentation-only tracked state, clean worktree, absent build/package/container files, ignored operating instructions, and no existing product code.
- 2026-07-24 — Verified local tool versions and the presence of JDK 21.0.8. Confirmed system Gradle is absent and Docker daemon is not running.
- 2026-07-24 — Verified current frontend and Python package versions through npm and PyPI registry queries after the sandboxed npm lookup failed with expected DNS restriction and the approved network retry succeeded.
- 2026-07-24 — Replaced the placeholder plan with this self-contained active plan before implementation.
- 2026-07-24 — Milestone 1 RED: added `scripts/check-repository-structure.sh`; it exited 1 and listed the 16 absent toolchain, build, package, and source files expected from the documentation-only scaffold.
- 2026-07-24 — Dependency discovery: npm rejected TypeScript 7.0.2 because `typescript-eslint` 8.65.0 supports TypeScript below 6.1.0. No peer override was used; TypeScript was repinned to the registry-verified compatible 6.0.3.
- 2026-07-24 — Milestone 1 GREEN: the structure check passed; Gradle 8.14.3 ran on Java 21.0.8; gateway, traffic simulator, catalog, orders, payments, and admin portal each passed one foundation behavior test.
- 2026-07-24 — Milestone 1 REFACTOR: replaced deprecated synchronous FastAPI test clients with HTTPX ASGI transports, removed the warning, and added `scripts/test.sh`; the complete suite passed (six tests across six independent boundaries).
- 2026-07-24 — Milestone 2 RED: absent-probe tests failed in all six boundaries; then Java, traffic simulator, and portal coverage gates failed below 90%. Coverage.py did not count Python conditional expressions as branches, so mock probes were changed to explicit `if` statements and catalog, orders, and payments then failed at 81.82%.
- 2026-07-24 — Milestone 2 GREEN: missing-branch tests raised every temporary gate to 100%. The traffic command required explicit `--cov-config=traffic-simulator/pyproject.toml` when launched from repository root so the narrowly documented `__main__` entry-point exclusion is honored.
- 2026-07-24 — Milestone 2 REFACTOR: removed every temporary probe and added `scripts/coverage.sh`; the final gateway gate passed and traffic simulator, catalog, orders, payments, and portal each reported 100%.
- 2026-07-24 — Milestone 3 RED: the initial scaffold failed Java Spotless, Python Ruff format/lint, frontend Prettier, frontend typecheck, and frontend ESLint configuration. Controlled probes then proved Checkstyle, strict mypy, and ESLint each return nonzero for a specific violation.
- 2026-07-24 — Milestone 3 GREEN/REFACTOR: applied configured formatters, narrowed Ruff from impractical `ALL` to the documented quality/security rule families, fixed typed ESLint scope and Vitest type loading, removed all probes, and added root format/static scripts. Both root scripts passed.
- 2026-07-24 — Milestone 4 RED: seven contract tests were added before schemas; the focused suite exited 1 at the first missing `policy.schema.json`.
- 2026-07-24 — Milestone 4 GREEN: added the approved token-bucket policy schema, empty-object traffic v0 schema, documented 429 error schema, and path-empty OpenAPI scaffold. Six JSON Schema tests passed immediately; OpenAPI validation exposed incorrect relative-reference base handling.
- 2026-07-24 — Milestone 4 REFACTOR: switched from the deprecated OpenAPI shortcut to `validate`, supplied the contract-directory base URI, added `scripts/validate-contracts.sh`, and integrated contracts into root tests and static checks. Seven contract tests and all expanded quality checks passed.
- 2026-07-24 — Milestone 5 RED: `docker compose config` exited 14 because no Compose file existed; Dockerfile lint exited 1 and named all four absent Dockerfiles; the pre-implementation smoke script exited 14 on the same missing Compose boundary.
- 2026-07-24 — Milestone 5 GREEN: Compose validated, Hadolint passed, and six custom images built. The first full smoke brought Redis, PostgreSQL, Prometheus, Grafana, three mock services, and all three gateway replicas healthy, but correctly failed because the admin Nginx health check resolved `localhost` to unbound IPv6.
- 2026-07-24 — Milestone 5 REFACTOR: changed only Nginx health probes to `127.0.0.1`, pinned npm inside the build image, added a transitive mock-service production lock, suppressed intentional container-build installer warnings, and reran lint/config/smoke. Every service became healthy, three gateway replicas were counted, seven host health requests passed, the traffic simulator exited with its version without generating traffic, clean shutdown passed, and the test volume was removed.
- 2026-07-24 — Milestone 6 RED: the CI structure check exited 1 and named the absent workflow, artifact-build script, and complete verification script.
- 2026-07-24 — Milestone 6 GREEN: added a read-only GitHub Actions workflow that calls the local verification entry point, added isolated application artifact builds, replaced every command-document placeholder with inspected commands, and observed the CI structure check and application build return zero.
- 2026-07-24 — Milestone 6 REFACTOR: added missing Python package READMEs to remove build metadata warnings, made the profile-gated traffic image build explicit in a clean container smoke, and reran the complete CI-equivalent command. Structure, CI parity, formatting, static analysis, tests, independent coverage, contracts, application artifacts, Dockerfile lint, Compose health, three gateway replicas, seven host health endpoints, and the no-traffic one-shot command all passed. The environment shut down and the disposable PostgreSQL volume was removed.

## Command and evidence log

Commands executed from repository root unless noted:

1. `pwd && rg --files -g 'AGENTS.md' -g 'SKILL.md' -g 'docs/**' -g '!**/.git/**' | sort`
   - Result: exit 0; located the root/nested instructions, both repository skills, required documents, ADRs, and active Phase 0 plan.
2. `wc -l ...`
   - Result: exit 0; confirmed the complete reading set (2,425 lines across the selected files).
3. `sed -n ... AGENTS.md ... tdd-vertical-slice/SKILL.md ... PHASE-0-FOUNDATION.md ... PLANS.md`
   - Result: exit 0; operating, skill, active-plan, and plan-format instructions read completely.
4. `sed -n ... PROJECT_SPEC.md ... SYSTEM_ARCHITECTURE.md ... POLICY_MODEL.md`
   - Result: exit 0; Phase 0 scope, system boundaries, and policy constraints read completely.
5. `sed -n ... ENGINEERING_STANDARDS.md ... TEST_STRATEGY.md ... DEFINITION_OF_DONE.md ... REPOSITORY_LAYOUT.md ... COMMANDS.md`
   - Result: exit 0; quality gates, completion criteria, intended layout, and command placeholders read completely.
6. `for file in docs/adr/README.md docs/adr/0*.md gateway/AGENTS.md admin-portal/AGENTS.md traffic-simulator/AGENTS.md mock-services/AGENTS.md; do ...; done`
   - Result: exit 0; all ADRs and nested component rules read completely.
7. `git status --short && rg --files --hidden -g '!.git/**' | sort && git log -5 --oneline --decorate`
   - Result: exit 0; clean documentation-only tracked scaffold at `93da665`.
8. `sed -n ... .gitignore ... docs/index.md ... docs/CODEX_PROMPTS.md && find ... && java -version && node --version && npm --version && docker --version && docker compose version && conda run -n rate-limiter python --version && conda run -n rate-limiter pip --version && gradle --version`
   - Result: exit 127 only at the final `gradle` lookup; all preceding reads/version checks succeeded and established that Gradle is absent.
9. `/usr/libexec/java_home -V ...; corepack --version; pnpm --version; conda run -n rate-limiter python -m pip list --format=freeze; git remote -v; git ls-tree -r --name-only HEAD; docker info ...`
   - Result: compound exit 1 because Docker daemon was unavailable; confirmed JDK 21.0.8, pnpm 10.30.0, minimal Conda environment, GitHub origin, tracked tree, and inactive Docker daemon.
10. `command -v ...; <JDK21>/bin/java -version`
    - Result: exit 0; `uv`, `hadolint`, and `shellcheck` absent; JDK 21.0.8 verified directly.
11. Sandboxed `npm view ...`
    - Result: interrupted after DNS `ENOTFOUND`; expected sandbox network restriction, not a dependency failure.
12. Approved `npm view ...` queries.
    - Result: exit 0; verified exact frontend versions recorded in the decision log.
13. Approved `npm view ... && conda run -n rate-limiter python -m pip index versions ...`.
    - Result: exit 0; verified remaining frontend and Python versions are available.
14. `chmod +x scripts/check-repository-structure.sh && scripts/check-repository-structure.sh`
    - Result: exit 1 as expected for Milestone 1 RED; all 16 required skeleton files were reported missing.
15. `mktemp -d /tmp/rate-limiter-gradle.XXXXXX`
    - Result: exit 0; created isolated temporary download directory `/tmp/rate-limiter-gradle.VSBNpm`.
16. Approved `curl --fail --location --output /tmp/rate-limiter-gradle.VSBNpm/gradle-8.14.3-bin.zip https://services.gradle.org/distributions/gradle-8.14.3-bin.zip`.
    - Result: exit 0; downloaded the official 131 MiB distribution.
17. Approved checksum download followed by `shasum -a 256 -c`.
    - Result: compound exit 1 because Gradle publishes a bare hash rather than `shasum -c` format; the download itself succeeded.
18. `cd /tmp/rate-limiter-gradle.VSBNpm && shasum -a 256 gradle-8.14.3-bin.zip && sed -n '1p' gradle-8.14.3-bin.zip.sha256`
    - Result: exit 0; computed and published SHA-256 both equal `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`.
19. Approved `unzip ... && JAVA_HOME=<JDK21> /tmp/.../gradle wrapper --gradle-version 8.14.3 --distribution-type bin`.
    - Result: exit 0; wrapper generated successfully with Gradle 8.14.3 on Java 21.
20. `sed -n '1,120p' gradle/wrapper/gradle-wrapper.properties && git status --short`
    - Result: exit 0; wrapper URL and Phase 0 worktree changes inspected.
21. Approved `conda run -n rate-limiter python -m pip install --disable-pip-version-check --requirement requirements-dev.lock`.
    - Result: exit 0; pinned direct Python dependencies and their resolved transitive dependencies installed in the required environment.
22. `conda run -n rate-limiter python -m pip freeze --all`
    - Result: exit 0; captured the resolved Python dependency set for expansion of the lock file.
23. Approved `conda run -n rate-limiter python -m pip install --disable-pip-version-check --no-deps --editable traffic-simulator --editable mock-services`.
    - Result: exit 0; both local distributions built and installed in editable mode.
24. Approved `npm --prefix admin-portal install --cache /tmp/rate-limiter-npm-cache --no-audit --no-fund`.
    - Result: exit 1; npm correctly rejected TypeScript 7.0.2 against `typescript-eslint` 8.65.0 peer range `>=4.8.4 <6.1.0`.
25. Approved `npm view typescript@6 version`.
    - Result: exit 0; TypeScript 6.0.3 is the newest compatible 6.x release.
26. Approved retry of `npm --prefix admin-portal install --cache /tmp/rate-limiter-npm-cache --no-audit --no-fund`.
    - Result: exit 0; 279 exact-lock packages installed and `package-lock.json` generated.
27. `scripts/check-repository-structure.sh && node --version && npm --version && conda run -n rate-limiter python --version && JAVA_HOME=<JDK21> ./gradlew --version`
    - Result: exit 0; structure passed and Node 24.10.0, npm 11.6.0, Python 3.12.13, Gradle 8.14.3, and launcher JVM 21.0.8 were verified.
28. Approved `JAVA_HOME=<JDK21> ./gradlew :gateway:test --no-daemon`.
    - Result: exit 1; dependency versions were empty because the Spring Boot BOM was not yet applied.
29. Approved retry of `JAVA_HOME=<JDK21> ./gradlew :gateway:test --no-daemon` after applying the BOM.
    - Result: exit 1; application compiled and started, but the test attempted to inject an unavailable `WebTestClient.Builder`.
30. Approved third `JAVA_HOME=<JDK21> ./gradlew :gateway:test --no-daemon` after binding the test client directly.
    - Result: exit 0; one gateway health/non-route test passed.
31. `conda run -n rate-limiter python -m pytest traffic-simulator/tests &&` separate catalog, orders, and payments pytest commands.
    - Result: exit 0; one test passed in each Python boundary; an HTTPX/TestClient deprecation warning was observed in each mock service and then removed during refactoring.
32. `npm --prefix admin-portal test -- --run`
    - Result: exit 0; one portal foundation rendering test passed.
33. `chmod +x scripts/test.sh && scripts/test.sh`
    - Result: exit 0; gateway, traffic simulator, catalog, orders, payments, and portal tests all passed with no Python deprecation warnings.
34. Controlled six-boundary absent-probe command (focused Gradle, pytest, and Vitest tests with outputs captured under `/tmp`).
    - Result: wrapper command exit 0 after asserting all six nested commands failed; Java could not resolve `CoverageProbe`, Python collection could not import each probe, and Vitest could not import `coverageProbe`.
35. Six native coverage commands with one branch tested.
    - Result: wrapper exit 1 because catalog/orders/payments unexpectedly passed; Java failed, traffic failed at 80%, and portal failed at 50% branch. The passing mock results exposed that coverage.py did not count the conditional expression.
36. Three mock coverage commands after changing only the temporary probes to explicit `if`.
    - Result: wrapper exit 0 after asserting all nested commands failed; catalog, orders, and payments each reported 81.82%.
37. Six native coverage commands after adding missing-branch tests.
    - Result: compound exit 1 after Java passed but traffic remained at 80% because the root-launched pytest-cov process did not load the nested exclusion configuration.
38. Same six native coverage commands after adding the narrow entry-point exclusion.
    - Result: compound exit 1 for the same root configuration-discovery reason; no later commands ran.
39. `conda run -n rate-limiter python -m coverage debug config` from `traffic-simulator/`.
    - Result: exit 0; proved the intended config and `if __name__ == "__main__":` exclusion are valid when explicitly loaded.
40. Traffic coverage command with `--cov-config=traffic-simulator/pyproject.toml`.
    - Result: exit 0; temporary traffic gate reported 100%.
41. Catalog, orders, payments, and portal coverage commands with both branches tested.
    - Result: exit 0; all four boundaries reported 100%.
42. Traffic coverage command after converting its probe to an explicit `if` and temporarily removing the false-branch test.
    - Result: exit 1 as expected; 80% total with the untested line and partial branch isolated to `coverage_probe.py`.
43. Same traffic coverage command after restoring the false-branch test.
    - Result: exit 0; 100% line and branch coverage.
44. `chmod +x scripts/coverage.sh && scripts/coverage.sh` after deleting all temporary probes.
    - Result: exit 0; gateway JaCoCo verification passed; traffic simulator, catalog, orders, payments, and portal each reported 100%.
45. Baseline Java, Python, and portal format/lint/typecheck command set with per-command status capture.
    - Result: wrapper exit 0 after recording nested statuses: Java 1, Python format 1, Python lint 1, Python mypy 0, portal format 1, portal ESLint 2, and portal typecheck 2. Each failure was inspected.
46. Full inspection of the Java, Python lint, and portal ESLint RED logs with `sed`.
    - Result: exit 0; confirmed concrete formatting issues, overbroad Ruff rules, and typed ESLint applying to its untyped JS config.
47. `JAVA_HOME=<JDK21> ./gradlew :gateway:spotlessApply --no-daemon &&` Ruff format/safe-fix commands and `npm --prefix admin-portal run format:write`.
    - Result: exit 0; Java/Kotlin Gradle, three Python files, and portal files were formatted; Ruff safely fixed three issues.
48. Complete Java, Python, and portal format/lint/typecheck rerun with status assertions.
    - Result: exit 0; all seven nested checks returned zero.
49. Controlled Checkstyle, strict-mypy, and ESLint violation commands.
    - Result: wrapper exit 0 after asserting all three nested commands failed; one wildcard-import error, one incompatible assignment, and one unused variable were reported.
50. `chmod +x scripts/format.sh scripts/static-checks.sh && scripts/format.sh && scripts/static-checks.sh` after deleting all probes.
    - Result: exit 0; Spotless, Ruff format, Prettier, Checkstyle, Ruff lint, strict mypy, ESLint, and strict TypeScript all passed.
51. `conda run -n rate-limiter python -c 'import inspect; from openapi_spec_validator import validate_spec; print(inspect.signature(validate_spec))'`.
    - Result: exit 0; verified the installed validator accepts a `base_uri` parameter before writing tests.
52. `conda run -n rate-limiter python -m pytest contracts/tests -x`.
    - Result: exit 1 as expected; first contract test failed because `contracts/policy.schema.json` did not exist.
53. `conda run -n rate-limiter python -m pytest contracts/tests` after adding schemas.
    - Result: exit 1; six JSON Schema tests passed and OpenAPI failed because the external relative reference could not resolve from a file URI lacking a directory base.
54. `conda run -n rate-limiter python -c 'import inspect, openapi_spec_validator as module; ...'`.
    - Result: exit 0; verified the non-deprecated `validate` API and signature.
55. `conda run -n rate-limiter python -m pytest contracts/tests` after fixing base URI and API usage.
    - Result: exit 0; seven contract tests passed without warnings.
56. `chmod +x scripts/validate-contracts.sh && scripts/validate-contracts.sh && scripts/format.sh && scripts/static-checks.sh`.
    - Result: exit 0; contracts, expanded formatting, and expanded static gates all passed.
57. `conda run -n rate-limiter python -m ruff check --show-files contracts && conda run -n rate-limiter python -m ruff check contracts`.
    - Result: exit 0; confirmed the contract config and Python test file are included and clean.
58. `docker compose config`.
    - Result: exit 14 as expected for Milestone 5 RED; no configuration file existed.
59. `chmod +x scripts/lint-dockerfiles.sh && scripts/lint-dockerfiles.sh`.
    - Result: exit 1 as expected; reported the four missing application Dockerfiles.
60. `chmod +x scripts/container-smoke.sh && scripts/container-smoke.sh`.
    - Result: exit 14 as expected; Compose configuration was absent and the cleanup trap also reported the missing file.
61. `docker compose config --quiet && docker compose config --images`.
    - Result: exit 0; Compose parsed and listed all non-profile image references.
62. Approved `open -a Docker`.
    - Result: exit 0; Docker Desktop launch requested.
63. Approved `docker info --format '{{.ServerVersion}}'`.
    - Result: exit 0; Docker Engine 27.5.1 was ready.
64. Approved `scripts/lint-dockerfiles.sh`.
    - Result: exit 0; pulled `hadolint/hadolint:v2.12.0-alpine` at digest `sha256:3c206a...` and all four Dockerfiles passed.
65. Approved `docker compose --profile tools build`.
    - Result: yielded once and then exited 0; gateway, traffic simulator, catalog, orders, payments, and admin portal images built successfully.
66. Approved first `scripts/container-smoke.sh`.
    - Result: yielded twice and exited 1; all services except admin portal became healthy, then the trap shut down and removed all containers/network.
67. Approved `docker compose up --detach admin-portal && docker compose ps admin-portal && docker compose logs --no-color admin-portal`.
    - Result: exit 0; Nginx started normally and the container entered health-starting.
68. Approved `docker inspect ... && docker exec ... wget ... localhost`.
    - Result: exit 0; health history showed repeated IPv6 `localhost` connection refusals and reproduced exit 1.
69. Approved host and container checks using `curl`, `wget 127.0.0.1`, and `ss`.
    - Result: compound exit 127 only because `ss` is absent; host health and IPv4 loopback health both returned the expected JSON and wget exit 0.
70. Approved `npm --prefix admin-portal install --package-lock-only ... && docker compose down --remove-orphans`.
    - Result: exit 0; lock metadata updated and diagnostic container/network removed.
71. Approved `scripts/lint-dockerfiles.sh && docker compose config --quiet`.
    - Result: exit 0; refined Dockerfiles and Compose passed.
72. Approved second `scripts/container-smoke.sh`.
    - Result: yielded once and exited 0; all services healthy, three gateway replicas present, seven host health checks passed, traffic CLI output matched, and all containers/network shut down.
73. Approved `docker compose down --volumes --remove-orphans && docker compose ps --all`.
    - Result: exit 0; PostgreSQL test volume removed and no Compose containers remained.
74. Approved `docker image inspect ... --format '{{.RepoTags}} user={{.Config.User}}'`.
    - Result: exit 0; gateway user `10001:0`, traffic user `65532:65532`, mock user `65532:65532`, and admin user `101:101` verified.
75. `chmod +x scripts/check-ci.sh && scripts/check-ci.sh`.
    - Result: exit 1 as expected for Milestone 6 RED; named the absent `.github/workflows/ci.yml`, `scripts/build.sh`, and `scripts/verify.sh`.
76. `chmod +x scripts/build.sh scripts/verify.sh && scripts/build.sh`.
    - Result: exit 0; gateway JAR, both Python source/wheel distributions, and portal production bundle built. The first run also exposed missing Python package README metadata without failing.
77. `scripts/check-ci.sh`.
    - Result: exit 0; workflow YAML parsed, required orchestration files existed, command placeholders were absent, and CI invoked the local verification entry point.
78. `scripts/verify.sh`.
    - Result: exit 0; every local CI-equivalent gate passed, all Compose services became healthy, the traffic simulator generated no traffic, and the stack shut down.
79. Parallel final inspection of the active plan, `docs/COMMANDS.md`, `git status --short`, `git diff --stat`, `git diff --check`, and prohibited-marker search.
    - Result: inspection completed; it found one trailing-space issue in the plan and only the intentional marker checks/prohibitions, with no skipped tests or unexplained markers in executable work.
80. `scripts/container-smoke.sh` after initially changing the one-shot invocation to `docker compose run --rm --build traffic-simulator`.
    - Result: exit 1 as a new orchestration RED; the explicit image build succeeded, but Compose build progress was captured with the exact CLI output and correctly failed the assertion.
81. `scripts/container-smoke.sh` after separating `docker compose build traffic-simulator` from the output-captured `docker compose run`.
    - Result: exit 0; all services were healthy, the explicitly built traffic image returned only its expected version, and the stack shut down.
82. `docker compose down --volumes --remove-orphans && docker compose ps --all && scripts/verify.sh`.
    - Result: exit 0; the prior disposable volume was removed, no Compose containers remained, and the complete final-source CI-equivalent run passed.
83. `docker compose down --volumes --remove-orphans && docker compose ps --all`.
    - Result: exit 0; the final disposable PostgreSQL volume was removed and no Compose containers remained.
84. Parallel final hygiene inspection: `git diff --check`, `scripts/check-ci.sh`,
    completed-plan location assertions, `docker compose ps --all`, prohibited-marker
    search, full untracked-file listing, and executable-bit inspection.
    - Result: the diff, CI structure, plan location, marker, and executable checks
      passed; the untracked listing exposed generated TypeScript build-info files,
      which were added to `.gitignore`. The combined status was nonzero only
      because the sandbox denied the read-only Docker socket check; the same check
      is repeated below with the previously approved Docker access.
85. `git diff --check && scripts/check-ci.sh &&` completed-plan, executable-bit,
    ignored-build-info, and `docker compose ps --all` assertions.
    - Result: exit 0 with no diff whitespace errors, complete CI structure,
      the plan only under `completed/`, all root scripts executable, no unignored
      TypeScript build-info, and no Compose containers.

## Decision log

- 2026-07-24 — Use GitHub Actions because `origin` is GitHub. No new CI platform decision is required.
- 2026-07-24 — Use Gradle 8.14.3 rather than Gradle 9 because Spring Boot 3.5 explicitly supports Gradle 8.4+ and the local JDK 21 runs it.
- 2026-07-24 — Use Spring Boot 3.5.16 rather than adopting Spring Boot 4 during a foundation phase; 3.5.16 supports Java through 25 and avoids an unnecessary major-framework decision.
- 2026-07-24 — Use the owner-provided Conda environment and pip-compatible exact direct pins rather than introducing uv, Poetry, or another Python environment owner.
- 2026-07-24 — Use npm rather than pnpm because npm is bundled with the pinned Node runtime and `package-lock.json` is sufficient for deterministic Phase 0 installs.
- 2026-07-24 — Pin React 19.2.8, Vite 8.1.5, Vitest 4.1.10, TypeScript 6.0.3, ESLint 10.7.0, Prettier 3.9.6, FastAPI 0.139.2, Uvicorn 0.51.0, pytest 9.1.1, pytest-cov 7.1.0, mypy 2.3.0, Ruff 0.16.0, build 1.5.0, jsonschema 4.26.0, openapi-spec-validator 0.9.0, and HTTPX 0.28.1 after registry verification. TypeScript 7.0.2 was superseded before installation because npm proved it incompatible with the linter peer range.
- 2026-07-24 — Treat schema incompleteness as an explicit later-phase boundary, not permission to invent fields or paths.

## Discoveries and surprises

- The operating instructions and repository skills are ignored by Git, so their presence cannot be proven from a clean checkout. Phase 0 will add a structure check that reports their local absence clearly, but it will not silently override the repository owner’s ignore policy.
- The machine default is Java 25 even though Java 21 is installed. Root Java scripts must set or verify Java 21 explicitly.
- Docker CLI and Compose are installed, but Docker Desktop was not active during the initial audit.
- The original Phase 0 plan omitted required data/migration, discoveries, exact milestone file, expected-failure, refactor, and broader-command details.
- Current TypeScript 7 is outside `typescript-eslint` 8.65.0's supported peer range; TypeScript 6.0.3 is the newest compatible verified choice.

## Risks and limitations

- The 90% gates are mechanically meaningful but easy to satisfy while the codebases contain almost no logic. Phase 0 proves failure behavior with temporary branches; later phases must add behavioral, concurrency, integration, and contract depth.
- Exact dependency locks are platform-specific where package managers require them; CI will verify Linux resolution separately.
- Image tags are pinned by version, not digest. Digest pinning remains a supply-chain hardening task.
- A local successful CI-equivalent command cannot prove the remote GitHub Actions runner without a pushed workflow run.
- Full product-level Compose smoke assertions are excluded because Phase 0 has no proxying or limiter behavior.

## Final outcome

Phase 0 is complete. The repository now has pinned, independently verifiable Java,
Python, and frontend foundations; conservative executable contract scaffolds;
non-root application images; a health-checkable multi-replica Compose environment;
and one local verification command shared with read-only GitHub Actions.

Strict RED-GREEN-REFACTOR evidence was observed at every milestone, including
independent under-coverage failures for all six executable boundaries and a final
orchestration RED that proved a clean-run traffic image build could not be mixed
with exact CLI output capture. The final `scripts/verify.sh` run returned exit 0,
and the final cleanup left no Compose containers or disposable test volumes.

No product feature was implemented. Exact later-phase traffic fields, admin paths,
algorithm-specific schemas beyond token bucket, numeric maxima, authentication,
backend routes, source-IP fallback, header allowlisting, and identity-hash rotation
remain intentionally undecided. None blocks the Phase 0 foundation. Remote GitHub
Actions execution remains unobserved until the workflow is pushed.
