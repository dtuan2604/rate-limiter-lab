# Engineering Standards and Codex Guardrails

## 1. Development method

Strict test-driven development is mandatory for behavior changes:

```text
RED -> GREEN -> REFACTOR -> VERIFY
```

The active ExecPlan must record the failing test command and why it failed, followed by the passing command after implementation.

Tests written after production code do not satisfy the workflow. A compile failure caused only by an intentionally absent new symbol can be part of RED, but the preferred RED signal is an executable behavioral assertion that fails meaningfully.

## 2. Small changes

A work unit should normally change one coherent behavior through all necessary layers. Avoid broad scaffolding of untested future features.

Before editing:

- inspect existing code and current plan;
- identify the smallest observable behavior;
- define its test boundary;
- confirm contracts that will change;
- list expected commands.

After editing:

- run focused tests;
- run affected module tests;
- run static checks;
- run coverage verification;
- run integration or container checks when the behavior crosses those boundaries.

## 3. Evidence and truthfulness

Codex must not claim:

- tests pass;
- coverage is at least 90%;
- a Redis operation is atomic;
- code is thread-safe;
- containers start;
- a route works;
- policy propagation converges;
- a feature is production-ready;

unless the corresponding command or experiment was executed and the result inspected.

When a tool or environment prevents validation, state exactly what was not run and why. Do not replace missing evidence with confident language.

## 4. API and dependency verification

- Pin production and build dependency versions.
- Use dependency catalogs or central version management.
- Verify unfamiliar APIs against official documentation for the pinned version, source, compiler, or type checker.
- Do not add two libraries for the same purpose without an ADR.
- Explain new major dependencies in the active ExecPlan.
- Run vulnerability scanning, but treat results as input requiring review rather than automatic proof of safety.

## 5. No placeholder completion

A completed milestone may not contain:

- unexplained `TODO` or `FIXME` markers;
- empty implementations;
- always-allow or always-success branches posing as real behavior;
- fake persistence in a distributed path;
- disabled or skipped tests;
- assertions that merely check non-null or constants when behavior matters;
- catches that swallow errors;
- silent fallback to local limiter state.

Temporary scaffolding must be explicitly marked incomplete in the active ExecPlan and excluded from claims of completion.

## 6. Coverage integrity

Do not game coverage by:

- excluding production packages;
- testing constructors without behavior;
- mocking the unit whose atomicity or persistence is under test;
- deleting error handling;
- marking difficult tests ignored;
- executing lines without meaningful assertions.

Coverage exclusions are limited to generated code and trivial framework entry points with no logic. Every exclusion requires an inline or build-file explanation.

## 7. Schema-first external contracts

Before changing an externally visible request, response, event, or policy shape:

1. update the relevant schema or OpenAPI contract;
2. add or update contract tests;
3. observe RED;
4. implement consumers and producers;
5. verify backward compatibility or record the deliberate break.

Shared contracts include:

- policy JSON Schema;
- admin API OpenAPI;
- traffic scenario schema;
- standardized error schema;
- Redis Lua result contract;
- metrics label definitions.

## 8. Architecture boundaries

Do not leak framework or storage types into domain algorithm contracts.

All in-memory and Redis implementations must satisfy shared behavioral contract tests where semantics are the same. Redis-specific tests additionally prove atomicity and expiry.

Use explicit value types for limits, durations, request costs, policy IDs, versions, and identity hashes rather than passing unrelated primitives through many layers.

## 9. Reactive gateway rules

- Never call blocking database, filesystem, or network operations on Netty event-loop threads.
- Bound body buffering and connection pools.
- Make timeout and cancellation behavior explicit.
- Ensure a rejected request does not subscribe to or execute backend forwarding.
- Test cancellation, timeout, and error mapping.
- Avoid mutable shared request state.

## 10. Python rules

- Use type hints for public interfaces.
- Enforce Ruff, formatting, and mypy in strict or deliberately documented mode.
- Use deterministic seeds in tests.
- Avoid real sleeping in unit tests; inject scheduling/time abstractions where practical.
- Avoid outbound external network calls in tests.

## 11. TypeScript rules

- Enable strict TypeScript.
- Test user-visible behavior with React Testing Library.
- Mock HTTP at the transport boundary with MSW or equivalent.
- Do not test internal component implementation details.
- Include error, loading, empty, and stale-state UI paths.

## 12. Security and data handling

- Never log credentials, API keys, full identity hashes, request bodies by default, or Redis script arguments containing sensitive data.
- Validate trusted proxy configuration.
- Restrict administrative APIs.
- Bound all user-controlled numeric values.
- Prohibit arbitrary code or expression execution in policies.
- Use parameterized database operations.
- Scan images and dependencies, but do not silently auto-upgrade across incompatible versions.

## 13. Git and worktree discipline

- Do not discard user changes.
- Inspect `git status` before and after work.
- Keep changes on-topic.
- Do not rewrite history or force push.
- Commit only when the user or current workflow allows it.
- If using multiple Codex agents or worktrees, assign non-overlapping scopes or explicit integration ownership.

## 14. Documentation maintenance

Update documentation in the same change when behavior, commands, contracts, or architecture change. The code is authoritative for verified runtime details; docs must be corrected when stale rather than ignored.
