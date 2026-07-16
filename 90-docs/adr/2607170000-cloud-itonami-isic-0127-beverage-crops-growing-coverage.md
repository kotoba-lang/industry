# ADR-2607170000: cloud-itonami-isic-0127 (growing of beverage crops) beverage-plantation-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage),
ADR-2607152500 (cloud-itonami-isic-0122, module-structure template)

## Context

This is part of an ongoing careful, smaller-batch rollout (capable model +
mandatory verification per agent) after a prior 18-agent haiku batch had a
61% defect rate (empty implementations, missing modules, false "all
green" claims). 30+ consecutive agents on this stricter protocol have
succeeded before this one. Built by reading `cloud-itonami-isic-0122`
(growing of tropical and subtropical fruits, 30 tests / 93 assertions
independently re-verified) in full as the module-structure template, with
`clojure -M:test` independently re-run from fresh clones both before and
after push.

Before any implementation work, the `kotoba-lang/industry` registry entry
was independently re-verified (fresh clone, not trusted from the task
assignment): `{:id "0127" :name "Growing of beverage crops" ...}` is
genuinely what is registered — no ID/name mismatch (multiple prior agents
in this fleet had mislabeled their assigned ISIC class, e.g. 0892
assumed=salt/actually peat, 0144 assumed=swine/actually sheep-goats).
`gh api repos/cloud-itonami/cloud-itonami-isic-0127` returned 404 before
any work began, confirming this was a fresh from-scratch scaffold (no
prior repo existed; the registry's old `:repo`/`:business-id` pointed at
a never-created `gftdcojp/cloud-itonami-A0127` placeholder).

Beverage-crop-growing operations (ISIC Rev. 4 0127, covering coffee, tea,
cacao, and yerba mate plantations, among others) span: plantation/block
record logging (planting, harvest yield, quality-grade — cupping score
for coffee, leaf grade for tea), field-operation (pruning/spraying/
harvest) scheduling, crop health/pest (e.g. coffee borer)/disease (e.g.
leaf rust)/drought-stress concern escalation, and seedling/fertilizer/
equipment procurement. **CRITICAL exclusions**: direct field-equipment
operation and finalizing a spray-application decision remain the
exclusive authority of the grower/agronomist — this actor only
coordinates back-office record-keeping and logistics, never field
actuation or agronomic decision authority.

## Decision

Implement a complete beverage-crop-plantation-operations-coordination
actor (`cloud-itonami-isic-0127`), mirroring `cloud-itonami-isic-0122`'s
module shape module-for-module (namespace `beverageops.*`):

1. **`beverageops.governor`** (`BeverageOperationsGovernor`) — independent
   constraint layer with HARD checks (always hold, no override):
   - `plantation-not-registered` — request's plantation-id must resolve
     to a registered plantation/block in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`; the
     governor never directly executes anything.
   - `field-equipment-or-spray-blocked` — `:operate-field-equipment` and
     `:finalize-spray-application` are unconditionally, permanently
     blocked regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `plantation-count-invalid` — `:log-plantation-record` with a
     non-positive logged quantity (trees/bushes counted / harvest weight
     / yield estimate / cupping score / leaf grade) is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` — ALWAYS escalates, any confidence.
   - `:order-supplies` above its category cost threshold (default 500;
     `beverageops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`beverageops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (seedling, fertilizer, equipment) and
   beverage-crop classification (coffee, tea, cacao, yerba mate).

3. **`beverageops.registry`** — independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `plantation-count-non-positive?`,
   `confidence-below-floor?`.

4. **`beverageops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-plantation` lookup, `add-plantation` for tests/simulation.

5. **`beverageops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`beverageops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`beverageops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0122's own stub status).

8. **`beverageops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`; verified end-to-end (`:disposition :escalate`, phase-0 forces
   human review as expected).

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-plantation-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** — 30 tests / 92 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/`sim_test`,
    matching 0122's own test coverage shape). Assertion count is 1 lower
    than 0122's 93 because `beverageops.facts` covers 4 beverage-crop
    classes (coffee/tea/cacao/yerba-mate) instead of 0122's 5 fruit
    classes (mango/banana/papaya/avocado/pineapple).

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`beverageops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0122).

## Consequences

(+) Beverage-crop-growing (ISIC 0127) plantation-operations-coordination
is now genuinely implemented and fully tested.

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
deferred scaffolding, matching 0122's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0127`: `clojure -M:test` → "Ran 30 tests
  containing 92 assertions. 0 failures, 0 errors." `clojure -M:lint` →
  0 errors, 0 warnings. Independently re-verified from a fresh clone of
  `origin/main` after push (identical raw output).
- Commit `6de3dda6cee4e39d8b845688f785edbc8a5a25c8` pushed to
  `cloud-itonami/cloud-itonami-isic-0127`'s `main` (fresh repo,
  root-commit).
- Registry entry (`kotoba-lang/industry`) updated in place: `"0127"`
  entry's `:maturity` `:spec` → `:implemented`, `:repo`/`:business-id`
  set to `cloud-itonami-isic-0127` and this ADR referenced. Landed via
  GitHub API server-side merge commit
  `634e84b7973b53b8dfd08b66460a2cf7f68da941` onto `main` (no 409, landed
  on first attempt). `industry_test.clj`'s `maturity-summary` assertion
  bumped from 219 to 220 — the true count recomputed from a real
  `clojure -M:test`/`(industry/maturity-summary)`-driven call against the
  live `registry.edn` on a freshly re-fetched clone immediately before
  this edit (not an assumed fixed number). Baseline run at that `HEAD`
  was already green at "Ran 15 tests containing 946 assertions. 0
  failures, 0 errors." (219 implemented). `grep -c "â" resources/kotoba/
  industry/registry.edn` → 0 (no file-wide UTF-8 mojibake), both pre- and
  post-merge. Post-merge, `clojure -M:test` from a **completely fresh
  clone** of `kotoba-lang/industry`'s `main` (with `kotoba-lang/
  technology` re-cloned as its `../technology` sibling, per `industry`'s
  own `deps.edn` local/root dependency) → "Ran 15 tests containing 946
  assertions. 0 failures, 0 errors."
