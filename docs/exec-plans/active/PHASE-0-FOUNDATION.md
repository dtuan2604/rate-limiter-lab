# Phase 0 ExecPlan — Repository and Quality Foundation

**Status:** Proposed for approval  
**Owner:** Codex, supervised by repository owner  
**Last updated:** Replace when work starts

## Purpose

Create a repository in which every subsequent feature is forced through repeatable builds, strict TDD, independent coverage gates, schema checks, and container validation. This phase intentionally implements no rate-limiting product behavior.

## Scope

Included:

- select and pin compatible toolchain versions;
- establish Gradle multi-project structure;
- establish Python and TypeScript packages;
- configure unit test frameworks and independent 90% coverage gates;
- configure formatting, linting, static analysis, and type checking;
- create initial contracts and validation commands;
- create Docker build placeholders that run real health-checkable minimal applications only if tests lead them;
- establish CI and local verification scripts;
- verify nested AGENTS guidance and repo-scoped skills are discoverable;
- populate `docs/COMMANDS.md` with tested commands.

Excluded:

- limiter algorithms;
- Redis state logic;
- proxy forwarding;
- policy persistence;
- admin feature behavior;
- traffic generation behavior beyond package test scaffolding.

## Current repository state

At the start, only repository instructions and design documents may exist. Codex must inspect the actual tree and update this section before implementation.

## Proposed design

Use a single repository with independently testable executable codebases:

- Java gateway Gradle project;
- Python traffic simulator package;
- independently deployable Python mock services, with a tested shared package if needed;
- React/TypeScript admin portal;
- contract and documentation directories;
- Docker Compose and monitoring configuration.

Root scripts should orchestrate existing module commands rather than hiding them. CI must report coverage separately per codebase.

## Invariants

- A coverage failure in one codebase fails the build even if aggregate coverage is high.
- No quality gate is configured only in documentation; every gate is executable.
- A minimal scaffold does not claim product behavior.
- Tool and dependency versions are pinned after compatibility verification.
- Local verification and CI use the same underlying commands.

## Milestone 1 — Toolchain decisions and skeleton

### RED

Add repository-structure or build-discovery tests/checks that fail because the expected modules and pinned toolchain files do not exist.

### GREEN

Create the smallest buildable module skeletons and version configuration.

### Verification

Codex must replace these placeholders with exact commands after selecting tools:

```text
<root build discovery command>
<java compile/test discovery command>
<python package test discovery command>
<frontend typecheck/test discovery command>
```

### Success

Every codebase can run a trivial meaningful test and fail when that test is intentionally broken.

## Milestone 2 — Coverage gates

### RED

Add deliberately under-covered branch behavior in test fixtures or gate-verification samples and confirm each codebase's coverage task fails below 90%.

### GREEN

Configure JaCoCo, pytest-cov branch coverage, and frontend coverage thresholds. Remove temporary gate-verification behavior after proving the gates.

### Success

CI and local commands independently fail each codebase below threshold.

## Milestone 3 — Static analysis and formatting

### RED

Use controlled fixtures or known violations to prove each configured checker detects an issue.

### GREEN

Configure Java formatting/static checks, Ruff/formatting/mypy, and ESLint/Prettier/strict TypeScript.

### Success

One root verification command runs all applicable checks and returns nonzero on violations.

## Milestone 4 — Contracts and validation

### RED

Add invalid sample policy, traffic scenario, error response, and OpenAPI fixture tests.

### GREEN

Create initial schemas with only fields already approved in the product documents and add validators.

### Success

Valid examples pass; invalid examples fail at stable, useful locations.

## Milestone 5 — Container validation

### RED

Add Compose validation and image-build checks before valid configuration exists.

### GREEN

Create minimal secure Dockerfiles and Compose structure sufficient to build and start health-checkable skeleton services. Do not fake product endpoints.

### Success

`docker compose config`, image builds, health checks, and clean shutdown pass.

## Milestone 6 — CI and command documentation

### RED

Create CI jobs that initially expose missing commands or failing gates.

### GREEN

Wire the same verified local commands into CI. Populate `docs/COMMANDS.md` with exact versions and results.

### Success

A clean checkout can run the documented verification workflow without tribal knowledge.

## Contract and schema changes

Initial versions of:

- `contracts/policy.schema.json`;
- `contracts/traffic-scenario.schema.json`;
- `contracts/error.schema.json`;
- `contracts/admin-api.openapi.yaml`.

These are scaffolds, not permission to invent unapproved product fields.

## Security and failure analysis

- Base images must be pinned by version and later may be pinned by digest.
- Containers should run as non-root where practical.
- No credentials are committed.
- CI permissions are least privilege.
- Dependency scans are advisory inputs unless severity policy is explicitly configured.

## Validation plan

Codex must fill exact commands in `docs/COMMANDS.md` and reference them here after execution.

Minimum categories:

- format check;
- lint/static analysis;
- type checking;
- unit tests;
- coverage verification per codebase;
- schema validation;
- Gradle build;
- Python package build;
- frontend build;
- Dockerfile lint;
- `docker compose config`;
- image build;
- health-check smoke test.

## Progress log

Add timestamped RED/GREEN/REFACTOR evidence here during execution.

## Decision log

Record exact selected versions, Gradle structure, Python package manager, frontend package manager, CI platform assumptions, and any deviation from project documents.

## Risks and limitations

The 90% gates can be temporarily easy to satisfy when codebases contain almost no logic. This phase must prove gates mechanically, but meaningful behavioral coverage begins in Phase 1.

## Final outcome

Complete only after every documented command has been executed from a clean state and results are recorded.
