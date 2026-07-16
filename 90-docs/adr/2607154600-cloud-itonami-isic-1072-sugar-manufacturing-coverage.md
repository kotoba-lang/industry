# ADR-2607154600: cloud-itonami-isic-1072 (Manufacture of sugar) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1061 (Grain mill products -- the reference
module shape this actor mirrors, independently re-verified before use),
cloud-itonami-isic-1050 (Dairy products -- second food-manufacturing
reference), the `kotoba-lang/industry` registry's `"1072"` catalog entry
(previously `:spec` with a placeholder `gftdcojp/cloud-itonami-C1072`
repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1072"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1072` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests). This is part of an ongoing careful, smaller-batch rollout
(one ISIC class per agent, a capable model, mandatory verification) after
a prior 18-agent haiku batch produced a 61% defect rate (empty
implementations, missing modules, false "all green" reports) on other
ISIC classes; 18+ consecutive agents on this stricter protocol have all
succeeded since. This ADR covers ISIC 1072 only, built and verified from
scratch -- no prior `cloud-itonami-isic-1072` repo existed under the
`cloud-itonami` org.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1072` as a sugar-manufacturing
PLANT-OPERATIONS COORDINATION actor (not crystallization/refining-line
control authority), mirroring `cloud-itonami-isic-1061`'s verified module
shape (`facts`/`registry`/`store`/`governor`/`operation`/`phase`/
`advisor`/`sim`, `deps.edn`/`blueprint.edn`/README/GOVERNANCE/
CODE_OF_CONDUCT/CONTRIBUTING/SECURITY) with fresh, sugar-refining-specific
domain logic under the `sugarops` namespace:

1. **`sugarops.facts`** -- product-type refining windows (moisture/
   polarization-min/color-max-icumsa/ash-content-max/so2-max-ppm/
   granulation, by product id: refined white sugar, raw cane sugar,
   refined beet sugar, brown/soft sugar -- refined grades demand a
   much higher minimum sucrose purity, 99.7-99.8% pol, than raw/brown
   grades, 96.0%/89.0% respectively, reflecting the real difference in
   refining depth), jurisdiction sulfite-declaration and
   evidence-checklist requirements (JP/US/EU, each with a distinct
   `sulfite-declaration-threshold-ppm` reflecting differing regulatory
   thresholds -- JP 30ppm vs. US/EU 10ppm), and sulfite-declaration
   helper predicates driven by the batch's actual SO2 residue rather
   than a raw-material composition table (unlike grain milling's
   grain-source allergen table, sugar's declaration-triggering hazard
   is a process residue, not a formulation ingredient).
2. **`sugarops.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify
   physical/operational constraints: moisture tolerance, polarization
   floor, color ceiling, ash-content ceiling, SO2-residue ceiling,
   granulation bounds, metal-detector calibration age (90-day limit,
   mirroring the grain-mill magnet-calibration interval), weight
   variance, sulfite-label risk, and foreign-material detection (a
   dedicated boolean predicate so the Governor's check-function shapes
   stay uniform).
3. **`sugarops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`sugarops.governor`** -- 19 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct
   crystallization/refining-line (vacuum-pan evaporator/crystallizer/
   centrifuge) control or food-safety certification authority -- is
   refused unconditionally (`:op-not-allowed`), never a soft
   escalation. `:flag-food-safety-concern` always escalates to a human
   regardless of confidence, as do the two real actuation events
   (`:log-production-batch`/`:coordinate-shipment`).
5. **`sugarops.phase`** -- `:intake -> :extraction -> :clarification ->
   :crystallization -> :centrifuge -> :package -> :audit -> :archived`,
   a sugar-refining-specific phase sequence (`:clarification` is where
   SO2 residue originates via the sulfitation step of juice
   purification; `:crystallization` -- vacuum-pan evaporation/
   crystallization -- is never directly controlled by this actor).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1061`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).

### A deliberate generalization from the mirrored reference

`millops.governor`'s `shipment-batch-not-registered-violations` only
checks batch registration for `:coordinate-shipment`. This actor's
domain design brief states the HARD invariant more broadly: "plant/batch
record must be independently verified/registered before any action" --
not scoped to shipment coordination alone. `sugarops.governor`
implements this as `batch-not-registered-violations`, applied
unconditionally to every op in the closed allowlist (`:log-
production-batch`, `:schedule-maintenance`, `:flag-food-safety-concern`,
`:coordinate-shipment`), each covered by its own test
(`batch-not-registered-violation-test`). This is an intentional
broadening, not a mirroring gap.

### What this actor does NOT do

Vacuum-pan evaporator/crystallizer/centrifuge equipment operation and
food-safety certification authority remain exclusive to licensed
sugar-refinery plant staff and regulators, permanently, with no actor or
human-approval override path -- enforced structurally by the closed
operation allowlist, not just documented.

## Verification

- `cloud-itonami-isic-1072`: `clojure -M:test` -- raw final line: `Ran
  55 tests containing 179 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Independently re-verified: fresh `git clone --depth 1` into a new
  scratch directory after push, re-ran `clojure -M:test` against the
  clean clone -- same green result (`Ran 55 tests containing 179
  assertions.` / `0 failures, 0 errors.`).
- Repo created fresh (`gh repo create --source=. --remote=origin` +
  push), commit `f4c73dc` on `cloud-itonami-isic-1072`'s `main` (initial
  commit, no prior history).
- `kotoba-lang/industry` registry `"1072"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1072` placeholder to
  `cloud-itonami/cloud-itonami-isic-1072`, `:required-technologies`/
  `:optional-technologies` trimmed to the `:implemented`-tier shape
  (`[:identity :forms :audit-ledger :cae]` / `[:telemetry]`, matching
  every other implemented food-manufacturing entry), `:maturity` `:spec`
  -> `:implemented`); `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion bumped from the live-recomputed 205 to
  206 implemented entries (recomputed via a fresh `clojure -M:test` run
  against a freshly re-fetched `origin/main`, not assumed); full
  `kotoba-lang/industry` suite re-run green post-edit and again
  post-merge against a fresh clone (see registry PR/merge commit for raw
  output).

## Consequences

(+) `cloud-itonami-isic-1072` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"1072"` entry promoted to
`:maturity :implemented`, count 205 -> 206.
(+) `sugarops.governor`'s generalized `batch-not-registered-violations`
(applied to every allowed op, not only shipment coordination) is a
reusable pattern candidate for other cloud-itonami actors whose domain
brief states the same broader invariant.
(-) `sugarops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `sugarops.operation/run-operation` takes
an already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-1061`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.
