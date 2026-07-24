# Definition of Done

## 1. A single task is done only when

- its intended behavior and exclusions are clear;
- a meaningful RED test was observed before production implementation;
- focused tests pass;
- affected module tests pass;
- coverage gates pass for every changed executable codebase;
- formatting, lint, type checking, and static analysis pass;
- relevant integration or contract tests pass;
- documentation and schemas are updated;
- no unexplained TODO, skipped test, placeholder, or silent fallback remains;
- the active ExecPlan records commands, results, decisions, and limitations;
- the final report distinguishes verified facts from unverified assumptions.

## 2. A phase is done only when

- every milestone outcome is demonstrable from a clean checkout;
- all required CI jobs pass;
- the phase's containers build and start where applicable;
- the phase includes at least one end-to-end proof of its user-visible purpose;
- active plans are moved to `completed/` with a final outcome summary;
- new architecture decisions are recorded in ADRs;
- commands in `docs/COMMANDS.md` are verified;
- known debt is explicit and not hidden behind completion claims.

## 3. Distributed limiter implementation is done only when

- it satisfies common algorithm contract tests;
- one atomic Redis operation owns the authoritative transition;
- multi-client/multi-instance concurrency tests pass;
- expiry and cleanup behavior is proven;
- Redis server time behavior is tested when required;
- failure-open/failure-closed behavior is tested;
- raw sensitive identities are absent from Redis keys and logs;
- adding replicas does not multiply the limit;
- restart does not reset the state;
- implementation and limitations are documented.

## 4. Policy control plane is done only when

- policy and version persistence constraints are tested;
- JSON Schema and semantic validation are complete;
- activation is transactional;
- event publication ordering is correct;
- gateways atomically swap complete snapshots;
- missed-event polling reconciliation is tested;
- propagation status is visible;
- invalid candidate policies leave prior snapshots active;
- admin API contract and portal behavior are tested.

## 5. Entire project completion

The project is complete only when a clean environment can:

1. build every image;
2. start all required containers;
3. run at least three gateway replicas behind a load balancer;
4. seed or create a policy;
5. generate concurrent traffic;
6. demonstrate accepted and rejected requests;
7. prove rejected requests did not reach a backend;
8. update a policy without gateway restart;
9. demonstrate global enforcement across replicas;
10. restart and scale replicas without state reset;
11. expose usable metrics and dashboards;
12. pass all per-codebase coverage and quality gates;
13. run documented failure scenarios;
14. provide reproducible experiment results for all required algorithms.

“Production-ready” is not an allowed completion label. The final documentation must state which production concerns remain outside this educational system.
