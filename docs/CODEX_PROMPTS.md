# Suggested Codex Prompt Sequence

Use one focused prompt at a time. Do not ask Codex to implement the complete platform in a single run.

## 1. Repository audit and Phase 0 plan

```text
Read AGENTS.md and all documents required for Phase 0. Inspect the actual repository. Do not implement product features. Update docs/exec-plans/active/PHASE-0-FOUNDATION.md so it is self-contained and names the exact toolchain choices, files, commands, milestones, risks, and TDD evidence you will use. Identify contradictions and decisions needing approval. Do not claim anything was run unless you ran it.
```

## 2. Execute Phase 0

```text
Execute the approved Phase 0 ExecPlan. Follow strict RED-GREEN-REFACTOR for each milestone. Keep the plan and docs/COMMANDS.md current. Do not add rate-limiting behavior. Run and record every required quality and container command. Stop scope expansion, not implementation progress, when you encounter an unapproved architecture choice.
```

## 3. Plan Phase 1 algorithms

```text
Create a self-contained Phase 1 ExecPlan for the common limiter domain contracts and in-memory fixed-window implementation first. Include invariants, fake-time design, property tests, concurrency tests, package boundaries, and exact verification commands. Do not implement until the plan is reviewed.
```

Then repeat per algorithm rather than implementing all algorithms simultaneously.

## 4. First distributed vertical slice

```text
Create an ExecPlan for one end-to-end slice: simulator request -> load balancer -> at least three gateway replicas -> static fixed-window policy -> atomic Redis decision -> one mock backend. The plan must prove a shared limit across replicas, rejected-request non-delivery, replica restart persistence, and failure-mode behavior. Use the distributed-rate-limiter skill. Do not add the admin portal or dynamic policy control plane in this slice.
```

## 5. Policy control plane

```text
Create an ExecPlan for PostgreSQL policy versioning, activation, Redis Pub/Sub invalidation, immutable gateway snapshot swap, and polling reconciliation. Begin with schema and contract tests. Include propagation-status observability and missed-event recovery. Do not implement UI until the API and propagation behavior are complete and tested.
```

## 6. Review prompt

```text
Review the current branch against AGENTS.md, the active ExecPlan, schemas, ADRs, and the Definition of Done. Focus on correctness gaps, non-atomic Redis behavior, reactive blocking, policy snapshot races, test weakness, coverage gaming, sensitive-data exposure, and unsupported completion claims. Run the relevant checks before reporting findings.
```
