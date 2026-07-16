# ADR-2607193000: cloud-itonami-isic-1103 (Manufacture of malt liquors and malt) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1102 (Manufacture of wines -- primary
reference module shape, its ABV-tolerance/excise-tax-classification
framing directly mirrored, independently re-verified before use),
cloud-itonami-isic-1104 (Manufacture of soft drinks -- second regulated-
beverage-manufacturing reference), the `kotoba-lang/industry` registry's
`"1103"` catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-C1103` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1103"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1103` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests). Both the placeholder org/name and the real `cloud-itonami` org
target name were independently confirmed 404 via `gh api` before any
scaffolding began. This is part of an ongoing careful, smaller-batch
rollout (one ISIC class per agent, a capable model, mandatory
verification) after a prior 18-agent haiku batch produced a 61% defect
rate (empty implementations, missing modules, false "all green" reports)
on other ISIC classes; 66+ consecutive agents on this stricter protocol
have all succeeded since. This ADR covers ISIC 1103 only, built and
verified from scratch -- no prior `cloud-itonami-isic-1103` repo existed
under the `cloud-itonami` org. The registry entry's `:name` ("Manufacture
of malt liquors and malt") was independently verified against a fresh
clone before any work began, per this fleet's ID/name-mismatch caution,
and confirmed to match the assigned scope (beer brewing + malting).

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1103` as a brewery/malthouse
PLANT-OPERATIONS COORDINATION actor (not direct brewing-line control
authority, not an excise-tax authority), mirroring
`cloud-itonami-isic-1102`'s verified module shape (`facts`/`registry`/
`store`/`governor`/`operation`/`phase`/`advisor`/`sim`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY) module-for-module with fresh, malt-liquor(beer)/
malt-specific domain logic under the `maltops` namespace:

1. **`maltops.facts`** -- product-type production windows (ABV/
   bitterness-IBU/diacetyl-off-flavor/microbial-load/fill-quantity/
   extract-yield, by product id: lager, ale, stout, and unfermented base
   malt -- base malt carries `abv-target-percent`/`ibu-min/max`/
   `diacetyl-max-ppb` all at `0.0`/`0`, since it is unfermented, plus a
   much higher microbial-load ceiling than packaged beer, 1000 CFU/mL vs
   50 CFU/mL, reflecting that dry grain has no alcohol/hop/pasteurization
   barrier but is also not itself a microbiologically-unstable finished
   beverage; conversely, base malt is the only product type carrying a
   nonzero `extract-yield-min-percent`, 80.0%, a genuine ASBC/EBC malt-
   analysis "brewing-grade malt" label-claim threshold that has no
   analog in finished beer), and jurisdiction ABV-label-accuracy and
   evidence-checklist requirements (JP 酒税法/国税庁, US TTB 27 CFR Part 25 /
   27 CFR 7.71, EU Council Directive 92/83/EEC -- the same excise-tax
   authorities, not general food-labeling authorities, that also govern
   wine, since beer is excise-taxed by ABV band in all three
   jurisdictions just as wine is).
2. **`maltops.registry`** -- pure, host-clock-free validation predicates
   the Governor uses to independently verify physical/operational
   constraints: ABV tolerance, IBU range, diacetyl ceiling, microbial-
   load ceiling, extract-yield floor, packaging-line calibration age
   (90-day limit, mirroring wine's bottling-line fill-volume-meter
   interval), fill-quantity variance, ABV-label mismatch, and
   contamination detection (a dedicated boolean predicate so the
   Governor's check-function shapes stay uniform).
3. **`maltops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`maltops.governor`** -- 18 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design brief: the advisor may only ever propose
   `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct
   mashing/fermentation/packaging-line control or excise/tax-
   classification-authority decisions -- is refused unconditionally
   (`:op-not-allowed`), never a soft escalation. `:flag-food-safety-
   concern` always escalates to a human regardless of confidence, as do
   the two real actuation events (`:log-production-batch`/
   `:coordinate-shipment`).
5. **`maltops.phase`** -- `:intake -> :malting -> :mashing ->
   :fermentation -> :packaging -> :inspection -> :audit -> :archived`, a
   malt-liquor/malt-specific phase sequence (`:malting` is only
   meaningful for malt-product batches -- a brewery buying already-
   malted grain can transition `:intake -> :mashing` directly, skipping
   it, since phase transitions are forward-only but not required to hit
   every intermediate phase).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1102`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).

### A deliberate departure from the wine/soft-drink fill-quantity check

Both `wineops` and `softdrinkops` gate fill-volume variance against a
single hardcoded threshold (15) shared across all product types, because
every product type in each of those catalogs is packaged on the same
liquid-volume scale (350mL-750mL). `maltops`'s catalog spans two very
different packaging-quantity scales (bottled/kegged/canned beer in mL,
target ~330-500; bulk-bagged base malt in gram-equivalent units, target
25000/tolerance 250, reusing the same `:fill-volume-target/tolerance-ml`
field name for implementation uniformity per the field's docstring). A
single hardcoded threshold across both scales is not meaningful --
initial implementation copied the wine/soft-drink hardcoded-15 pattern
verbatim and a real test failure caught it immediately (a malt batch
with a 50-unit variance, well within its own 250-unit tolerance, was
wrongly flagged against the hardcoded 15). Fixed by having
`fill-volume-variance-excessive-violations` look up each batch's own
product-type `:fill-volume-tolerance-ml` instead of a shared constant --
arguably a more correct design than the two references it mirrors, and
kept as an intentional, documented departure rather than force-fitting
the copied pattern.

### What this actor does NOT do

Mash-tun/lauter-tun/fermentation-tank/packaging-line equipment operation
and excise/tax-classification authority (reclassifying a batch's
national/federal excise-tax category) remain exclusive to licensed
brewery/malthouse staff and human tax authorities, permanently, with no
actor or human-approval override path -- enforced structurally by the
closed operation allowlist, not just documented.

## Verification

- `cloud-itonami-isic-1103`: `clojure -M:test` -- raw final line: `Ran
  57 tests containing 190 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Independently re-verified: fresh `git clone --depth 1` into a new
  scratch directory after push, re-ran `clojure -M:test` against the
  clean clone -- same green result (`Ran 57 tests containing 190
  assertions.` / `0 failures, 0 errors.`).
- Repo created fresh (`gh repo create cloud-itonami/cloud-itonami-isic-1103
  --public`) + push, commit `324157fc6514587066dca3e445a5986786b72760` on
  `cloud-itonami-isic-1103`'s `main` (initial commit, no prior history);
  confirmed landed on `origin/main` via `gh api
  repos/cloud-itonami/cloud-itonami-isic-1103/commits/main` matching the
  local push SHA exactly.
- `kotoba-lang/industry` registry `"1103"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1103` placeholder to
  `cloud-itonami/cloud-itonami-isic-1103`, `:maturity` `:spec` ->
  `:implemented`); `test/kotoba/industry_test.clj`'s `maturity-summary`
  assertion bumped to the live-recomputed count (recomputed via
  `(kotoba.industry/maturity-summary)` on a freshly re-fetched
  `origin/main` both immediately before the edit and again immediately
  after, not assumed); full `kotoba-lang/industry` suite re-run green
  post-edit and again post-merge against a brand-new fresh clone.
  `grep -c "â" resources/kotoba/industry/registry.edn` confirmed 0 (no
  mojibake) both pre- and post-merge. Exact counts and the merge commit
  SHA are recorded in the accompanying session report (this ADR's
  authoring turn), since the registry-side merge landed after this
  file's initial draft under GitHub-API 409-retry load from the
  concurrently-running ISIC-actor fleet.

## Consequences

(+) `cloud-itonami-isic-1103` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"1103"` entry promoted to
`:maturity :implemented`.
(+) Identified and fixed a latent design flaw in the wine/soft-drink
fill-volume-variance pattern (hardcoded single threshold) that only
surfaces when a catalog spans multiple packaging-quantity scales;
documented above so future ISIC actors with multi-scale catalogs (e.g.
any class mixing bulk/bagged and bottled/canned products) copy the
per-product-type-tolerance form instead of the hardcoded-constant form.
(-) `maltops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `maltops.operation/run-operation` takes
an already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-1102`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.
