# ADR-2607152400: cloud-itonami-isic-1061 (Manufacture of grain mill products) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1071 (Bakery products -- the reference
module shape this actor mirrors, independently re-verified before use),
cloud-itonami-isic-1050 (Dairy products -- second food-manufacturing
reference), the `kotoba-lang/industry` registry's `"1061"` catalog entry
(previously `:spec` with a placeholder `gftdcojp/cloud-itonami-C1061`
repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1061"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1061` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests). This is part of a smaller, more careful redo batch (6 agents,
one ISIC class each, a more capable model, mandatory verification) after
a prior 18-agent batch on a weaker model produced a 61% defect rate
(empty implementations, missing modules, false "all green" reports) on
other ISIC classes. This ADR covers ISIC 1061 only, built and verified
from scratch -- no prior `cloud-itonami-isic-1061` repo existed under the
`cloud-itonami` org.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1061` as a grain-mill-products
manufacturing PLANT-OPERATIONS COORDINATION actor (not milling-line
control authority), mirroring `cloud-itonami-isic-1071`'s verified module
shape (`facts`/`registry`/`store`/`governor`/`operation`/`phase`/
`advisor`/`sim`, `deps.edn`/`blueprint.edn`/README/GOVERNANCE/
CODE_OF_CONDUCT/CONTRIBUTING/SECURITY) with fresh, grain-mill-specific
domain logic under the `millops` namespace:

1. **`millops.facts`** -- product-type milling windows (moisture/
   ash-content/granulation/mycotoxin-max-ppb, by product id: white wheat
   flour, whole wheat flour, durum semolina, corn meal -- corn
   deliberately carries a much stricter mycotoxin ceiling, 20 ppb vs.
   1000 ppb, reflecting the real regulatory asymmetry between aflatoxin
   action levels for corn and DON/vomitoxin action levels for wheat),
   jurisdiction allergen-declaration and evidence-checklist requirements
   (JP/US/EU), and a per-grain-source allergen table (wheat/durum/rye/
   barley all map to the `:wheat` allergen id; `:oat/hulled` carries no
   primary allergen of its own but a real `:wheat` cross-contact risk,
   reflecting the actual gluten-free-oat shared-milling-line hazard).
2. **`millops.registry`** -- pure, host-clock-free validation predicates
   the Governor uses to independently verify physical/operational
   constraints: moisture tolerance, mycotoxin-level ceiling, ash-content
   bounds, granulation bounds, magnet/metal-detection calibration age
   (90-day limit -- shorter than a generic scale-calibration interval,
   reflecting the higher consequence of a missed metal-detection fault),
   weight variance, allergen-label risk, and foreign-material detection
   (a dedicated boolean predicate so the Governor's check-function shapes
   stay uniform).
3. **`millops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`millops.governor`** -- 17 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct milling-line
   (roller mill/sifter/purifier) control or food-safety certification
   authority -- is refused unconditionally (`:op-not-allowed`), never a
   soft escalation. `:flag-food-safety-concern` always escalates to a
   human regardless of confidence, as do the two real actuation events
   (`:log-production-batch`/`:coordinate-shipment`).
5. **`millops.phase`** -- `:intake -> :clean -> :mill -> :inspect ->
   :package -> :audit -> :archived`, a grain-mill-specific phase
   sequence (`:clean` = grain cleaning/destoning/moisture-conditioning
   ahead of the roller mills, distinct from bakery's `:design` phase).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1071`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).

### What this actor does NOT do

Roller-mill/sifter/purifier equipment operation and food-safety
certification authority remain exclusive to licensed grain-mill plant
staff and regulators, permanently, with no actor or human-approval
override path -- enforced structurally by the closed operation
allowlist, not just documented.

### A portability fix found while mirroring the reference

`cloud-itonami-isic-1071/src/bakeryops/phase.cljc` (the mirrored
reference) uses `(.indexOf phase-sequence from-phase)` on a plain
Clojure vector -- `java.util.List/indexOf` compiles and passes under JVM
Clojure (which is all `clojure -M:test` exercises) but is JVM-only
interop: ClojureScript's `PersistentVector` does not implement a
`.indexOf` JS method, so this would fail to compile under `cljs`. Per
this workspace's mandatory cljs-first/portable-`.cljc` rule,
`millops.phase` replaces it with a portable `index-of` helper
(`keep-indexed` + `first`) that behaves identically on both platforms.
This divergence from the reference is intentional and should be
back-ported to `bakeryops.phase` as a follow-up, not treated as a defect
in this ADR's own implementation.

## Verification

- `cloud-itonami-isic-1061`: `clojure -M:test` -- raw final line: `Ran
  49 tests containing 160 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Independently re-verified: fresh `git clone --depth 1` into a new
  scratch directory after push, re-ran `clojure -M:test` against the
  clean clone -- same green result (`Ran 49 tests containing 160
  assertions.` / `0 failures, 0 errors.`).
- Repo created fresh (`gh repo create --source=. --push`), commit
  `82df7c4` on `cloud-itonami-isic-1061`'s `main` (initial commit, no
  prior history).
- `kotoba-lang/industry` registry `"1061"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1061` placeholder to
  `cloud-itonami/cloud-itonami-isic-1061`, `:maturity` `:spec` ->
  `:implemented`), `test/kotoba/industry_test.clj`'s `maturity-summary`
  assertion bumped from the live-recomputed 193 to 194 implemented
  entries; full `kotoba-lang/industry` suite re-run green post-edit and
  again post-merge against a fresh clone (see registry PR/merge commit
  for raw output).

## Consequences

(+) `cloud-itonami-isic-1061` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"1061"` entry promoted to
`:maturity :implemented`, count 193 -> 194.
(+) `millops.phase`'s portable `index-of` helper is a small, reusable
fix pattern for the same latent JVM-interop issue in `bakeryops.phase`
and any other `.cljc` phase module copied from that lineage.
(-) `millops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `millops.operation/run-operation` takes
an already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-1071`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.
