# Verified Repository Commands

This file starts as a controlled placeholder. Codex must update it during Phase 0 with commands that were actually executed successfully in the repository.

Do not document guessed commands.

## Required command categories

### Entire repository

```text
TBD: one command for all formatting checks
TBD: one command for all static checks
TBD: one command for all unit tests
TBD: one command for all coverage gates
TBD: one command for all CI-equivalent verification
```

### Java gateway

```text
TBD: format
TBD: static analysis
TBD: unit test
TBD: integration test
TBD: coverage verification
TBD: build
```

### Python traffic simulator

```text
TBD: format
TBD: lint
TBD: type check
TBD: unit test and branch coverage
TBD: package build
```

### Python mock services

Document each independently executable service and shared package.

### Admin portal

```text
TBD: format
TBD: lint
TBD: type check
TBD: unit test and coverage
TBD: production build
```

### Contracts

```text
TBD: JSON Schema validation
TBD: OpenAPI validation
```

### Containers

```text
docker compose config
TBD: lint Dockerfiles
docker compose build
TBD: start and wait for health
TBD: smoke tests
docker compose down
docker compose down -v
```

## Recording format

For every command, record:

- working directory;
- prerequisites;
- exact command;
- what it verifies;
- last verified date;
- expected output or exit behavior;
- common failure interpretation.
