# ADR-2607151900: cloud-itonami-isic-1075 (Manufacture of prepared meals and dishes) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1073 (Cocoa, chocolate and sugar
confectionery -- the reference module shape this actor mirrors,
independently re-verified before use), cloud-itonami-isic-1071 (Bakery
products -- second regulated food-manufacturing reference), the
`kotoba-lang/industry` registry's `"1075"` catalog entry (previously
`:spec` with a placeholder `gftdcojp/cloud-itonami-C1075` repo link that
was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1075"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1075` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests). This is part of an ongoing careful, smaller-batch rollout
(one ISIC class per agent, a capable model, mandatory verification) after
a prior 18-agent haiku batch produced a 61% defect rate (empty
implementations, missing modules, false "all green" reports) on other
ISIC classes; 54+ consecutive agents on this stricter protocol have all
succeeded since. This ADR covers ISIC 1075 only, built and verified from
scratch -- no prior `cloud-itonami-isic-1075` repo existed under the
`cloud-itonami` org (confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-1075` before starting).

Before any implementation work, the registry entry was independently
re-verified fresh (cloned `kotoba-lang/industry` to a uniquely-named
scratch dir, not the shared checkout) to confirm `{:id "1075" :name
"Manufacture of prepared meals and dishes" ...}` is genuinely what is
registered -- this fleet has previously seen agents mislabel their
assigned ISIC class (0892 assumed=salt, actually peat; 0144 assumed=swine,
actually sheep-goats), so this check is mandatory, not optional.
Confirmed correct.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1075` as a prepared-meals/
ready-dish-manufacturing PLANT-OPERATIONS COORDINATION actor (not
cook-line/chill-freeze-line/packaging-line control authority), mirroring
`cloud-itonami-isic-1073`'s verified module shape (`facts`/`registry`/
`store`/`governor`/`operation`/`phase`/`advisor`/`sim`, `deps.edn`/
`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY)
module-for-module, with fresh, cook-chill/cook-freeze-specific domain
logic under the `mealops` namespace:

1. **`mealops.facts`** -- product-type processing windows (core-cook-
   temp-min-c / chill-time-max-minutes / cold-storage-temp window /
   max-shelf-life-hours / water-activity-max / ph-max, by product id:
   cook-chill poultry, cook-chill beef, cook-freeze fish, cook-chill
   vegetarian), jurisdiction evidence-checklist requirements (JP/US/EU).
   `core-cook-temp-min-c` (70C) follows UK FSA cook-chill/cook-freeze
   guidance and equivalent US FDA Food Code / Codex CAC/RCP 39 cook-
   lethality reference temperatures; `chill-time-max-minutes` follows
   the classic cook-chill 90-minute (70C->3C) rule, with cook-freeze
   lines given a longer 240-minute window to reach a deep-frozen
   target; `ph-max` (5.0 for the two cook-chill meat product types)
   encodes the UK FSA / Health Canada REPFED secondary-control pH
   ceiling for reduced-oxygen-packaged (vacuum/MAP) product against
   non-proteolytic Clostridium botulinum growth.
2. **`mealops.registry`** -- 12 pure, host-clock-free validation
   predicates the Governor uses to independently verify
   physical/operational HACCP critical-control-point constraints: core-
   cook-temperature floor (CCP1 lethality), chill/freeze-down-time
   ceiling (CCP2), cold-storage-temperature window (cold-chain break),
   shelf-life ceiling (use-by-date), water-activity ceiling, pH
   ceiling, metal-detector/X-ray calibration age (24-hour shift-based
   interval -- shorter than confectionery's 90-day interval, reflecting
   prepared-meal lines' higher-frequency recalibration practice), weight
   variance, allergen cross-contact mismatch (set-difference predicate,
   mirrored from `chocops.registry/allergen-label-mismatch?`), foreign-
   material detection, sanitation/cross-contamination-control score, and
   a new domain-specific predicate not present in the 1073 reference --
   packaging-seal (vacuum/MAP) integrity -- since cold-chain-break and
   seal-integrity are both explicitly named food-safety concerns in this
   actor's domain-design brief and cook-chill/cook-freeze product safety
   depends on an intact reduced-oxygen package.
3. **`mealops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`mealops.governor`** -- 20 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct cook-line/
   chill-freeze-line/packaging-line control or food-safety certification
   authority -- is refused unconditionally (`:op-not-allowed`), never a
   soft escalation. `:flag-food-safety-concern` always escalates to a
   human regardless of confidence (e.g. HACCP critical-limit deviation,
   allergen cross-contact, cold-chain break), as do the two real
   actuation events (`:log-production-batch`/`:coordinate-shipment`).
5. **`mealops.phase`** -- `:intake -> :prep -> :cook -> :chill-freeze ->
   :package -> :inspect -> :audit -> :archived`, a cook-chill/cook-
   freeze-specific phase sequence (`:cook`, `:chill-freeze`, and
   `:package` are never directly controlled by this actor -- cook-line/
   chill-freeze-line/packaging-line operation remain exclusive to plant
   staff; `:chill-freeze` is where CCP2, the rapid chill/freeze-down
   through the microbial "danger zone," happens).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1073`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README
   sections).

### Batch-registration invariant applies to every action, not only shipment

Per this actor's domain design brief, the HARD invariant "plant/batch
record must be independently verified/registered before any action" is
NOT scoped to shipment coordination alone. `mealops.governor` implements
this as `batch-not-registered-violations`, applied unconditionally to
every op in the closed allowlist (`:log-production-batch`,
`:schedule-maintenance`, `:flag-food-safety-concern`,
`:coordinate-shipment`), each covered by its own test
(`batch-not-registered-violation-test`), mirroring the same invariant
`cloud-itonami-isic-1073`'s `chocops.governor` already implements
relative to its own reference chain.

### What this actor does NOT do

Cook-line, chill/blast-freeze-line, and packaging-line equipment
operation and food-safety certification authority remain exclusive to
licensed prepared-meal-plant staff and regulators, permanently, with no
actor or human-approval override path -- enforced structurally by the
closed operation allowlist, not just documented.

## Consequences

(+) `cloud-itonami-isic-1075` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.

(+) `kotoba-lang/industry` registry `"1075"` entry promoted to
`:maturity :implemented` (`:repo`/`:business-id` corrected from the
never-populated `gftdcojp/cloud-itonami-C1075` placeholder to
`cloud-itonami/cloud-itonami-isic-1075`; `:required-technologies` /
`:optional-technologies` normalized from the generic `:spec`-stub values
to the `[:identity :forms :audit-ledger :cae]` / `[:telemetry]` values
already used by the neighboring implemented food-manufacturing entries
1071/1073/1074) -- see the registry commit/merge SHA recorded alongside
this ADR's landing, and the post-merge re-verification re-run of
`clojure -M:test` from an independent fresh clone.

(+) `mealops.registry/packaging-seal-compromised?` is a new predicate
shape (vs. the 1073 reference's 11 predicates) worth reusing for other
reduced-oxygen-packaged/vacuum-sealed food-manufacturing actors where
seal integrity is itself a distinct food-safety hazard axis, not merely
a subset of an existing check.

(-) `mealops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `mealops.operation/run-operation` takes
an already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-1073`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems -- scope is
deliberately bounded to back-office coordination.

## Verification

- `cloud-itonami-isic-1075`: `clojure -M:test` -- raw final line: `Ran
  56 tests containing 179 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Scaffolded and tested from a uniquely-named scratch dir
  (`/private/tmp/.../scratchpad/1075-work/cloud-itonami-isic-1075`),
  never the shared superproject checkout.
- Repo created fresh (`gh repo create` + push), initial commit
  `5c77c39f05fd5724400bd021711ba0e1eb34e508` on
  `cloud-itonami-isic-1075`'s `main` (confirmed as the tip of
  `origin/main` via `gh api
  repos/cloud-itonami/cloud-itonami-isic-1075/commits/main`).
- All source is `.cljc` (portable, grepped clean of JVM-only interop --
  only host-clock access, `System/currentTimeMillis` /
  `js/Date.now`, is reader-conditional guarded).
- `kotoba-lang/industry` registry `"1075"` entry updated in place
  (`:repo`/`:business-id` corrected, `:maturity` `:spec` ->
  `:implemented`) via an exact-text in-place edit of the single
  `{:id "1075" ...}` block (no wholesale regeneration);
  `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  to match the live-recomputed implemented-entry count (recomputed via
  a fresh `clojure -M:test` run against a freshly re-fetched
  `origin/main`, not assumed); full `kotoba-lang/industry` suite
  re-run green post-edit and again post-merge against a fresh clone.
