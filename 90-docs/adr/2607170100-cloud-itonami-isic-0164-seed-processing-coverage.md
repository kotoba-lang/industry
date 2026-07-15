# ADR-2607170100: cloud-itonami-isic-0164 (Seed processing for propagation) facility-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1061 (Manufacture of grain mill products --
the reference module shape this actor mirrors, independently re-read in
full before use), the `kotoba-lang/industry` registry's `"0164"` catalog
entry (previously `:spec` with a placeholder `gftdcojp/cloud-itonami-A0164`
repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"0164"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-A0164` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests; confirmed 404 via `gh api` before any work began). This is part
of an ongoing careful, smaller-batch rollout (one ISIC class per agent, a
capable model, mandatory verification) after a prior 18-agent haiku batch
produced a 61% defect rate on other ISIC classes; 30+ consecutive agents
on this stricter protocol have all succeeded since. This ADR covers ISIC
0164 only. Before any work began, the registry entry's identity
(`{:id "0164" :name "Seed processing for propagation"}`) was independently
verified against a fresh clone, per this fleet's caution against ID/name
mismatches (prior agents in this fleet had mislabeled 0892 as salt when it
was actually peat, and 0144 as swine when it was actually sheep/goats) --
confirmed correct, no mismatch. No prior `cloud-itonami-isic-0164` repo
existed under the `cloud-itonami` org.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-0164` as a
seed-processing-for-propagation FACILITY-OPERATIONS COORDINATION actor
(not cleaning/grading-equipment control authority, not seed-certification
authority), mirroring `cloud-itonami-isic-1061`'s verified module shape
(`facts`/`registry`/`store`/`governor`/`operation`/`phase`/`advisor`/
`sim`, `deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY) with fresh, seed-processing-specific domain logic
under the `seedops` namespace. ISIC 0164's finished product is seed
destined to be PLANTED (grown into a future crop), not consumed or milled
for food -- this is the key domain distinction from 1061's grain-mill
post-harvest processing, and it drives every domain-specific check below:

1. **`seedops.facts`** -- seed-lot-type processing/storage windows
   (moisture/germination-min-percent/purity-min-percent/other-crop-seed-
   max-percent, by seed-lot id: hybrid maize, certified wheat, certified
   soybean, tomato -- tomato deliberately carries a lower germination
   floor, 75% vs. 90% for hybrid maize, reflecting the real regulatory
   asymmetry between field-crop and vegetable-seed certification
   standards), jurisdiction trait-declaration and evidence-checklist
   requirements (JP/US/EU), and a per-seed-source (variety/cultivar)
   regulated-trait table (Bt maize and Roundup Ready soybean map to
   `:gm-bt-trait`/`:gm-ht-trait`; conventional maize/soybean carry no
   primary trait of their own but a real GM-trait cross-contact
   (adventitious-presence) risk, reflecting the actual
   conventional/non-GM-labeled-seed-grown-near-a-GM-field hazard --
   structurally the same shape as 1061's oat/wheat gluten cross-contact
   table, applied to a genuinely different regulatory concern).
2. **`seedops.registry`** -- pure, host-clock-free validation predicates
   the Governor uses to independently verify physical/operational
   constraints: moisture tolerance, germination-rate floor,
   purity floor, other-crop-seed-contamination ceiling,
   germinator/incubator calibration age (60-day limit -- shorter than
   1061's 90-day magnet-calibration interval, reflecting the sensitivity
   of biological germination testing to environmental drift), weight
   variance, trait-label risk, and seed-borne-disease detection (a
   dedicated boolean predicate so the Governor's check-function shapes
   stay uniform with 1061's foreign-material-detected predicate).
3. **`seedops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`seedops.governor`** -- 17 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-processing-batch`, `:schedule-maintenance`,
   `:flag-quality-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct
   cleaning/grading-equipment (scalper/gravity-table/screen/germinator)
   control or seed-certification-authority decisions -- is refused
   unconditionally (`:op-not-allowed`), never a soft escalation.
   `:flag-quality-concern` always escalates to a human regardless of
   confidence, as do the two real actuation events
   (`:log-processing-batch`/`:coordinate-shipment`).
5. **`seedops.phase`** -- `:intake -> :clean -> :grade -> :test ->
   :package -> :audit -> :archived`, a seed-processing-specific phase
   sequence (`:grade` = size/density grading via screens and gravity
   tables, `:test` = germination/purity/moisture/other-crop-seed/
   seed-borne-disease laboratory testing, distinct from 1061's `:mill`/
   `:inspect` split). Uses the same portable `keep-indexed`-based
   `index-of` helper 1061 introduced (not the JVM-only `.indexOf`), so
   this actor ships cljs-portable from day one rather than needing a
   follow-up fix.
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1061`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).

### What this actor does NOT do

Scalper/gravity-table/screen/germinator equipment operation and
seed-certification authority (the legal act of certifying a seed lot fit
for propagation) remain exclusive to licensed seed-processing facility
staff and regulators, permanently, with no actor or human-approval
override path -- enforced structurally by the closed operation allowlist,
not just documented.

## Verification

- `cloud-itonami-isic-0164`: `clojure -M:test` -- raw final line: `Ran
  49 tests containing 156 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Grepped for stray JVM-only interop (`.indexOf`, `java.`, unguarded
  `System/`) outside `#?(:clj ...)` reader conditionals -- none found;
  every host-clock call is behind a `:clj`/`:cljs` reader conditional.
- Repo created fresh (`gh repo create` + push), commit `f9e92c5` on
  `cloud-itonami-isic-0164`'s `main` (initial commit, no prior history),
  confirmed via `git rev-parse HEAD` == `git rev-parse origin/main`.
- `kotoba-lang/industry` registry `"0164"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-A0164` placeholder to
  `cloud-itonami/cloud-itonami-isic-0164`, `:required-technologies`/
  `:optional-technologies`/`:operating-states` corrected to match
  1061's own implemented-tier shape, `:maturity` `:spec` ->
  `:implemented`); `test/kotoba/industry_test.clj`'s `maturity-summary`
  assertion bumped from a live-recomputed 220 to 221 implemented entries
  (recomputed via `(industry/maturity-summary)` against a freshly
  re-fetched origin/main immediately before the edit -- not assumed).
  Full `kotoba-lang/industry` suite re-run green post-edit: `Ran 15
  tests containing 946 assertions.` / `0 failures, 0 errors.` Landed via
  GitHub API server-side merge (`gh api repos/kotoba-lang/industry/merges`),
  merge commit `ea4e498`, succeeded on the first attempt (no 409s
  encountered this time, despite the concurrent-load caution -- a
  second, unrelated concurrent promotion to `:implemented` was observed
  between the pre-edit and post-edit `maturity-summary` reads, 219 -> 220
  before this ADR's own edit landed the 220 -> 221 step, confirming
  concurrent fleet activity was genuinely in flight).
- Post-merge re-verification: freshly re-fetched `origin/main`,
  re-cloned into a brand-new scratch directory (plus a fresh
  `kotoba-lang/technology` sibling clone for `deps.edn` resolution),
  re-ran `clojure -M:test` against the clean post-merge clone -- see
  this ADR's own commit history / task report for the raw re-verification
  output. `grep -c "â" resources/kotoba/industry/registry.edn` returned
  `0` (no file-wide UTF-8 mojibake).

## Consequences

(+) `cloud-itonami-isic-0164` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"0164"` entry promoted to
`:maturity :implemented`, count 220 -> 221.
(+) `seedops.governor`'s germination-rate-below-minimum and
purity-below-minimum checks are a reusable pattern for any future
propagation/viability-graded ISIC class in this fleet (as distinct from
1061's food-safety-contamination-ceiling pattern).
(-) `seedops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `seedops.operation/run-operation` takes an
already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-1061`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.
