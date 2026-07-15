# ADR-2607181500: cloud-itonami-isic-1073 (Manufacture of cocoa, chocolate and sugar confectionery) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1072 (Manufacture of sugar -- the reference
module shape this actor mirrors, independently re-verified before use),
cloud-itonami-isic-1071 (Bakery products -- second regulated
food-manufacturing reference), the `kotoba-lang/industry` registry's
`"1073"` catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-C1073` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1073"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1073` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests). This is part of an ongoing careful, smaller-batch rollout
(one ISIC class per agent, a capable model, mandatory verification) after
a prior 18-agent haiku batch produced a 61% defect rate (empty
implementations, missing modules, false "all green" reports) on other
ISIC classes; 48+ consecutive agents on this stricter protocol have all
succeeded since. This ADR covers ISIC 1073 only, built and verified from
scratch -- no prior `cloud-itonami-isic-1073` repo existed under the
`cloud-itonami` org (confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-1073` before starting).

Before any implementation work, the registry entry was independently
re-verified fresh (cloned `kotoba-lang/industry` to a uniquely-named
scratch dir, not the shared checkout) to confirm `{:id "1073" :name
"Manufacture of cocoa, chocolate and sugar confectionery" ...}` is
genuinely what is registered -- this fleet has previously seen agents
mislabel their assigned ISIC class (0892 assumed=salt, actually peat;
0144 assumed=swine, actually sheep-goats), so this check is mandatory,
not optional. Confirmed correct.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1073` as a cocoa/chocolate/
sugar-confectionery-manufacturing PLANT-OPERATIONS COORDINATION actor
(not conching/tempering/molding-line control authority), mirroring
`cloud-itonami-isic-1072`'s verified module shape (`facts`/`registry`/
`store`/`governor`/`operation`/`phase`/`advisor`/`sim`, `deps.edn`/
`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY)
module-for-module, with fresh, cocoa/chocolate/confectionery-specific
domain logic under the `chocops` namespace:

1. **`chocops.facts`** -- product-type processing windows (moisture/
   cocoa-content-min-percent/particle-size-max-microns/process-temp
   window/cadmium-max-ppm/viscosity-max-pa-s, by product id: dark
   chocolate, milk chocolate, white chocolate, sugar confectionery
   (hard candy) -- `cocoa-content-min-percent` follows Codex STAN
   87-1981 minimum total-cocoa-solids thresholds (dark 35%, milk 25%)
   and, for white chocolate, represents the minimum cocoa-BUTTER
   percentage instead (white chocolate has zero non-fat cocoa solids by
   definition); for pure sugar confectionery the field is 0 and the
   check never fires), jurisdiction evidence-checklist requirements
   (JP/US/EU).
2. **`chocops.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify
   physical/operational constraints: moisture tolerance, cocoa-content
   floor, particle-size ceiling (refining fineness/grittiness),
   process-temperature (tempering/cook) window, cadmium-residue
   ceiling (a cocoa-specific heavy-metal hazard from soil
   bio-accumulation, EU Regulation 488/2014), viscosity ceiling
   (molding-line pourability), metal-detector calibration age (90-day
   limit, mirroring the sugar-refining magnet-calibration interval),
   weight variance, allergen cross-contact mismatch (a set-difference
   predicate between the batch's actual cross-contact-risk set and its
   declared-allergens set -- the single most common recall reason in
   this industry, distinct in shape from sugar's threshold-based
   sulfite-declaration check because major-allergen declaration is not
   a ppm-threshold matter), and foreign-material detection (a dedicated
   boolean predicate so the Governor's check-function shapes stay
   uniform).
3. **`chocops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`chocops.governor`** -- 19 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct
   conching/tempering/molding-line control or food-safety certification
   authority -- is refused unconditionally (`:op-not-allowed`), never a
   soft escalation. `:flag-food-safety-concern` always escalates to a
   human regardless of confidence (e.g. allergen cross-contact with
   milk/nuts, salmonella risk), as do the two real actuation events
   (`:log-production-batch`/`:coordinate-shipment`).
5. **`chocops.phase`** -- `:intake -> :roasting -> :conching ->
   :tempering -> :molding -> :package -> :audit -> :archived`, a
   cocoa/chocolate-processing-specific phase sequence (`:conching` and
   `:tempering` are never directly controlled by this actor --
   conche/tempering-machine/molding-line operation remain exclusive to
   plant staff; `:tempering` is where the polymorphic cocoa-butter
   crystal form -- Form V -- is set, preventing fat bloom).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1072`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README
   sections).

### Batch-registration invariant applies to every action, not only shipment

Per this actor's domain design brief, the HARD invariant "plant/batch
record must be independently verified/registered before any action" is
NOT scoped to shipment coordination alone. `chocops.governor` implements
this as `batch-not-registered-violations`, applied unconditionally to
every op in the closed allowlist (`:log-production-batch`,
`:schedule-maintenance`, `:flag-food-safety-concern`,
`:coordinate-shipment`), each covered by its own test
(`batch-not-registered-violation-test`), mirroring the same broadening
`cloud-itonami-isic-1072`'s `sugarops.governor` already made relative to
its own reference (`millops.governor`).

### What this actor does NOT do

Conche, tempering-machine, and molding-line equipment operation and
food-safety certification authority remain exclusive to licensed
confectionery-plant staff and regulators, permanently, with no actor or
human-approval override path -- enforced structurally by the closed
operation allowlist, not just documented.

## Consequences

(+) `cloud-itonami-isic-1073` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.

(+) `kotoba-lang/industry` registry `"1073"` entry promoted to
`:maturity :implemented` (`:repo`/`:business-id` corrected from the
never-populated `gftdcojp/cloud-itonami-C1073` placeholder to
`cloud-itonami/cloud-itonami-isic-1073`) -- see the registry
commit/merge SHA recorded alongside this ADR's landing, and the
post-merge re-verification re-run of `clojure -M:test` from an
independent fresh clone.

(+) `chocops.registry/allergen-label-mismatch?`'s set-difference shape
(vs. `sugarops`' threshold+declared-set shape for sulfite) is a
reusable pattern candidate for other food-manufacturing actors whose
food-safety-labeling hazard is a major-allergen cross-contact risk
rather than a residue-threshold declaration.

(-) `chocops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `chocops.operation/run-operation` takes
an already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-1072`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems -- scope is
deliberately bounded to back-office coordination.

## Verification

- `cloud-itonami-isic-1073`: `clojure -M:test` -- raw final line: `Ran
  53 tests containing 174 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Scaffolded and tested from a uniquely-named scratch dir
  (`/tmp/cloud-itonami-1073-work/cloud-itonami-isic-1073`), never the
  shared superproject checkout.
- Repo created fresh (`gh repo create` + push), initial commit
  `7b3de99052b8957cda3f9beb244bf9d643c571b7` on
  `cloud-itonami-isic-1073`'s `main` (confirmed as the tip of
  `origin/main` via `gh api
  repos/cloud-itonami/cloud-itonami-isic-1073/commits/main`).
- All source is `.cljc` (portable, grepped clean of JVM-only interop --
  only host-clock access, `System/currentTimeMillis` /
  `js/Date.now`, is reader-conditional guarded).
- `kotoba-lang/industry` registry `"1073"` entry to be updated in place
  (`:repo`/`:business-id` corrected, `:maturity` `:spec` ->
  `:implemented`) via an exact-text in-place edit of the single
  `{:id "1073" ...}` block (no wholesale regeneration);
  `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  to match the live-recomputed implemented-entry count (recomputed via
  a fresh `clojure -M:test` run against a freshly re-fetched
  `origin/main`, not assumed); full `kotoba-lang/industry` suite
  re-run green post-edit and again post-merge against a fresh clone.
