# ADR-2607157000: cloud-itonami-isic-1104 (Manufacture of soft drinks; production of mineral waters and other bottled waters) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1102 (Manufacture of wines -- the reference
module shape this actor mirrors, independently re-verified before use),
cloud-itonami-isic-1072 (Manufacture of sugar -- second regulated-
beverage/food-manufacturing reference), the `kotoba-lang/industry`
registry's `"1104"` catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-C1104` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1104"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1104` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests). Both the placeholder org/name and the real `cloud-itonami` org
target name were independently confirmed 404 via `gh api` before any
scaffolding began. This is part of an ongoing careful, smaller-batch
rollout (one ISIC class per agent, a capable model, mandatory
verification) after a prior 18-agent haiku batch produced a 61% defect
rate (empty implementations, missing modules, false "all green" reports)
on other ISIC classes; 60+ consecutive agents on this stricter protocol
have all succeeded since. This ADR covers ISIC 1104 only, built and
verified from scratch -- no prior `cloud-itonami-isic-1104` repo existed
under the `cloud-itonami` org. The registry entry's `:name` ("Manufacture
of soft drinks") was independently verified against a fresh clone before
any work began, per this fleet's ID/name-mismatch caution, and confirmed
to match the assigned scope (soft-drink and bottled/mineral-water
manufacturing).

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1104` as a soft-drink/bottled-
water-manufacturing PLANT-OPERATIONS COORDINATION actor (not mixing/
carbonation/filling-line control authority), mirroring
`cloud-itonami-isic-1102`'s verified module shape (`facts`/`registry`/
`store`/`governor`/`operation`/`phase`/`advisor`/`sim`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY) module-for-module with fresh, soft-drink/bottled-
water-specific domain logic under the `softdrinkops` namespace:

1. **`softdrinkops.facts`** -- product-type production windows
   (carbonation-level/Brix-sugar-content/preservative/microbial-load/
   fill-volume/mineral-content, by product id: carbonated soft drink,
   still flavored drink, natural mineral water, other bottled/purified
   water -- mineral water and other bottled water permit zero added
   preservative and carry a much stricter microbial-load ceiling, 20
   CFU/mL, than mixed/carbonated soft drinks, 100 CFU/mL, reflecting the
   real difference in thermal-processing/preservative barrier), and
   jurisdiction preservative-declaration and evidence-checklist
   requirements (JP/US/EU, each with a >=1ppm "any functionally-added
   preservative must be declared" threshold -- a stricter, more
   conservative convention than wine's higher naturally-occurring-SO2
   sulfite threshold, reflecting that soft-drink/bottled-water
   preservatives are a deliberate formulation ingredient rather than a
   fermentation byproduct).
2. **`softdrinkops.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify
   physical/operational constraints: carbonation tolerance, Brix range,
   preservative ceiling, microbial-load ceiling, mineral-content floor
   (the minimum total-dissolved-solids threshold a batch must meet to
   legally carry a "mineral water" label claim), filling-line
   calibration age (90-day limit, mirroring wine's bottling-line
   fill-volume-meter interval), fill-volume variance, additive-label
   mismatch, and contamination detection (a dedicated boolean predicate
   so the Governor's check-function shapes stay uniform).
3. **`softdrinkops.store`** -- plain-data store (`{:batches {...}
   :facts [...]}`) with batch lookup/registration/processed/shipment-
   finalized flags and an append-only audit ledger.
4. **`softdrinkops.governor`** -- 18 independently-verified
   hard-violation checks plus a closed operation allowlist as a hard,
   permanent block per the domain design brief: the advisor may only
   ever propose `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct mixing/
   carbonation/filling-line control or food-safety-certification
   authority -- is refused unconditionally (`:op-not-allowed`), never a
   soft escalation. `:flag-food-safety-concern` always escalates to a
   human regardless of confidence, as do the two real actuation events
   (`:log-production-batch`/`:coordinate-shipment`).
5. **`softdrinkops.phase`** -- `:intake -> :mixing -> :carbonation ->
   :filling -> :inspection -> :audit -> :archived`, a soft-drink/
   bottled-water-specific phase sequence (`:carbonation` -- CO2
   injection -- is never directly controlled by this actor, and every
   batch transits the state even for still products).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1102`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).

### What this actor does NOT do

Mixing-tank/carbonator/filling-line equipment operation and food-safety-
certification authority remain exclusive to licensed plant staff and
regulators, permanently, with no actor or human-approval override path
-- enforced structurally by the closed operation allowlist, not just
documented.

## Verification

- `cloud-itonami-isic-1104`: `clojure -M:test` -- raw final line: `Ran
  58 tests containing 195 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Independently re-verified: fresh `git clone --depth 1` into a new
  scratch directory after push, re-ran `clojure -M:test` against the
  clean clone -- same green result (`Ran 58 tests containing 195
  assertions.` / `0 failures, 0 errors.`).
- Repo created fresh (`gh api orgs/cloud-itonami/repos` + push), commit
  `abc9768` on `cloud-itonami-isic-1104`'s `main` (initial commit, no
  prior history); confirmed landed on `origin/main` via GitHub API
  ref comparison before proceeding.
- `kotoba-lang/industry` registry `"1104"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1104` placeholder to
  `cloud-itonami/cloud-itonami-isic-1104`, `:maturity` `:spec` ->
  `:implemented`); `test/kotoba/industry_test.clj`'s `maturity-summary`
  assertion bumped from the live-recomputed 250 to 251 implemented
  entries (recomputed via `(kotoba.industry/maturity-summary)` on a
  freshly re-fetched `origin/main` both immediately before the edit and
  again immediately after, not assumed); full `kotoba-lang/industry`
  suite re-run green post-edit (`Ran 15 tests containing 952
  assertions.` / `0 failures, 0 errors.`) and again post-merge against a
  brand-new fresh clone (same result, merge commit
  `ca91036ffdd4f81515fe88e183a8735e910d9a05` confirmed identical to
  `origin/main` via GitHub API compare, `ahead_by`/`behind_by` both 0).
  `grep -c "â" resources/kotoba/industry/registry.edn` confirmed 0
  (no mojibake) both pre- and post-merge.

## Consequences

(+) `cloud-itonami-isic-1104` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"1104"` entry promoted to
`:maturity :implemented`, count 250 -> 251.
(-) `softdrinkops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `softdrinkops.operation/run-operation`
takes an already-formed proposal plus an injected `governor-fn` rather
than internally invoking an advisor. This matches
`cloud-itonami-isic-1102`'s own current shape and is a natural,
contained future extension, not required for this ADR's verification
bar.
