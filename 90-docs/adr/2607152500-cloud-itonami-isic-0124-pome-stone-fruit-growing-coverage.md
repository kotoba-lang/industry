# ADR-2607152500: cloud-itonami-isic-0124 (growing of pome fruits and stone fruits) pome-stone-fruit-orchard-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage),
ADR-2607172000 (cloud-itonami-isic-0123, module-structure template)

## Context

This is part of an ongoing careful, smaller-batch rollout (capable model +
mandatory verification per agent) after a prior 18-agent haiku batch had a
61% defect rate (empty implementations, missing modules, false "all
green" claims). 48+ consecutive agents on this stricter protocol have
succeeded before this one. Built by reading `cloud-itonami-isic-0123`
(growing of citrus fruits, 30 tests / 92 assertions) in full as the
module-structure template, with `clojure -M:test` independently re-run
from fresh clones both before and after push.

Before any implementation work, the `kotoba-lang/industry` registry entry
was independently re-verified (fresh clone, not trusted from the task
assignment): `{:id "0124" :name "Growing of pome fruits and stone
fruits" ...}` is genuinely what is registered — no ID/name mismatch
(several prior agents in this fleet had mislabeled their assigned ISIC
class, e.g. 0892 assumed=salt actually=peat, 0144 assumed=swine
actually=sheep-goats). A fresh `gh api` check also confirmed no prior
repository existed at either `cloud-itonami/cloud-itonami-isic-0124` or
the stale placeholder `gftdcojp/cloud-itonami-A0124` referenced by the
old registry entry (404 confirmed).

Pome/stone-fruit-growing operations (ISIC Rev. 4 0124, covering apple,
pear, quince (pome fruits) and peach, plum, cherry, apricot (stone
fruits) orchards) span: orchard/block record logging (planting, harvest
yield, brix testing), field-operation (pruning/thinning/spraying/
harvest) scheduling, crop health/pest (e.g. codling moth)/disease (e.g.
fire blight)/frost-damage concern escalation, and seedling/fertilizer/
equipment procurement. **CRITICAL exclusions**: direct field-equipment
operation and finalizing a spray-application decision remain the
exclusive authority of the grower/agronomist — this actor only
coordinates back-office record-keeping and logistics, never field
actuation or agronomic decision authority.

## Decision

Implement a complete pome-stone-fruit-orchard-operations-coordination
actor (`cloud-itonami-isic-0124`), mirroring
`cloud-itonami-isic-0123`'s module shape module-for-module (namespace
`pomestoneops.*`):

1. **`pomestoneops.governor`** (`PomeStoneOperationsGovernor`) —
   independent constraint layer with HARD checks (always hold, no
   override):
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
   - `:flag-crop-health-concern` — ALWAYS escalates, any confidence
     (e.g. codling moth, fire blight, frost damage).
   - `:order-supplies` above its category cost threshold (default 500;
     `pomestoneops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`pomestoneops.facts`** — reference data (pure, deterministic):
   supply categories with cost thresholds (seedling, fertilizer,
   equipment) and fruit-class classification (apple/pear/quince = pome;
   peach/plum/cherry/apricot = stone).

3. **`pomestoneops.registry`** — independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `orchard-count-non-positive?`,
   `confidence-below-floor?`.

4. **`pomestoneops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-orchard` lookup, `add-orchard` for tests/simulation.

5. **`pomestoneops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`pomestoneops.phase`** — 0→3 rollout phase gate: phase-0 forces
   every would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`pomestoneops.operation`** — composes advisor → governor →
   phase-gate into one synchronous operation run (langgraph-clj
   StateGraph wiring deferred, matching 0123's own stub status).

8. **`pomestoneops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`; verified end-to-end (`:disposition :escalate`, phase-0 forces
   human review as expected).

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-orchard-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** — 31 tests / 105 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0123's own test coverage shape). One extra
    `deftest` versus 0123's 30 (`governor-test/escalation-fire-blight-
    concern`, exercising the fire-blight-specific crop-health-concern
    escalation path called out in the domain design) plus wider
    `facts-test` coverage (7 pome/stone fruit classes with a pome/stone
    `:group` assertion, vs. 0123's 4 citrus classes with no group
    dimension) account for the higher counts.

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`pomestoneops.operation`'s real `run-operation`/`build` functions
    and `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn
    metadata; standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0123).

## Consequences

(+) Pome/stone-fruit-growing (ISIC 0124) orchard-operations-coordination
is now genuinely implemented and fully tested.

(+) Scope boundaries (direct field-equipment operation, finalizing a
spray-application decision permanently excluded) are hardcoded in
governor checks (`field-equipment-or-spray-blocked`) and documented in
README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern`, e.g. codling
moth, fire blight, always human) is a core design invariant, not an
add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0123's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0124`: `clojure -M:test` → "Ran 31 tests
  containing 105 assertions. 0 failures, 0 errors." `clojure -M:lint` →
  0 errors, 0 warnings.
- Commit `4e1ac3cfa10b74faa7dca7f99aceabb555e5d7db` pushed to
  `cloud-itonami/cloud-itonami-isic-0124`'s `main` (fresh repo,
  root-commit). Independently confirmed via `gh api
  repos/cloud-itonami/cloud-itonami-isic-0124/commits` matching the
  local commit SHA.
- Registry entry (`kotoba-lang/industry`) updated in place: `"0124"`
  entry's `:maturity` `:spec` → `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-A0124` placeholder to
  `cloud-itonami/cloud-itonami-isic-0124`, and this ADR referenced.
  `industry_test.clj`'s `maturity-summary` assertion bumped 238 → 239
  (the true count recomputed live via `(industry/maturity-summary)`,
  not assumed, on a freshly re-fetched `origin/main` — the first landing
  attempt hit a genuine 409 merge conflict from concurrent-fleet activity
  [another agent's `0143` promotion landed in between]; re-fetched
  `origin/main`, re-derived the count live [237 → 238 baseline already
  present from that other promotion, confirmed still green pre-edit],
  reapplied the same textual edit with the new 238 → 239 delta, and
  re-ran the full suite before pushing again).
  `grep -c "â" resources/kotoba/industry/registry.edn` → 0 (no
  file-wide UTF-8 mojibake, both before and after merge). Landed via
  GitHub API server-side merge commit
  `1ac2cc0402ba32e49407bcb9e40a810f8c4922d2` onto `main` (succeeded on
  the second landing attempt). `gh api
  repos/kotoba-lang/industry/compare/1ac2cc0402ba32e49407bcb9e40a810f8c4922d2...main`
  → `{"status":"identical","ahead_by":0,"behind_by":0}`, confirming the
  merge commit is exactly `main`'s tip. Stale/merged promotion branches
  deleted. Post-merge, `clojure -M:test` from a **completely fresh
  clone** of `kotoba-lang/industry`'s `main` (with `kotoba-lang/technology`
  re-cloned as its `../technology` sibling, per `industry`'s own
  `deps.edn` local/root dependency) → "Ran 15 tests containing 950
  assertions. 0 failures, 0 errors." A separate, independent fresh clone
  of `cloud-itonami/cloud-itonami-isic-0124` itself (commit
  `4e1ac3cfa10b74faa7dca7f99aceabb555e5d7db`) also re-confirmed "Ran 31
  tests containing 105 assertions. 0 failures, 0 errors."
