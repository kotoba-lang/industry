# ADR-2607152500: cloud-itonami-isic-1062 (Manufacture of starches and starch products) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1061 (Grain mill products -- the reference
module shape this actor mirrors, independently re-verified before use),
the `kotoba-lang/industry` registry's `"1062"` catalog entry (previously
`:spec` with a placeholder `gftdcojp/cloud-itonami-C1062` repo link that
was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1062"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1062` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests). This is part of an ongoing, careful smaller-batch rollout
(one ISIC class per agent, a capable model, mandatory verification) after
a prior 18-agent batch on a weaker model produced a 61% defect rate on
other ISIC classes; 40+ consecutive agents on this stricter protocol had
succeeded before this one. This ADR covers ISIC 1062 only, built and
verified from scratch -- no prior `cloud-itonami-isic-1062` repo existed
under the `cloud-itonami` org (confirmed via `gh api` 404 before
scaffolding, both for the target repo name and the never-populated
`gftdcojp/cloud-itonami-C1062` placeholder).

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1062` as a
starches-and-starch-products manufacturing PLANT-OPERATIONS COORDINATION
actor (not extraction/refining-line control authority), mirroring
`cloud-itonami-isic-1061`'s verified module shape (`facts`/`registry`/
`store`/`governor`/`operation`/`phase`/`advisor`/`sim`, `deps.edn`/
`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY)
with fresh, starch-extraction-specific domain logic under the
`starchops` namespace:

1. **`starchops.facts`** -- product-type extraction/refining windows
   (moisture/purity/granulation/sulfite-residue-max-ppm/
   microbial-load-max-cfu, by product id: native corn starch, native
   potato starch, native cassava/tapioca starch, native wheat starch --
   corn and wheat starch deliberately carry a much higher sulfite-residue
   ceiling (50 ppm) than potato/cassava (10 ppm), reflecting that corn
   wet-milling (and wheat starch's gluten-separation process) routinely
   steeps in a dilute sulfurous-acid solution while potato/cassava
   extraction is a purely mechanical/aqueous process; cassava starch
   carries the strictest microbial-load ceiling (500 CFU/g vs. 1000 for
   the others), reflecting the higher spoilage risk of tropical wet
   processing), jurisdiction food-safety-declaration and
   evidence-checklist requirements (JP/US/EU), and a per-raw-material
   allergen table (`:wheat/starch-grade` is the one common feedstock in
   this ISIC class that retains a genuine `:wheat` (gluten) allergen;
   `:corn/waxy-hybrid` carries no primary allergen of its own but a real
   `:wheat` cross-contact risk on equipment shared with wheat-starch
   extraction, mirroring 1061's oat/wheat shared-milling-line hazard).
2. **`starchops.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify
   physical/operational constraints: moisture tolerance, sulfite-residue
   ceiling, microbial-load ceiling, purity bounds, granulation bounds,
   detection-equipment (magnet/metal-detector) calibration age (60-day
   limit -- shorter than 1061's 90-day interval, reflecting the higher
   fouling/drift rate of detection equipment running continuously on a
   high-moisture wet-processing line rather than a dry milling line),
   weight variance, allergen-label risk, and foreign-material detection.
3. **`starchops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`starchops.governor`** -- 18 independently-verified hard-violation
   checks (one more than 1061's 17, because the single mycotoxin check in
   1061 splits into two independent food-safety checks here: sulfite
   residue and microbial load, which are genuinely distinct hazards in
   starch extraction) plus a closed operation allowlist as a hard,
   permanent block per the domain design: the advisor may only ever
   propose `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct
   extraction/refining-line (steeping-tank/centrifuge/hydrocyclone/dryer)
   control or food-safety certification authority -- is refused
   unconditionally (`:op-not-allowed`), never a soft escalation.
   `:flag-food-safety-concern` always escalates to a human regardless of
   confidence, as do the two real actuation events
   (`:log-production-batch`/`:coordinate-shipment`).
5. **`starchops.phase`** -- `:intake -> :steep -> :extract -> :refine ->
   :dry -> :inspect -> :package -> :audit -> :archived`, a
   starch-extraction-specific phase sequence (`:steep` = the
   sulfurous-acid steeping step that is this domain's sulfite-residue
   source; `:extract`/`:refine`/`:dry` split out the separation/
   purification/drying steps that 1061's single `:mill` phase collapses,
   reflecting that starch extraction genuinely has more distinct physical
   unit operations than dry milling).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1061`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).

### What this actor does NOT do

Steeping-tank/centrifuge/hydrocyclone/dryer equipment operation and
food-safety certification authority remain exclusive to licensed
starch-plant staff and regulators, permanently, with no actor or
human-approval override path -- enforced structurally by the closed
operation allowlist, not just documented.

### Portability

`starchops.phase`'s `index-of` helper uses `keep-indexed` + `first`
rather than `.indexOf` (JVM-only `java.util.List` interop that would fail
to compile under ClojureScript), matching the portable fix already
established in `millops.phase` (ADR-2607152400) rather than
reintroducing the JVM-only pattern. `starchops.governor`'s
`now-epoch-ms` isolates the one host-clock call behind a `:clj`/`:cljs`
reader-conditional, keeping `starchops.registry` and every other
namespace free of host-clock/JVM-only calls. All source is `.cljc`.

## Verification

- `cloud-itonami-isic-1062`: `clojure -M:test` -- raw final line: `Ran
  52 tests containing 175 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Repo created fresh (`gh repo create --source=. `, explicit `git push -u
  origin main`), commit `c2f1e48c7c3cef70dc4f5d97d7e4ab97023a0d57` on
  `cloud-itonami-isic-1062`'s `main` (initial commit, no prior history);
  confirmed landed via `gh api repos/cloud-itonami/cloud-itonami-isic-1062/commits/main`
  matching the local push SHA exactly.
- `kotoba-lang/industry` registry `"1062"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1062` placeholder to
  `cloud-itonami/cloud-itonami-isic-1062`, `:maturity` `:spec` ->
  `:implemented`), `test/kotoba/industry_test.clj`'s `maturity-summary`
  assertion bumped to match the live-recomputed implemented count; full
  `kotoba-lang/industry` suite re-run green post-edit and again
  post-merge against a fresh clone (see registry merge commit for raw
  output).
- This superproject ADR pair was authored and committed from a sibling
  `git worktree` outside the superproject checkout
  (`/tmp/root-adr-1062`, branch `adr-1062-starches-1062`) and landed via
  a server-side merge, per this workspace's mandatory worktree-isolation
  and no-rebase/no-force-push discipline.

## Consequences

(+) `cloud-itonami-isic-1062` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"1062"` entry promoted to
`:maturity :implemented`.
(+) The sulfite-residue / microbial-load split (vs. 1061's single
mycotoxin check) is a reusable pattern for other wet-processing food
actors where steeping/soaking residue and extended-slurry-dwell microbial
risk are genuinely distinct hazards.
(-) `starchops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `starchops.operation/run-operation` takes
an already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-1061`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.
