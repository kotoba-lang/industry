# ADR-2608100000: cloud-itonami-isic-1079 (Manufacture of other food products n.e.c.) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1075 (Prepared meals and dishes -- the
reference module shape this actor mirrors, independently re-read before
use), cloud-itonami-isic-1073 (Cocoa, chocolate and sugar confectionery
-- second regulated food-manufacturing reference), the
`kotoba-lang/industry` registry's `"1079"` catalog entry (previously
`:spec` with a placeholder `gftdcojp/cloud-itonami-C1079` repo link that
did not follow this fleet's naming convention and did not exist on
GitHub)

## Context

`kotoba-lang/industry`'s registry carried a `"1079"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1079` --
a placeholder repo link with no actual implementation behind it, and one
that also did not follow this fleet's established
`cloud-itonami/cloud-itonami-isic-<id>` naming convention. This is part
of an ongoing careful, smaller-batch rollout (one ISIC class per agent,
a capable model, mandatory synchronous verification) after a prior
18-agent haiku batch produced a 61% defect rate (empty implementations,
missing modules, false "all green" reports) on other ISIC classes;
114+ consecutive agents on this stricter protocol have all succeeded
since. This ADR covers ISIC 1079 only, built and verified from scratch
-- no prior repo existed under either the placeholder's org/name
(`gftdcojp/cloud-itonami-C1079`) or the canonical
`cloud-itonami/cloud-itonami-isic-1079` name (both confirmed 404 via
`gh api` before starting).

Before any implementation work, the registry entry was independently
re-verified fresh (cloned `kotoba-lang/industry` to a uniquely-named
scratch dir, not the shared checkout) to confirm `{:id "1079" :name
"Manufacture of other food products n.e.c." ...}` is genuinely what is
registered -- this fleet has previously seen agents mislabel their
assigned ISIC class, so this check is mandatory, not optional.
Confirmed correct. ISIC 1079 is a residual ("not elsewhere classified")
category with no single canonical product (covering lines such as
instant/prepared seasonings, soup mixes, yeast, egg products, honey
processing), so this actor -- per the task's domain-design brief --
picks one concrete illustrative product line, documented plainly in the
README: **instant-seasoning/soup-mix manufacturing** (instant
dashi/soup-stock powder, cream-based soup mix, dry spice blends,
bouillon/consommé granules).

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1079` as an instant-
seasoning/soup-mix-manufacturing PLANT-OPERATIONS COORDINATION actor
(not mixing-line/packaging-line control authority), mirroring
`cloud-itonami-isic-1075`'s verified module shape (`facts`/`registry`/
`store`/`governor`/`operation`/`phase`/`advisor`/`sim`, `deps.edn`/
`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY)
module-for-module, with fresh, dry-mix-blending-specific domain logic
under the `seasoningops` namespace:

1. **`seasoningops.facts`** -- product-type processing windows
   (moisture-content-max-percent / blend-homogeneity-min-percent /
   water-activity-max / microbial-load-max-cfu-per-g /
   max-shelf-life-hours, by product id: instant dashi powder, western
   cream soup mix, dry curry spice blend, bouillon granules),
   jurisdiction evidence-checklist requirements (JP/US/EU).
   `moisture-content-max-percent` and `water-activity-max` follow FDA
   Draft Guidance for Industry: Control of Salmonella in Low-Moisture
   Foods and Codex Alimentarius CXC 68-2013 (Code of Hygienic Practice
   for Low-Moisture Foods), both of which name moisture control as a
   primary control for this product category; `microbial-load-max-cfu-
   per-g` reflects that dried spices/seasonings are a recognised
   Salmonella contamination vector (multiple FDA/CDC multistate outbreak
   investigations trace back to spice-blend and bouillon-type products).
2. **`seasoningops.registry`** -- 11 pure, host-clock-free validation
   predicates the Governor uses to independently verify
   physical/operational critical-control-point constraints:
   moisture-content ceiling (this actor's CCP1 analogue),
   blend-homogeneity floor (this actor's CCP2 analogue), water-activity
   ceiling, microbial-load ceiling, shelf-life ceiling (use-by-date),
   metal-detector calibration age (48-hour interval -- longer than
   ISIC 1075's 24-hour shift-based interval, reflecting dry-mix
   blending/packaging lines' lower-throughput, batch-based production
   vs. a continuous prepared-meal cook line), weight variance, allergen
   cross-contact mismatch (set-difference predicate, mirrored from
   `mealops.registry/allergen-label-mismatch?`), foreign-material
   detection, sanitation/cross-contamination-control score, and
   packaging-seal (moisture-barrier) integrity.
3. **`seasoningops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`seasoningops.governor`** -- 19 independently-verified
   hard-violation checks plus a closed operation allowlist as a hard,
   permanent block per the domain design: the advisor may only ever
   propose `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct mixing-line/
   packaging-line-equipment control or food-safety certification
   authority -- is refused unconditionally (`:op-not-allowed`), never a
   soft escalation. `:flag-food-safety-concern` always escalates to a
   human regardless of confidence (e.g. allergen cross-contact,
   microbial contamination), as do the two real actuation events
   (`:log-production-batch`/`:coordinate-shipment`).
5. **`seasoningops.phase`** -- `:intake -> :weigh -> :mix -> :package ->
   :inspect -> :audit -> :archived`, a dry-mix-blending-specific phase
   sequence (`:mix` and `:package` are never directly controlled by
   this actor -- mixing-line/packaging-line operation remain exclusive
   to plant staff). Unlike ISIC 1075's refrigerated ready-meals, this
   product category is shelf-stable at ambient temperature, so there is
   deliberately no `:chill-freeze`-equivalent phase or cold-chain check
   in this actor -- moisture-barrier packaging-seal integrity is what
   protects the shelf-life claim instead.
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1075`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README
   sections).

### Batch-registration invariant applies to every action, not only shipment

Per this actor's domain design brief, the HARD invariant "plant/batch
record must be independently verified/registered before any action" is
NOT scoped to shipment coordination alone. `seasoningops.governor`
implements this as `batch-not-registered-violations`, applied
unconditionally to every op in the closed allowlist
(`:log-production-batch`, `:schedule-maintenance`,
`:flag-food-safety-concern`, `:coordinate-shipment`), each covered by
its own test (`batch-not-registered-violation-test`), mirroring the same
invariant `cloud-itonami-isic-1075`'s `mealops.governor` already
implements.

### What this actor does NOT do

Mixing-line and packaging-line equipment operation and food-safety
certification authority remain exclusive to licensed dry-mix-plant staff
and regulators, permanently, with no actor or human-approval override
path -- enforced structurally by the closed operation allowlist, not
just documented.

### Repo naming correction

The registry's pre-existing `:repo`/`:business-id` placeholder
(`gftdcojp/cloud-itonami-C1079` / `cloud-itonami-C1079`) is corrected to
this fleet's established convention:
`https://github.com/cloud-itonami/cloud-itonami-isic-1079` /
`cloud-itonami-isic-1079`.

### Timestamp note

This ADR's numeric prefix (`2608100000`) does not correspond to the
literal current date -- it was chosen, per this fleet's established
collision-avoidance convention (visible across many other ADRs in this
directory using similar out-of-calendar prefixes, e.g. `2608001100`,
`2607999900`), to sort clearly above the highest existing
`90-docs/adr/26XXXXXXXX-*` prefix found in the live tree at the time of
writing (`2608001100-cloud-itonami-isic-0161-...`), to avoid colliding
with the many other concurrent agents in this rollout.

## Consequences

(+) `cloud-itonami-isic-1079` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed
under a non-canonical name.

(+) `kotoba-lang/industry` registry `"1079"` entry promoted to
`:maturity :implemented` (`:repo`/`:business-id` corrected from the
never-populated `gftdcojp/cloud-itonami-C1079` placeholder to
`cloud-itonami/cloud-itonami-isic-1079`; `:required-technologies` /
`:optional-technologies` left exactly as they already were in the
registry -- `[:robotics :identity :forms :dmn :bpmn :audit-ledger :cae]`
/ `[]` -- since those fields were already correctly populated, not
generic `:spec`-stub placeholders) -- see the registry commit/merge SHA
recorded alongside this ADR's landing, and the post-merge
re-verification re-run of `clojure -M:test` from an independent fresh
clone.

(+) `seasoningops.registry/moisture-content-exceeds-max?` /
`blend-homogeneity-below-minimum?` are new predicate shapes (CCP1/CCP2
analogues for a dry-mix blending line rather than a cook/chill line)
worth reusing for other low-moisture/shelf-stable food-manufacturing
actors where mixing uniformity and moisture control -- not cold-chain --
are the defining food-safety hazard axes.

(-) `seasoningops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `seasoningops.operation/run-operation`
takes an already-formed proposal plus an injected `governor-fn` rather
than internally invoking an advisor. This matches
`cloud-itonami-isic-1075`'s own current shape and is a natural,
contained future extension, not required for this ADR's verification
bar.

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems -- scope is
deliberately bounded to back-office coordination.

## Verification

- `cloud-itonami-isic-1079`: `clojure -M:test` -- raw final line: `Ran
  53 tests containing 169 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Scaffolded and tested from a uniquely-named scratch dir
  (`/private/tmp/.../scratchpad/isic-1079/cloud-itonami-isic-1079`),
  never the shared superproject checkout.
- Repo created fresh (`gh repo create` + push), initial commit
  `bb40a440f7b8a0dcfa8f21724879663d8ecaeb47` on
  `cloud-itonami-isic-1079`'s `main` (confirmed as the tip of
  `origin/main` via `gh api
  repos/cloud-itonami/cloud-itonami-isic-1079/git/refs/heads/main`).
- All source is `.cljc` (portable, no JVM-only interop -- only
  host-clock access, `System/currentTimeMillis` / `js/Date.now`, is
  reader-conditional guarded).
- `kotoba-lang/industry` registry `"1079"` entry updated in place
  (`:repo`/`:business-id` corrected, `:maturity` `:spec` ->
  `:implemented`) via an exact-text in-place edit of the single
  `{:id "1079" ...}` block (no wholesale regeneration);
  `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  to match the live-recomputed implemented-entry count (recomputed via
  `kotoba.industry/maturity-summary`, not `grep -c`, against a freshly
  re-fetched `origin/main`); full `kotoba-lang/industry` suite re-run
  green post-edit and again post-merge against a fresh clone.
