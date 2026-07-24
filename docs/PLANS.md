# Codex Execution Plans (ExecPlans)

An ExecPlan is a self-contained, living implementation specification for a substantial task. A reader with only the repository and the plan must be able to understand the intended outcome, continue the work, and verify it.

## When an ExecPlan is required

Use one for:

- work spanning more than one component;
- distributed concurrency or data consistency changes;
- externally visible contract changes;
- a new algorithm implementation;
- a project phase;
- a significant refactor;
- work expected to require several RED-GREEN-REFACTOR cycles.

## Required properties

An ExecPlan must:

- define user-visible or experimentally visible outcomes;
- explain terms specific to this repository;
- name exact files and module boundaries as they become known;
- include TDD checkpoints;
- include commands that prove each milestone;
- remain current after discoveries;
- distinguish decisions from assumptions;
- record progress and unexpected behavior;
- avoid depending on chat history.

## Required structure

Every active plan must contain these sections.

### Title and status

State whether the plan is proposed, approved, active, blocked, or complete. Include owner and last-updated date.

### Purpose

Explain what a user or developer can do after the work that they could not do before.

### Scope and non-scope

List included behavior and explicit exclusions. Keep the slice narrow.

### Current repository state

Describe relevant existing modules, contracts, tests, and constraints discovered by inspection. Do not guess.

### Proposed design

Explain request flow, state changes, interfaces, failure behavior, and security implications in plain language.

### Invariants

List properties that must always hold. Examples:

- rejected requests never reach a backend;
- token balance stays within bounds;
- gateway replica count does not change a distributed limit.

### Milestones

Each milestone must deliver a verifiable behavior. For every milestone provide:

- files expected to change;
- RED test to write;
- expected failure reason;
- minimum implementation;
- refactoring boundary;
- focused verification command;
- broader verification command;
- observable success criteria.

### Contract and schema changes

List every JSON Schema, OpenAPI, event, Redis result, metric, or configuration contract changed.

### Data and migration considerations

Describe PostgreSQL migration, Redis key compatibility, version isolation, cleanup, and rollback where applicable.

### Security and failure analysis

Describe input validation, sensitive data, authorization boundary, fail-open/fail-closed behavior, timeouts, and resource bounds.

### Validation plan

List exact commands for formatting, static checks, tests, coverage, integration, containers, and end-to-end experiments. Replace placeholders with verified commands during Phase 0.

### Progress log

Use timestamped entries. Record RED and GREEN evidence, files changed, and outcomes.

### Decision log

Record each material decision, alternatives considered, and whether an ADR was created.

### Discoveries and surprises

Record facts learned during implementation that affect design or future work.

### Risks and limitations

State remaining technical and product risks honestly.

### Final outcome

On completion, summarize demonstrated behavior, command results, coverage by codebase, and remaining non-goals.

## Plan maintenance rules

- Update the plan before leaving a milestone.
- Do not erase prior decisions; mark them superseded and explain why.
- If implementation invalidates the plan, revise the plan before continuing broad changes.
- A plan can be approved while leaving narrow implementation details to evidence-driven decisions.
- Move complete plans to `docs/exec-plans/completed/`.
