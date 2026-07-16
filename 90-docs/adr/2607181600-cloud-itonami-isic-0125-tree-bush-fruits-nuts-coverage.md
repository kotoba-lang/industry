# ADR-2607181600: cloud-itonami-isic-0125 (growing of other tree and bush fruits and nuts) berry-nut-orchard-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-18
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage),
ADR-2607152500 (cloud-itonami-isic-0124, module-structure template)

## Context

This is part of an ongoing careful, smaller-batch rollout (capable model +
mandatory verification per agent) after a prior 18-agent haiku batch had a
61% defect rate (empty implementations, missing modules, false "all
green" claims). 54+ consecutive agents on this stricter protocol have
succeeded before this one. Built by reading `cloud-itonami-isic-0124`
(growing of pome fruits and stone fruits, 31 tests / 105 assertions) in
full as the module-structure template, with `clojure -M:test`
independently re-run from fresh clones both before and after push.

Before any implementation work, the `kotoba-lang/industry` registry entry
was independently re-verified (fresh clone, not trusted from the task
assignment): `{:id "0125" :name "Growing of other tree and bush fruits
and nuts" ...}` is genuinely what is registered — no ID/name mismatch
(several prior agents in this fleet had mislabeled their assigned ISIC
class, e.g. 0892 assumed=salt actually=peat, 0144 assumed=swine
actually=sheep-goats). A fresh `gh api` check also confirmed no prior
repository existed at either `cloud-itonami/cloud-itonami-isic-0125` or
the stale placeholder `gftdcojp/cloud-itonami-A0125` referenced by the
old registry entry (404 confirmed).

Tree/bush-fruit-and-nut-growing operations (ISIC Rev. 4 0125, covering
bush fruits/berries such as blueberry, raspberry, blackcurrant and tree
nuts such as almond, walnut, hazelnut, pecan orchards/groves) span:
orchard/grove-block record logging (planting, harvest yield,
quality-grade), field-operation (pruning/spraying/harvest) scheduling,
crop health/pest (e.g. spotted wing drosophila)/disease (e.g. walnut
blight)/frost-damage concern escalation, and seedling/fertilizer/
equipment procurement. **CRITICAL exclusions**: direct field-equipment
operation and finalizing a spray-application decision remain the
exclusive authority of the grower/agronomist — this actor only
coordinates back-office record-keeping and logistics, never field
actuation or agronomic decision authority.

## Decision

Implement a complete berry-nut-orchard-operations-coordination actor
(`cloud-itonami-isic-0125`), mirroring `cloud-itonami-isic-0124`'s module
shape module-for-module (namespace `berrynutops.*`):

1. **`berrynutops.governor`** (`BerryNutOperationsGovernor`) — independent
   constraint layer with HARD checks (always hold, no override):
   - `orchard-not-registered` — request's orchard-id must resolve to a
     registered orchard/grove block in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`; the
     governor never directly executes anything.
   - `field-equipment-or-spray-blocked` — `:operate-field-equipment` and
     `:finalize-spray-application` are unconditionally, permanently
     blocked regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `orchard-count-invalid` — `:log-orchard-record` with a non-positive
     logged quantity (trees/bushes counted / harvest weight / yield
     estimate / quality-grade reading) is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` — ALWAYS escalates, any confidence
     (e.g. spotted wing drosophila, walnut blight, frost damage).
   - `:order-supplies` above its category cost threshold (default 500;
     `berrynutops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`berrynutops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (seedling, fertilizer, equipment) and
   fruit-class classification (blueberry/raspberry/blackcurrant = bush;
   almond/walnut/hazelnut/pecan = nut).

3. **`berrynutops.registry`** — independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `orchard-count-non-positive?`,
   `confidence-below-floor?`.

4. **`berrynutops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-orchard` lookup, `add-orchard` for tests/simulation.

5. **`berrynutops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`berrynutops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during simulation);
   phase-1 forces always-escalate ops to escalate even when clean;
   phase-2/3 pass the Governor's disposition through unchanged.

7. **`berrynutops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0124's own stub status).

8. **`berrynutops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-orchard-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** — 31 tests / 105 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0124's own test coverage shape exactly — same
    op set, same governor rule count, same 7-fruit-class facts table
    with a bush/nut `:group` dimension, same 15 governor-test cases
    swapping the fire-blight escalation case for a walnut-blight one).

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`berrynutops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0124).

## Consequences

(+) Tree/bush-fruit-and-nut-growing (ISIC 0125)
orchard-operations-coordination is now genuinely implemented and fully
tested.

(+) Scope boundaries (direct field-equipment operation, finalizing a
spray-application decision permanently excluded) are hardcoded in
governor checks (`field-equipment-or-spray-blocked`) and documented in
README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern`, e.g. spotted
wing drosophila, walnut blight, always human) is a core design
invariant, not an add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0124's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0125`: `clojure -M:test` → "Ran 31 tests
  containing 105 assertions. 0 failures, 0 errors." `clojure -M:lint` →
  0 errors, 0 warnings.
- Commit `eee42e00c53b6f99e1d1cba6a64fef1e2a24b03a` pushed to
  `cloud-itonami/cloud-itonami-isic-0125`'s `main` (fresh repo,
  root-commit). Independently confirmed via `gh api
  repos/cloud-itonami/cloud-itonami-isic-0125/commits/main` matching the
  local commit SHA, and `gh api
  repos/cloud-itonami/cloud-itonami-isic-0125/compare/main...main` →
  `{"status":"identical","ahead_by":0,"behind_by":0}`.
- Registry entry (`kotoba-lang/industry`) updated in place: `"0125"`
  entry's `:maturity` `:spec` → `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-A0125` placeholder to
  `cloud-itonami/cloud-itonami-isic-0125`, and this ADR referenced.
  `industry_test.clj`'s `maturity-summary` assertion bumped 243 → 244
  (the true count recomputed live via `(industry/maturity-summary)` on a
  freshly re-fetched `origin/main` immediately before the edit — landed
  cleanly on the first attempt, no concurrent-fleet 409). `grep -c "â"
  resources/kotoba/industry/registry.edn` → 0 (no file-wide UTF-8
  mojibake, both before and after merge). Landed via GitHub API
  server-side merge commit `d14b223175dc9a7e7b9fea06467f896dcc95be42`
  onto `main`. `gh api
  repos/kotoba-lang/industry/compare/d14b223175dc9a7e7b9fea06467f896dcc95be42...main`
  → `{"status":"identical","ahead_by":0,"behind_by":0}`, confirming the
  merge commit is exactly `main`'s tip. Merged branch deleted. Post-merge,
  `clojure -M:test` from a **completely fresh clone** of
  `kotoba-lang/industry`'s `main` (with `kotoba-lang/technology`
  re-cloned as its `../technology` sibling, per `industry`'s own
  `deps.edn` local/root dependency) → "Ran 15 tests containing 950
  assertions. 0 failures, 0 errors." A separate, independent fresh clone
  of `cloud-itonami/cloud-itonami-isic-0125` itself (commit
  `eee42e00c53b6f99e1d1cba6a64fef1e2a24b03a`) also re-confirmed "Ran 31
  tests containing 105 assertions. 0 failures, 0 errors."
