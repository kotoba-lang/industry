# ADR-2607152500: cloud-itonami-isic-0122 (growing of tropical and subtropical fruits) orchard-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage),
ADR-2607152200 (cloud-itonami-isic-0121, module-structure template)

## Context

This is part of an ongoing careful, smaller-batch rollout (capable model +
mandatory verification per agent) after a prior 18-agent haiku batch had a
61% defect rate (empty implementations, missing modules, false "all
green" claims). 24+ consecutive agents on this stricter protocol have
succeeded before this one. Built by reading `cloud-itonami-isic-0121`
(growing of grapes, 30 tests / 90 assertions independently re-verified)
in full as the module-structure template, with `clojure -M:test`
independently re-run from fresh clones both before and after push.

Before any implementation work, the `kotoba-lang/industry` registry entry
was independently re-verified (fresh clone, not trusted from the task
assignment): `{:id "0122" :name "Growing of tropical and subtropical
fruits" ...}` is genuinely what is registered — no ID/name mismatch (two
prior agents in this fleet had mislabeled their assigned ISIC class).

Tropical/subtropical-fruit-growing operations (ISIC Rev. 4 0122, covering
mango, banana, papaya, avocado, pineapple orchards, among others) span:
orchard/block record logging (planting, harvest yield, brix testing),
field-operation (pruning/spraying/irrigation/harvest) scheduling, crop
health/pest (e.g. fruit-fly)/fungal-disease/frost-damage concern
escalation, and seedling/fertilizer/equipment procurement. **CRITICAL
exclusions**: direct field-equipment operation and finalizing a
spray-application decision remain the exclusive authority of the
grower/agronomist — this actor only coordinates back-office
record-keeping and logistics, never field actuation or agronomic
decision authority.

## Decision

Implement a complete orchard-operations-coordination actor
(`cloud-itonami-isic-0122`), mirroring `cloud-itonami-isic-0121`'s module
shape module-for-module (namespace `orchardops.*`):

1. **`orchardops.governor`** (`OrchardOperationsGovernor`) — independent
   constraint layer with HARD checks (always hold, no override):
   - `orchard-not-registered` — request's orchard-id must resolve to a
     registered orchard/block in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`; the
     governor never directly executes anything.
   - `field-equipment-or-spray-blocked` — `:operate-field-equipment` and
     `:finalize-spray-application` are unconditionally, permanently
     blocked regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `orchard-count-invalid` — `:log-orchard-record` with a non-positive
     logged quantity (trees/plants counted / harvest weight / yield
     estimate / brix reading) is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` — ALWAYS escalates, any confidence.
   - `:order-supplies` above its category cost threshold (default 500;
     `orchardops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`orchardops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (seedling, fertilizer, equipment) and
   fruit-class classification (mango, banana, papaya, avocado, pineapple).

3. **`orchardops.registry`** — independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `orchard-count-non-positive?`,
   `confidence-below-floor?`.

4. **`orchardops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-orchard` lookup, `add-orchard` for tests/simulation.

5. **`orchardops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`orchardops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`orchardops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0121's own stub status).

8. **`orchardops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`; verified end-to-end (`:disposition :escalate`, phase-0 forces
   human review as expected).

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-orchard-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** — 30 tests / 93 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/`sim_test`,
    matching 0121's own test coverage shape). Assertion count is 3 higher
    than 0121's 90 because `orchardops.facts` covers 5 fruit classes
    (mango/banana/papaya/avocado/pineapple) instead of 0121's 2 grape
    classes.

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`orchardops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0121).

## Consequences

(+) Tropical/subtropical-fruit-growing (ISIC 0122) orchard-operations-
coordination is now genuinely implemented and fully tested.

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
deferred scaffolding, matching 0121's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0122`: `clojure -M:test` → "Ran 30 tests
  containing 93 assertions. 0 failures, 0 errors." `clojure -M:lint` →
  0 errors, 0 warnings. `clojure -M:run` → demo runs end-to-end, returns
  `:disposition :escalate` as expected (phase-0 forces human review of
  all commits). Independently re-verified from a fresh clone of
  `origin/main` after push (identical raw output).
- Commit `ec0bc3d17302461a7ce987e9c1d08165f0dc1ec7` pushed to
  `cloud-itonami/cloud-itonami-isic-0122`'s `main` (fresh repo,
  root-commit).
- Registry entry (`kotoba-lang/industry`) updated in place: `"0122"`
  entry's `:maturity` `:spec` → `:implemented`, `:repo`/`:business-id`
  set to `cloud-itonami-isic-0122` and this ADR referenced. Landed via
  GitHub API server-side merge commit
  `21af8e81842358c8caf16d8a33c7de12a03cb638` onto `main` (no 409, landed
  on first attempt). `industry_test.clj`'s `maturity-summary` assertion
  bumped from 213 to 214 — the true count recomputed from a real
  `clojure -M:test`-driven `(industry/maturity-summary)` call against the
  live `registry.edn` on a freshly re-fetched clone immediately before
  this edit (not an assumed fixed number). Baseline run at that `HEAD`
  was already green at "Ran 15 tests containing 944 assertions. 0
  failures, 0 errors." (213). `grep -c "â" resources/kotoba/industry/
  registry.edn` → 0 (no file-wide UTF-8 mojibake). Post-merge, `clojure
  -M:test` from a **completely fresh clone** of `kotoba-lang/industry`'s
  `main` (with `kotoba-lang/technology` re-cloned as its `../technology`
  sibling, per `industry`'s own `deps.edn` local/root dependency) →
  "Ran 15 tests containing 944 assertions. 0 failures, 0 errors."
