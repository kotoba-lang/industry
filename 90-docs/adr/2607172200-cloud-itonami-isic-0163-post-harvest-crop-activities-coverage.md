# ADR-2607172200: cloud-itonami-isic-0163 (Post-harvest crop activities) facility-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-0164 (Seed processing for propagation --
the reference module shape this actor mirrors, independently re-read in
full before use), the `kotoba-lang/industry` registry's `"0163"` catalog
entry (previously `:spec` with a placeholder `gftdcojp/cloud-itonami-A0163`
repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"0163"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-A0163` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests; confirmed 404 via `gh api` before any work began). This is part
of an ongoing careful, smaller-batch rollout (one ISIC class per agent, a
capable model, mandatory verification) after a prior 18-agent haiku batch
produced a 61% defect rate on other ISIC classes; 42+ consecutive agents
on this stricter protocol have all succeeded since. This ADR covers ISIC
0163 only. Before any work began, the registry entry's identity
(`{:id "0163" :name "Post-harvest crop activities"}`) was independently
verified against a fresh clone, per this fleet's caution against ID/name
mismatches (prior agents in this fleet had mislabeled 0892 as salt when it
was actually peat, and 0144 as swine when it was actually sheep/goats) --
confirmed correct, no mismatch. No prior `cloud-itonami-isic-0163` repo
existed under the `cloud-itonami` org.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-0163` as a post-harvest
crop-processing FACILITY-OPERATIONS COORDINATION actor (not
drying/cleaning/grading/packing-equipment control authority), mirroring
`cloud-itonami-isic-0164`'s verified module shape (`facts`/`registry`/
`store`/`governor`/`operation`/`phase`/`advisor`/`sim`, `deps.edn`/
`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY)
with fresh, post-harvest-specific domain logic under the `postharvest`
namespace. ISIC 0163 prepares agricultural products for the PRIMARY
MARKET (cleaning, trimming, sorting, grading, drying, cold-storage
handling, packing) for crops NOT covered by a more specific ISIC class --
this is the key domain distinction from 0164's seed VIABILITY (for
planting) quality bar, and it drives every domain-specific check below:

1. **`postharvest.facts`** -- crop-lot-type processing/packing windows
   (moisture/defect-rate-max-percent/foreign-matter-max-percent, by
   crop-lot id: black tea, flue-cured tobacco, citrus, leafy greens) and
   jurisdiction evidence-checklist requirements (JP/US/EU). Two
   crop-lot types (black tea, flue-cured tobacco) have a drying step and
   carry a real moisture target/tolerance; the two fresh-produce types
   (citrus, leafy greens) have `nil` moisture fields (there is no drying
   step to have a target for) but instead carry a real cold-storage
   temperature target/tolerance -- and vice versa, the dried-goods types
   have `nil` cold-storage fields. The Governor's corresponding checks
   are written to skip cleanly on `nil`, never fabricating a spec that
   doesn't apply to a given crop-lot type (mirrors the honesty discipline
   `cloud-itonami-isic-3320`'s jurisdiction catalog established: no
   invented numeric floor where the underlying fact genuinely doesn't
   exist for that case).
2. **`postharvest.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify physical/
   operational constraints: moisture tolerance (when applicable),
   defect-rate ceiling, foreign-matter ceiling, cold-storage-temperature
   tolerance (when applicable), drying-equipment (moisture-meter/scale)
   calibration age (90-day limit), weight variance, pest-infestation
   detection, and pesticide-residue-exceedance detection (the latter two
   are dedicated boolean predicates so the Governor's check-function
   shapes stay uniform with 0164's seed-borne-disease-detected
   predicate, applied to the genuinely different for-market food-safety
   concern this ISIC class actually has).
3. **`postharvest.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`postharvest.governor`** -- 17 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-processing-batch`, `:schedule-maintenance`,
   `:flag-quality-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct
   drying/cleaning/grading/packing-line equipment control -- is refused
   unconditionally (`:op-not-allowed`), never a soft escalation.
   `:flag-quality-concern` always escalates to a human regardless of
   confidence, as do the two real actuation events
   (`:log-processing-batch`/`:coordinate-shipment`).
5. **`postharvest.phase`** -- `:intake -> :clean-trim -> :sort-grade ->
   :dry-or-store -> :pack -> :audit -> :archived`, a post-harvest-
   specific phase sequence (`:dry-or-store` deliberately folds drying
   and cold-storage handling into one phase slot, since a given batch
   only ever needs one or the other depending on its crop-lot type,
   distinct from 0164's dedicated `:test` phase for seed-viability lab
   work). Uses the same portable `keep-indexed`-based `index-of` helper
   0164 introduced (not the JVM-only `.indexOf`), so this actor ships
   cljs-portable from day one rather than needing a follow-up fix.
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-0164`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).
   `:itonami.blueprint/robotics` is honestly `false` (this actor holds no
   equipment-control authority), matching the more recent, more careful
   convention `cloud-itonami-isic-3320` established rather than 0164's
   own (inconsistent) `true`.

### What this actor does NOT do

Dryer, cleaner/scalper, grader, and packing-line equipment operation
remain exclusive to licensed post-harvest facility staff, permanently,
with no actor or human-approval override path -- enforced structurally by
the closed operation allowlist, not just documented. This actor also does
not perform grain-mill post-harvest processing that transforms the crop
into another product (ISIC 1061) or seed processing for propagation
(ISIC 0164) -- both are separate, already-implemented ISIC classes in
this fleet.

## Verification

- `cloud-itonami-isic-0163`: `clojure -M:test` -- raw final line: `Ran
  50 tests containing 158 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Grepped for stray JVM-only interop (`.indexOf`, `java.`, unguarded
  `System/`) outside `#?(:clj ...)` reader conditionals -- none found;
  every host-clock call is behind a `:clj`/`:cljs` reader conditional.
- Repo created fresh (`gh repo create` + push), commit `5a95014` on
  `cloud-itonami-isic-0163`'s `main` (initial commit, no prior history),
  confirmed via `git rev-parse HEAD` == `git rev-parse origin/main`
  (also cross-checked via the GitHub compare API: `ahead_by 0, behind_by
  0, status "identical"`). Independently re-verified against a brand-new
  fresh clone: `Ran 50 tests containing 158 assertions.` / `0 failures,
  0 errors.`
- `kotoba-lang/industry` registry `"0163"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-A0163` placeholder to
  `cloud-itonami/cloud-itonami-isic-0163`, `:required-technologies`/
  `:optional-technologies`/`:operating-states` corrected to match
  0164's own implemented-tier shape, `:maturity` `:spec` ->
  `:implemented`); `test/kotoba/industry_test.clj`'s `maturity-summary`
  assertion bumped (live-recomputed via `(industry/maturity-summary)`
  against a freshly re-fetched `origin/main` immediately before each
  edit -- not assumed). First attempt (231 -> 232) hit a genuine `409
  Merge conflict` against two other concurrent promotions
  (`cloud-itonami-isic-0119` and `cloud-itonami-isic-2431`) that landed
  on `main` in the interim -- rather than fight a textual 3-way merge on
  the shared registry/test files, the branch was discarded, `main` was
  re-fetched (231 -> 233 from the two concurrent promotions), the same
  targeted edit was redone against the new tip (233 -> 234), and pushed
  on a fresh branch. Landed via GitHub API server-side merge (`gh api
  repos/kotoba-lang/industry/merges`) on the second attempt, merge
  commit `0c8a3e9553ff97b6be1b1b8ddc2313cccbb41fba`. Full
  `kotoba-lang/industry` suite re-run green post-edit (pre-merge, on the
  branch): `Ran 15 tests containing 949 assertions.` / `0 failures, 0
  errors.`
- Post-merge re-verification: freshly re-fetched `origin/main`,
  re-cloned into a brand-new scratch directory at merge commit
  `0c8a3e9553ff97b6be1b1b8ddc2313cccbb41fba` (plus a fresh
  `kotoba-lang/technology` sibling clone for `deps.edn` resolution),
  re-ran `clojure -M:test` against the clean post-merge clone: `Ran 15
  tests containing 949 assertions.` / `0 failures, 0 errors.`
  `grep -c "â" resources/kotoba/industry/registry.edn` returned `0` (no
  file-wide UTF-8 mojibake).

## Consequences

(+) `cloud-itonami-isic-0163` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"0163"` entry promoted to
`:maturity :implemented`, count 233 -> 234.
(+) `postharvest.facts`/`postharvest.governor`'s `nil`-guarded
crop-lot-type-conditional checks (moisture only for drying-step types,
cold-storage temperature only for cold-chain types) are a reusable
pattern for any future ISIC class in this fleet whose spec catalog
genuinely varies which physical constraints apply per sub-type, as
distinct from 0164's every-type-has-every-field seed-lot catalog.
(+) The 409-conflict recovery in this ADR's own Verification section
(discard-and-redo-on-fresh-tip rather than textual 3-way merge) is a
concrete worked example of this fleet's "retry relentlessly on 409"
guidance for the shared `kotoba-lang/industry` registry/test files under
genuine concurrent-agent load.
(-) `postharvest.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `postharvest.operation/run-operation`
takes an already-formed proposal plus an injected `governor-fn` rather
than internally invoking an advisor. This matches
`cloud-itonami-isic-0164`'s own current shape and is a natural,
contained future extension, not required for this ADR's verification
bar.
