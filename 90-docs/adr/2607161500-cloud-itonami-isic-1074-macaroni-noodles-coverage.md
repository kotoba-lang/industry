# ADR-2607161500: cloud-itonami-isic-1074 (Manufacture of macaroni, noodles, couscous and similar farinaceous products) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1071 (Bakery products -- the reference
module shape this actor mirrors, independently re-verified before use),
cloud-itonami-isic-1072 (Sugar manufacturing -- second food-manufacturing
reference), the `kotoba-lang/industry` registry's `"1074"` catalog entry
(previously `:spec` with a placeholder `gftdcojp/cloud-itonami-C1074`
repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"1074"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1074` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests). Verified fresh against a clean clone of `kotoba-lang/industry`
before starting: the registry entry's `:id "1074"` and `:name` do
genuinely read "Manufacture of macaroni, noodles, couscous and similar
fari..." (truncated in the registry text but matching the assigned ISIC
class), avoiding the code-mislabel failure mode two earlier agents in
this fleet hit (0892 assumed to be salt when it is actually peat, 0144
assumed to be swine when it is actually sheep/goats). Also confirmed via
`gh api repos/cloud-itonami/cloud-itonami-isic-1074` returning 404 before
any work began -- no prior `cloud-itonami-isic-1074` repo existed under
the `cloud-itonami` org. This is part of an ongoing careful,
smaller-batch rollout (one ISIC class per agent, a capable model,
mandatory verification) after a prior 18-agent haiku batch produced a
61% defect rate (empty implementations, missing modules, false "all
green" reports) on other ISIC classes; 24+ consecutive agents on this
stricter protocol have all succeeded since. This ADR covers ISIC 1074
only, built and verified from scratch.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1074` as a macaroni/noodle/
couscous-manufacturing PLANT-OPERATIONS COORDINATION actor (not
extrusion/drying-line control authority), mirroring
`cloud-itonami-isic-1071`'s verified module shape (`facts`/`registry`/
`store`/`governor`/`operation`/`phase`/`advisor`/`sim`, `deps.edn`/
`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY)
with fresh, farinaceous-product-specific domain logic under the
`pastaops` namespace:

1. **`pastaops.facts`** -- product-type extrusion/drying windows
   (drying-temp-min/max/drying-time-min/max/moisture-target/tolerance,
   by product id: macaroni elbow, spaghetti, egg noodles, couscous --
   egg noodles dry at a deliberately lower temperature window,
   50-60°C vs. macaroni/spaghetti's 82-95°C, reflecting that egg
   protein is heat-sensitive; couscous, being small granules rather
   than extruded shapes, dries fastest, 60-120 minutes vs. pasta's
   180-300), jurisdiction allergen-declaration and evidence-checklist
   requirements (JP/US/EU, mirroring bakery's allergen-jurisdiction
   shape), and a wheat(durum semolina)/egg ingredient-allergen table
   distinct from bakery's wheat/egg/milk/nuts/sesame/soy table (no
   dairy or nut ingredients in this domain's product types).
2. **`pastaops.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify
   physical/operational constraints: drying-temperature range,
   drying-time ceiling, moisture tolerance (framed explicitly as a
   mold-growth food-safety boundary for dried farinaceous products,
   referencing CODEX STAN 249-2006's 12.5% m/m moisture ceiling for
   dried pasta -- unlike bakery's baking-moisture window, which is
   primarily a texture/quality concern), sanitation-score floor,
   ingredient/dosing-scale calibration age (180-day limit, matching
   bakery's mixing-scale interval), weight variance, and allergen-label
   risk.
3. **`pastaops.store`** -- plain-data store (`{:batches {...} :facts
   [...]}`) with batch lookup/registration/processed/shipment-finalized
   flags and an append-only audit ledger.
4. **`pastaops.governor`** -- 15 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-production-batch`, `:schedule-maintenance`,
   `:flag-food-safety-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct extrusion/
   drying-line control or food-safety certification authority -- is
   refused unconditionally (`:op-not-allowed`), never a soft
   escalation. `:flag-food-safety-concern` always escalates to a human
   regardless of confidence, as do the two real actuation events
   (`:log-production-batch`/`:coordinate-shipment`). A batch must be
   independently verified/registered in the store before
   `:coordinate-shipment` can be proposed against it
   (`:batch-not-registered`).
5. **`pastaops.phase`** -- `:intake -> :design -> :produce -> :inspect
   -> :package -> :audit -> :archived`, matching
   `cloud-itonami-isic-1071`'s phase sequence. Also fixes that
   reference's latent JVM-only `.indexOf` interop bug (ClojureScript's
   `PersistentVector` does not implement `.indexOf`) with a portable
   `keep-indexed`-based `index-of` helper, keeping `pastaops.phase`
   genuinely `.cljc`-portable rather than JVM-only in practice (per
   this repo's cljs-first/no-JVM-interop mandate and the same fix
   already applied by `cloud-itonami-isic-1061`'s `millops.phase`).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1071`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).

### What this actor does NOT do

Extruder/dryer equipment operation and food-safety certification
authority remain exclusive to licensed pasta-plant staff and regulators,
permanently, with no actor or human-approval override path -- enforced
structurally by the closed operation allowlist, not just documented.

## Verification

- `cloud-itonami-isic-1074`: `clojure -M:test` -- raw final line: `Ran
  41 tests containing 135 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Independently re-verified: fresh `git clone --depth 1` into a new
  scratch directory after push, re-ran `clojure -M:test` against the
  clean clone -- same green result (`Ran 41 tests containing 135
  assertions.` / `0 failures, 0 errors.`).
- Repo created fresh (`gh repo create cloud-itonami/cloud-itonami-isic-1074`
  + push), commit `0b36a72` on `cloud-itonami-isic-1074`'s `main`
  (initial commit, no prior history).
- `kotoba-lang/industry` registry `"1074"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-C1074` placeholder to
  `cloud-itonami/cloud-itonami-isic-1074`, `:required-technologies`/
  `:optional-technologies` trimmed to the `:implemented`-tier shape
  (`[:identity :forms :audit-ledger :cae]` / `[:telemetry]`, matching
  every other implemented food-manufacturing entry), `:maturity` `:spec`
  -> `:implemented`); `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion bumped from the live-recomputed baseline
  (confirmed 213 via a fresh `clojure -M:test` run against a freshly
  re-fetched `origin/main`, not assumed) to 214 implemented entries;
  full `kotoba-lang/industry` suite re-run green post-edit and again
  post-merge against a fresh clone (see registry PR/merge commit for raw
  output).

## Consequences

(+) `cloud-itonami-isic-1074` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"1074"` entry promoted to
`:maturity :implemented`, count 213 -> 214.
(+) `pastaops.phase`'s portable `index-of` helper (fixing the JVM-only
`.indexOf` bug carried in the mirrored `cloud-itonami-isic-1071`
reference) is available as a copy-paste pattern for any future actor
still mirroring the unfixed `bakeryops.phase` shape.
(-) `pastaops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `pastaops.operation/run-operation` takes
an already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-1071`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.
