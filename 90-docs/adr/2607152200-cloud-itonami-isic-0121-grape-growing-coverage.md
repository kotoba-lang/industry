# ADR-2607152200: cloud-itonami-isic-0121 (growing of grapes) vineyard-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage),
ADR-2607152100 (cloud-itonami-isic-0141, module-structure template)

## Context

This is part of a careful, smaller-batch redo (6 agents, a more capable
model, mandatory verification) after a prior 18-agent haiku batch had a
61% defect rate (empty implementations, missing modules, false "all
green" claims). Built by reading `cloud-itonami-isic-0141` (raising of
cattle and buffaloes, 30 tests / 90 assertions independently re-verified)
in full as the module-structure template, with `clojure -M:test`
independently re-run from fresh clones both before and after push.

Grape-growing operations (ISIC Rev. 4 0121) span: vineyard/block record
logging (planting, harvest, yield, brix testing), field-operation
(pruning/spraying/harvest) scheduling, crop health/pest (phylloxera)/
disease/frost-damage concern escalation, and rootstock/fertilizer/
equipment procurement. **CRITICAL exclusions**: direct field-equipment
operation and finalizing a spray-application decision remain the
exclusive authority of the grower/agronomist — this actor only
coordinates back-office record-keeping and logistics, never field
actuation or agronomic decision authority.

## Decision

Implement a complete vineyard-operations-coordination actor
(`cloud-itonami-isic-0121`), mirroring `cloud-itonami-isic-0141`'s module
shape module-for-module (namespace `vineyardops.*`):

1. **`vineyardops.governor`** (`VineyardOperationsGovernor`) — independent
   constraint layer with HARD checks (always hold, no override):
   - `vineyard-not-registered` — request's vineyard-id must resolve to a
     registered vineyard/block in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`; the
     governor never directly executes anything.
   - `field-equipment-or-spray-blocked` — `:operate-field-equipment` and
     `:finalize-spray-application` are unconditionally, permanently
     blocked regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `vineyard-count-invalid` — `:log-vineyard-record` with a non-positive
     logged quantity (vine count / harvest weight / yield estimate / brix
     reading) is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` — ALWAYS escalates, any confidence.
   - `:order-supplies` above its category cost threshold (default 500;
     `vineyardops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`vineyardops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (rootstock, fertilizer, equipment) and
   grape-class classification (wine-grape, table-grape).

3. **`vineyardops.registry`** — independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `vineyard-count-non-positive?`,
   `confidence-below-floor?`.

4. **`vineyardops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-vineyard` lookup, `add-vineyard` for tests/simulation.

5. **`vineyardops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`vineyardops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`vineyardops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching `cattleops.operation`'s own stub status).

8. **`vineyardops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-vineyard-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** — 30 tests / 90 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/`sim_test`,
    matching 0141's own test coverage shape).

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`vineyardops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0141).

## Consequences

(+) Grape-growing (ISIC 0121) vineyard-operations-coordination is now
genuinely implemented and fully tested.

(+) Scope boundaries (direct field-equipment operation, finalizing a
spray-application decision permanently excluded) are hardcoded in
governor checks (`field-equipment-or-spray-blocked`) and documented in
README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern` always human) is
a core design invariant, not an add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0141's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0121`: `clojure -M:test` → "Ran 30 tests
  containing 90 assertions. 0 failures, 0 errors." Independently
  re-verified from a fresh clone of `origin/main` after push (identical
  raw output).
- `clojure -M:lint` → 0 errors, 0 warnings. `clojure -M:run` → demo
  runs end-to-end, returns `:disposition :escalate` as expected
  (phase-0 forces human review of all commits).
- Commit `7c8e92516211a33c84ee7488f5fe56bc45b98852` pushed to
  `cloud-itonami/cloud-itonami-isic-0121`'s `main` (fresh repo,
  root-commit).
- Registry entry (`kotoba-lang/industry`) updated in place: `"0121"`
  entry's `:maturity` `:spec` → `:implemented`, `:repo`/`:business-id`
  set to `cloud-itonami-isic-0121` and this ADR referenced. Landed via
  GitHub API server-side merge commit
  `848dc0cee59c0b0a1da1bfaafb453a85c72e7e68` onto `main` (no 409, landed
  on first attempt). `industry_test.clj`'s `maturity-summary` assertion
  bumped from 193 to 194 — the true count recomputed from a real
  `clojure -M:test` run against the live `registry.edn` (a naive
  `grep -c ':maturity :implemented'` undercounted by 1; the baseline run
  at `HEAD` before this edit was already green at 193, and the run after
  the edit failed with `actual: (not (= 193 194))`, giving the correct
  bump target). Post-merge, `clojure -M:test` from a **completely fresh
  clone** of `kotoba-lang/industry`'s `main` (with `kotoba-lang/technology`
  re-cloned as its `../technology` sibling, per `industry`'s own
  `deps.edn` local/root dependency) → "Ran 15 tests containing 941
  assertions. 0 failures, 0 errors."
