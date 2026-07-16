# ADR-2607151330: cloud-itonami-isic-0126 (growing of oleaginous fruits) oleaginous-fruit-plantation-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage),
ADR-2607181600 (cloud-itonami-isic-0125, module-structure template)

## Context

This is part of an ongoing careful, smaller-batch rollout (capable model +
mandatory verification per agent) after a prior 18-agent haiku batch had a
61% defect rate (empty implementations, missing modules, false "all
green" claims). 60+ consecutive agents on this stricter protocol have
succeeded before this one. Built by reading `cloud-itonami-isic-0125`
(growing of other tree and bush fruits and nuts, 31 tests / 105
assertions) in full as the module-structure template, with
`clojure -M:test` independently re-run from fresh clones both before and
after push.

Before any implementation work, the `kotoba-lang/industry` registry entry
was independently re-verified (fresh clone, not trusted from the task
assignment): `{:id "0126" :name "Growing of oleaginous fruits" ...}` is
genuinely what is registered — no ID/name mismatch (several prior agents
in this fleet had mislabeled their assigned ISIC class, e.g. 0892
assumed=salt actually=peat, 0144 assumed=swine actually=sheep-goats). A
fresh `gh api` check also confirmed no prior repository existed at either
`cloud-itonami/cloud-itonami-isic-0126` or the stale placeholder
`gftdcojp/cloud-itonami-A0126` referenced by the old registry entry (404
confirmed).

Oleaginous-fruit-growing operations (ISIC Rev. 4 0126, covering fruit and
nuts grown mainly for oil extraction: oil palm, olive, coconut, and other
oil-bearing fruit trees such as candlenut) span: plantation-block record
logging (planting, harvest yield, oil-content test), field-operation
(pruning/spraying/harvest) scheduling, crop health/pest/disease (e.g. bud
rot/Ganoderma boninense) and drought-stress concern escalation, and
seedling/fertilizer/equipment procurement. **CRITICAL exclusions**: direct
field-equipment operation and finalizing a spray-application decision
remain the exclusive authority of the grower/agronomist — this actor only
coordinates back-office record-keeping and logistics, never field
actuation or agronomic decision authority.

## Decision

Implement a complete oleaginous-fruit-plantation-operations-coordination
actor (`cloud-itonami-isic-0126`), mirroring `cloud-itonami-isic-0125`'s
module shape module-for-module (namespace `oleaginousops.*`):

1. **`oleaginousops.governor`** (`OleaginousOperationsGovernor`) —
   independent constraint layer with HARD checks (always hold, no
   override):
   - `plantation-not-registered` — request's plantation-id must resolve
     to a registered plantation block in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`; the
     governor never directly executes anything.
   - `field-equipment-or-spray-blocked` — `:operate-field-equipment` and
     `:finalize-spray-application` are unconditionally, permanently
     blocked regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `plantation-count-invalid` — `:log-plantation-record` with a
     non-positive logged quantity (trees counted / harvest weight /
     oil-content-test yield / bunch-fruit count) is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` — ALWAYS escalates, any confidence
     (e.g. bud rot/Ganoderma boninense, drought-stress).
   - `:order-supplies` above its category cost threshold (default 500;
     `oleaginousops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`oleaginousops.facts`** — reference data (pure, deterministic):
   supply categories with cost thresholds (seedling, fertilizer,
   equipment) and fruit-class classification (oil-palm/coconut = palm;
   olive = drupe; candlenut = nut).

3. **`oleaginousops.registry`** — independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `plantation-count-non-positive?`,
   `confidence-below-floor?`.

4. **`oleaginousops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-plantation` lookup, `add-plantation` for tests/simulation.

5. **`oleaginousops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`oleaginousops.phase`** — 0→3 rollout phase gate: phase-0 forces
   every would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`oleaginousops.operation`** — composes advisor → governor →
   phase-gate into one synchronous operation run (langgraph-clj StateGraph
   wiring deferred, matching 0125's own stub status).

8. **`oleaginousops.sim`** — demo runner (`clojure -M:run`) with a
   working `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-plantation-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** — 31 tests / 99 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0125's own test coverage shape exactly — same
    op set, same governor rule count, same 15 governor-test cases
    swapping the spotted-wing-drosophila/walnut-blight escalation cases
    for bud-rot/Ganoderma and drought-stress ones; a smaller 4-entry
    fruit-class facts table (vs 0125's 7) accounts for the 105 → 99
    assertion-count difference).

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`oleaginousops.operation`'s real `run-operation`/`build` functions
    and `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn
    metadata; standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0125).

## Consequences

(+) Oleaginous-fruit-growing (ISIC 0126) plantation-operations-
coordination is now genuinely implemented and fully tested.

(+) Scope boundaries (direct field-equipment operation, finalizing a
spray-application decision permanently excluded) are hardcoded in
governor checks (`field-equipment-or-spray-blocked`) and documented in
README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern`, e.g. bud
rot/Ganoderma boninense, drought-stress, always human) is a core design
invariant, not an add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0125's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0126`: `clojure -M:test` → "Ran 31 tests
  containing 99 assertions. 0 failures, 0 errors." `clojure -M:lint` →
  0 errors, 0 warnings. `clojure -M:run` demo produced the expected
  `:escalate` disposition (phase-0 forces human review of all commits).
- Commit `36b768b0e8d335902376441a7386c4131077b986` pushed to
  `cloud-itonami/cloud-itonami-isic-0126`'s `main` (fresh repo,
  root-commit). Independently confirmed via `gh api
  repos/cloud-itonami/cloud-itonami-isic-0126/commits/main` matching the
  local commit SHA.
- Registry entry (`kotoba-lang/industry`) updated in place: `"0126"`
  entry's `:maturity` `:spec` → `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-A0126` placeholder to
  `cloud-itonami/cloud-itonami-isic-0126`, this ADR referenced, and
  `:required-technologies`/`:operating-states` trimmed to the
  implemented-tier convention (`[:robotics :identity :forms
  :audit-ledger]`, matching sibling implemented entries).
  `industry_test.clj`'s `maturity-summary` assertion bumped 249 → 250
  (the true count recomputed live via `(industry/maturity-summary)` on a
  freshly re-fetched `origin/main` immediately before the edit — landed
  cleanly on the first attempt, no concurrent-fleet 409); a new
  `:implemented`-tier `(is (= :implemented (industry/maturity "0126")))`
  testing block was also added, mirroring the existing per-ISIC-class
  test pattern. `grep -c "â" resources/kotoba/industry/registry.edn` → 0
  (no file-wide UTF-8 mojibake, both before and after merge). Landed via
  GitHub API server-side merge commit
  `d7c53c0878746ad81a26d9c1484daa66a97acf38` onto `main`. `gh api
  repos/kotoba-lang/industry/compare/d7c53c0878746ad81a26d9c1484daa66a97acf38...main`
  → `{"status":"identical","ahead_by":0,"behind_by":0}`, confirming the
  merge commit is exactly `main`'s tip. Merged branch deleted. Post-merge,
  `clojure -M:test` from a **completely fresh clone** of
  `kotoba-lang/industry`'s `main` (with `kotoba-lang/technology`
  re-cloned as its `../technology` sibling, per `industry`'s own
  `deps.edn` local/root dependency) → "Ran 15 tests containing 951
  assertions. 0 failures, 0 errors." A separate, independent fresh clone
  of `cloud-itonami/cloud-itonami-isic-0126` itself (commit
  `36b768b0e8d335902376441a7386c4131077b986`) also re-confirmed "Ran 31
  tests containing 99 assertions. 0 failures, 0 errors."
