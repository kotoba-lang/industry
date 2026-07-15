# ADR-2608400500: cloud-itonami-isic-0129 (Growing of other perennial crops) perennial-crop-cultivation operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-0161 (Support activities for crop
production -- the reference module shape this actor mirrors, independently
re-read in full before use), the `kotoba-lang/industry` registry's `"0129"`
catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-A0129` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"0129"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-A0129` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests; confirmed 404 via `gh api` before any work began, both for that
placeholder name and for `cloud-itonami/cloud-itonami-isic-0129` itself).
This is part of an ongoing careful, smaller-batch rollout (one ISIC class
per agent, a capable model, mandatory verification) after a prior
18-agent haiku batch produced a 61% defect rate on other ISIC classes;
126+ consecutive agents on this stricter protocol had already succeeded
before this one. This ADR covers ISIC 0129 only. Before any work began,
the registry entry's identity (`{:id "0129" :name "Growing of other
perennial crops"}`) was independently verified against a fresh clone of
`kotoba-lang/industry` (via the GitHub Contents API, not the CDN-cached
`raw.githubusercontent.com`), per this fleet's caution against ID/name
mismatches (prior agents in this fleet had mislabeled other ISIC classes)
-- confirmed correct, no mismatch, and the `:name` was not one of the
~10% of registry entries with a truncated `"..."` seed-data bug.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-0129` as a PERENNIAL-CROP-
CULTIVATION FARM OPERATIONS COORDINATION actor (not field-equipment
operation authority), mirroring `cloud-itonami-isic-0161`'s verified
module shape (`facts`/`registry`/`store`/`governor`/`operation`/`phase`/
`advisor`/`sim`, `deps.edn`/`blueprint.edn`/README/GOVERNANCE/
CODE_OF_CONDUCT/CONTRIBUTING/SECURITY) with fresh, perennial-crop-
cultivation-specific domain logic under the `perennial` namespace. ISIC
0129 is the RESIDUAL (not-elsewhere-classified) perennial-crop growing
division -- perennial crops not already covered by 0121 (grapes), 0122
(tropical/subtropical fruits), 0123 (citrus fruits), 0124 (pome and stone
fruits), 0125 (other tree and bush fruits and nuts), 0126 (oleaginous
fruits), or 0127 (beverage crops). The concrete illustrative crops chosen
for this build are BAMBOO (culm harvest), CORK OAK (bark stripping), and
ornamental/nursery trees and shrubs -- all grown and OWNED by the
operator, which is the key domain distinction from ISIC 0161 (support
activities for crop production, this actor's own reference), whose
operator never owns the crop it services on a fee/contract basis:

1. **`perennial.facts`** -- crop-operation-type safety windows
   (applicator-license currency, sprayer-equipment calibration,
   pre-harvest interval, restricted-entry interval, max wind speed, min
   buffer zone) split across mechanical harvest/maintenance
   crop-operation types (bamboo culm harvest, cork-oak bark stripping,
   ornamental pruning -- `nil` for every chemical-specific field, no
   spray step to have a target for) and chemical-application
   crop-operation types (nursery herbicide broadcast, ornamental
   fungicide foliar spray, bamboo insecticide ground spray -- each with a
   genuine numeric pre-harvest interval/restricted-entry
   interval/max-wind-speed/min-buffer-zone, with insecticide's tighter
   21-day PHI / 24-hour REI / 16 km/h wind ceiling / 30 m buffer vs.
   herbicide's 14-day / 12-hour / 24 km/h / 15 m reflecting insecticide's
   materially higher drift and residue hazard), plus jurisdiction
   evidence-checklist requirements (JP MAFF / US EPA / EU Reg
   1107/2009). The Governor's chemical-specific checks are written to
   skip cleanly on `nil`, never fabricating a spec that doesn't apply to
   a mechanical crop-operation type (mirrors 0161's own nil-guard
   discipline).
2. **`perennial.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify physical/
   regulatory constraints: applicator-license expiry, sprayer-
   calibration age (90-day limit), pre-harvest-interval violation,
   restricted-entry-interval violation, wind-speed exceedance, and
   buffer-zone violation.
3. **`perennial.store`** -- plain-data store (`{:cultivation-lots {...}
   :facts [...]}`) with cultivation-lot lookup/registration/logged/
   scheduled flags and an append-only audit ledger. A cultivation lot is
   the operator's OWN field/plot (never a client farm), which is what the
   store's docstring calls out explicitly as the distinction from
   0161's service-order abstraction.
4. **`perennial.governor`** -- 14 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-cultivation-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies` (all `:effect
   :propose`); anything else -- most importantly direct field-equipment
   operation (harvester, sprayer, applicator) -- is refused unconditionally
   (`:op-not-allowed`), never a soft escalation. A dedicated
   `:field-equipment-or-pesticide-decision-blocked` check adds
   defense-in-depth against a proposal that covertly requests direct
   field-equipment control or a final pesticide-application decision via
   a marker nested inside an otherwise-allowed op's `:value` -- evaluated
   unconditionally against every op, a permanent block never overridable
   by human approval, matching this fleet's established pattern of
   structural (not merely documented) scope boundaries. The
   `:cultivation-lot-not-registered` invariant is applied across ALL FOUR
   allowed ops, per this actor's explicit domain-design requirement that
   a farm/field record be independently verified/registered before any
   action at all. `:flag-crop-health-concern` always escalates to a
   human regardless of confidence, as does `:log-cultivation-record` (the
   one real actuation event this actor performs); `:order-supplies` above
   a 5000 USD cost threshold (`governor/supply-order-cost-threshold-usd`)
   likewise always escalates, while at or below the threshold it may
   auto-commit when the Governor is otherwise clean.
5. **`perennial.phase`** -- `:intake -> :survey -> :advise -> :treat ->
   :record -> :audit`. This sequence was NOT invented fresh: it is the
   registry's own already-registered `:operating-states` for `"0129"`
   (present since the entry's original `:spec` placeholder, and identical
   to 0161's own registered sequence -- both entries share the same
   `:required-technologies` list too), confirmed meaningful (not a
   placeholder sequence) and adopted verbatim rather than overwritten.
   Uses the same portable `keep-indexed`-based `index-of` helper 0161
   established (not the JVM-only `.indexOf`), so this actor ships
   cljs-portable from day one.
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-0161`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).
   `:itonami.blueprint/robotics` is honestly `false` (this actor holds no
   field-equipment-control authority).

### What this actor does NOT do

Harvester, sprayer, and applicator field-equipment operation remain
exclusive to licensed field-equipment operators, permanently, with no
actor or human-approval override path -- enforced structurally by both
the closed operation allowlist and the dedicated
`field-equipment-or-pesticide-decision-blocked` defense-in-depth check,
not just documented. This actor also does not finalize a pesticide-
application decision on its own (same permanent block), and does not
perform custom farm work for OTHER farms' crops on a fee/contract basis
-- that is ISIC 0161, a separate part of this fleet whose operator never
owns the crop it services. This actor also does not cover perennial
crops already assigned their own ISIC class (0121-0127) -- ISIC 0129 is
strictly the residual, not-elsewhere-classified perennial-crop growing
division.

## Verification

- `cloud-itonami-isic-0129`: `clojure -M:test` -- raw final line: `Ran
  51 tests containing 182 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Grepped for stray JVM-only interop (`.indexOf`, `java.`, unguarded
  `System/`) outside `#?(:clj ...)` reader conditionals -- none found;
  every host-clock call is behind a `:clj`/`:cljs` reader conditional.
- Repo created fresh (`gh repo create` + push), initial commit
  `f481533d542ebf7ebdac5a58770f9361de32e497` on
  `cloud-itonami-isic-0129`'s `main` (no prior history), confirmed via
  the GitHub Git Data API commits endpoint. Post-registry-merge
  re-verification (brand-new scratch directory, fresh clone of both
  `cloud-itonami-isic-0129` and a `kotoba-lang/technology` sibling):
  see below.
- `kotoba-lang/industry` registry `"0129"` entry updated in place via a
  direct GitHub Contents API single-file PUT (sha-checked optimistic
  concurrency, exact-block edit only, diff-verified single-block change
  -- only `:repo`/`:business-id`/`:maturity` changed, `:required-
  technologies`/`:optional-technologies`/`:operating-states` left as
  already-registered): `:repo`/`:business-id` corrected from the
  never-populated `gftdcojp/cloud-itonami-A0129` placeholder to
  `cloud-itonami/cloud-itonami-isic-0129`, `:maturity` `:spec` ->
  `:implemented`.
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  (live-recomputed via `(industry/maturity-summary)` against a freshly
  re-fetched `origin/main` immediately before the edit -- not assumed),
  plus a new `testing` block asserting `:implemented` for `"0129"`, using
  a freshly-refetched buffer for the PUT (not a stale read) per this
  fleet's hot-file discipline for this heavily-contended file.
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone plus a fresh `kotoba-lang/technology` sibling):
  `clojure -M:test` re-run green, `(industry/get-industry "0129")`
  confirmed `:maturity :implemented` with the corrected `:repo`/
  `:business-id`, and a sample of 2-3 unrelated entries confirmed intact
  (not clobbered by this edit). No mojibake found in `registry.edn`.
  Exact commit SHAs and raw test-output lines for both repos are recorded
  in the executing agent's final report (this ADR intentionally does not
  duplicate the fast-drifting shared `:implemented` counter's exact value
  at time of writing, since -- as ADR-2608001100 for ISIC 0161 also
  documented -- the fleet runs at extremely high concurrency and the
  counter is continuously advanced by unrelated concurrent agents; this
  promotion's own +1 was independently confirmed correct and intact
  against a fresh post-merge clone).

## Consequences

(+) `cloud-itonami-isic-0129` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"0129"` entry promoted to
`:maturity :implemented`; the shared fleet-wide `:implemented` count
advanced by this promotion's own +1.
(+) `perennial.facts`/`perennial.governor`'s `nil`-guarded
crop-operation-type-conditional checks (chemical-application safety
window only for spray crop-operation types, entirely absent for
mechanical harvest/maintenance) reuse the exact pattern ADR-2608001100
(ISIC 0161) established, now demonstrated on a second, structurally
distinct domain (the operator's OWN crop rather than a serviced client
farm's) -- further validating the pattern's reusability across this
fleet's agriculture cluster.
(+) This is the second ISIC-0129-adjacent actor (after 0161) to reuse the
identical `[:intake :survey :advise :treat :record :audit]` phase
sequence and `[:robotics :identity :forms :dmn :bpmn :audit-ledger
:telemetry]` required-technologies list straight from the registry's
pre-existing `:spec` entry, confirming these two entries were seeded
consistently and did not need correction.
(-) `perennial.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `perennial.operation/run-operation` takes
an already-formed proposal plus an injected `governor-fn` rather than
internally invoking an advisor. This matches `cloud-itonami-isic-0161`'s
own current shape and is a natural, contained future extension, not
required for this ADR's verification bar.
