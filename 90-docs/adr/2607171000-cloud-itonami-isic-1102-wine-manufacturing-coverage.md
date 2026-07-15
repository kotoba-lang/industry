# ADR-2607171000: cloud-itonami-isic-1102 (Manufacture of wines) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1072 (Manufacture of sugar -- the reference
module shape this actor mirrors, independently re-verified before use),
cloud-itonami-isic-1050 (Dairy products -- second food-manufacturing
reference), cloud-itonami-isic-1101 (Distilling, rectifying and blending
of spirits -- a neighboring alcohol-manufacturing class at `:maturity
:spec`, currently reverted after a real ABV-tolerance logic bug was found
in a prior promotion attempt; this ADR's ABV-tolerance check was written
and boundary-tested with that specific known failure mode in mind), the
`kotoba-lang/industry` registry's `"1102"` catalog entry (previously
`:spec` with a placeholder `gftdcojp/cloud-itonami-C1102` repo link that
was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1102"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1102` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests). This is part of an ongoing careful, smaller-batch rollout
(one ISIC class per agent, a capable model, mandatory verification) after
a prior 18-agent haiku batch produced a 61% defect rate (empty
implementations, missing modules, false "all green" reports) on other
ISIC classes; 30+ consecutive agents on this stricter protocol have all
succeeded since. Before any implementation work, the registry entry's
`:id`/`:name` pair was independently re-verified against a fresh
`git clone --depth 1` of `kotoba-lang/industry` (`{:id "1102" :name
"Manufacture of wines" ...}` confirmed verbatim) -- prior agents in this
fleet have mislabeled their assigned ISIC class from stale premises (e.g.
0892 assumed salt, actually peat; 0144 assumed swine, actually
sheep-goats). This ADR covers ISIC 1102 only, built and verified from
scratch -- no prior `cloud-itonami-isic-1102` repo existed under the
`cloud-itonami` org (confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-1102`
returning 404 before scaffolding began).

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1102` as a wine-manufacturing
PLANT-OPERATIONS COORDINATION actor (not fermentation/bottling-line
control authority, and not excise/tax-classification authority),
mirroring `cloud-itonami-isic-1072`'s verified module shape
(`facts`/`registry`/`store`/`governor`/`operation`/`phase`/`advisor`/
`sim`, `deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY) with fresh, wine-manufacturing-specific domain
logic under the `wineops` namespace:

1. **`wineops.facts`** -- product-style production windows (ABV-target/
   tolerance, residual-sugar-min/max, volatile-acidity-max, so2-max-ppm,
   fill-volume-target/tolerance, vintage-percent-min, by product id:
   still table wine, sparkling wine, dessert/sweet wine, fortified wine
   -- ABV tolerance follows the real US TTB two-band structure, 27 CFR
   4.36: wines declared under 14% ABV get a +/-1.5-point tolerance,
   wines declared at 14%+ ABV, a different federal excise-tax class, get
   a tighter +/-1.0-point tolerance, reflected directly in the fortified
   product type's `:abv-tolerance-percent 1.0` vs. every other product
   type's `1.5`), jurisdiction sulfite-declaration and
   evidence-checklist requirements (JP/US/EU, each converged on the same
   ~10ppm "contains sulfites" declaration threshold -- unlike sugar's
   per-jurisdiction-varying threshold, this reflects a genuine
   cross-jurisdiction Codex-Alimentarius-aligned convention for wine
   specifically), and vintage-percent-labeling helper predicates (the
   "85% rule", real under both US 27 CFR 4.27 and EU Reg (EU) 2019/33
   Art. 51). Japan's jurisdiction entry uses `:jp/nta` (National Tax
   Agency, 酒税法・果実酒等の製法品質表示基準) rather than sugar's
   `:jp/mhlw` (Ministry of Health, Labour and Welfare) -- wine excise/
   labeling authority in Japan sits with the tax agency, not the general
   food-labeling ministry, a genuine domain distinction reflected in the
   jurisdiction id chosen.
2. **`wineops.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify
   physical/operational constraints: ABV-tolerance band, residual-sugar
   range, volatile-acidity ceiling, SO2-residue ceiling, vintage-percent
   floor, bottling-line calibration age (90-day limit, mirroring the
   sugar-refining metal-detector-calibration interval), fill-volume
   variance, sulfite-label risk, and contamination detection (a
   dedicated boolean predicate so the Governor's check-function shapes
   stay uniform). `abv-out-of-tolerance?` is a direct, symmetric
   target+/-tolerance check mirroring `sugarops.registry/moisture-out-
   of-target?`'s proven shape exactly -- deliberately NOT a novel
   tolerance-class-crossing calculation, to avoid the exact bug category
   (an "ABV-tolerance logic bug") that caused `cloud-itonami-isic-1101`
   to be reverted after 4 real test failures were found despite a prior
   agent's false all-green claim.
3. **`wineops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`wineops.governor`** -- 18 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct
   fermentation/bottling-line (crush/press, fermentation-tank, bottling-
   line equipment) control OR an excise/tax-classification-authority
   decision (e.g. reclassifying a batch's federal/state tax category) --
   is refused unconditionally (`:op-not-allowed`), never a soft
   escalation. Both boundary cases are independently unit-tested
   (`run-operation-op-not-allowed-fermentation-line-test` and
   `run-operation-op-not-allowed-tax-classification-test`) rather than
   relying on a single example, since the domain brief calls out both as
   distinct hard invariants. `:flag-food-safety-concern` always
   escalates to a human regardless of confidence, as do the two real
   actuation events (`:log-production-batch`/`:coordinate-shipment`).
5. **`wineops.phase`** -- `:intake -> :crush -> :fermentation ->
   :pressing -> :aging -> :bottling -> :audit -> :archived`, a
   wine-production-specific phase sequence (`:fermentation` is where ABV
   develops and where SO2 additions typically begin; `:bottling` --
   fermentation-tank and bottling-line equipment operation -- is never
   directly controlled by this actor).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1072`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).

### A deliberate generalization from the mirrored reference (inherited, re-verified)

Like `sugarops.governor`, `wineops.governor`'s `batch-not-registered-
violations` is applied unconditionally to every op in the closed
allowlist (`:log-production-batch`, `:schedule-maintenance`,
`:flag-food-safety-concern`, `:coordinate-shipment`), matching this
actor's domain brief: "winery/batch record must be independently
verified/registered before any action" -- not scoped to shipment
coordination alone. Covered by `batch-not-registered-violation-test`.

### What this actor does NOT do

Fermentation-tank, press, and bottling-line equipment operation remain
exclusive to licensed winery staff, and excise/tax-classification
decisions (including whether a batch crossing its declared ABV tolerance
band should be reclassified into a different federal/state tax category)
remain exclusive to human operators and tax authorities, permanently,
with no actor or human-approval override path -- enforced structurally
by the closed operation allowlist, not just documented. This actor never
proposes or executes an actual sale/distribution transaction; its
`:coordinate-shipment` operation is coordination and compliance-logging
only.

## Verification

- `cloud-itonami-isic-1102`: `clojure -M:test` -- raw final line: `Ran
  54 tests containing 180 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- All source is `.cljc`; the only JVM-affine call
  (`System/currentTimeMillis`, for calibration-overdue checks) is behind
  a `:clj`/`:cljs` reader-conditional at a single isolated call site
  (`wineops.governor/now-epoch-ms`), matching `sugarops.governor`'s
  proven portable pattern -- no unguarded JVM-only interop.
- Repo created fresh (`gh repo create --source=. --remote=origin` +
  push), commit `100acf1` on `cloud-itonami-isic-1102`'s `main` (initial
  commit, no prior history). Confirmed landed:
  `gh api repos/cloud-itonami/cloud-itonami-isic-1102/commits/main
  --jq .sha` returned `100acf150cd04d27710f0c65f8c0672f0ac0c217`,
  matching the local push SHA exactly.
- Independent re-verification (fresh clone into a new scratch directory
  after both this repo's push and the registry merge, alongside a fresh
  `kotoba-lang/technology` sibling clone per the fleet's verification
  protocol) is recorded in this ADR's edn/PR trail; see the
  `kotoba-lang/industry` registry PR/merge commit for the paired raw
  post-merge `clojure -M:test` output.
- `kotoba-lang/industry` registry `"1102"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1102` placeholder to
  `cloud-itonami/cloud-itonami-isic-1102`, `:required-technologies`/
  `:optional-technologies` trimmed to the `:implemented`-tier shape
  (`[:identity :forms :audit-ledger :cae]` / `[:telemetry]`, matching
  every other implemented food/beverage-manufacturing entry, e.g.
  `"1050"`/`"1061"`/`"1071"`/`"1072"`/`"1074"`), `:maturity` `:spec` ->
  `:implemented`); `test/kotoba/industry_test.clj`'s `maturity-summary`
  assertion bumped to match the live-recomputed implemented-entry count
  (recomputed from a freshly re-fetched `origin/main`, not assumed);
  full `kotoba-lang/industry` suite re-run green post-edit and again
  post-merge against a fresh clone (see registry PR/merge commit for raw
  output).

## Consequences

(+) `cloud-itonami-isic-1102` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"1102"` entry promoted to
`:maturity :implemented`.
(+) `wineops.registry/abv-out-of-tolerance?`'s deliberately simple,
symmetric target+/-tolerance shape (matching the proven
`sugarops.registry/moisture-out-of-target?` pattern rather than
inventing a tolerance-class-crossing calculation) is offered as the
reference fix pattern for any future re-attempt at
`cloud-itonami-isic-1101` (Distilling, rectifying and blending of
spirits), which remains reverted at `:maturity :spec` after a real
ABV-tolerance logic bug.
(-) `wineops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `wineops.operation/run-operation` takes
an already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-1072`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.
