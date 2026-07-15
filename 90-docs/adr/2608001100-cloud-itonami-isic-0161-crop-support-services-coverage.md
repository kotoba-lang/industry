# ADR-2608001100: cloud-itonami-isic-0161 (Support activities for crop production) crop-support-service operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-0163 (Post-harvest crop activities -- the
reference module shape this actor mirrors, independently re-read in full
before use), cloud-itonami-isic-0164 (Seed processing for propagation,
0163's own upstream reference), the `kotoba-lang/industry` registry's
`"0161"` catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-A0161` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"0161"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-A0161` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests; confirmed 404 via `gh api` before any work began, both for that
placeholder name and for `cloud-itonami/cloud-itonami-isic-0161` itself).
This is part of an ongoing careful, smaller-batch rollout (one ISIC class
per agent, a capable model, mandatory verification) after a prior
18-agent haiku batch produced a 61% defect rate on other ISIC classes;
108+ consecutive agents on this stricter protocol have all succeeded
since. This ADR covers ISIC 0161 only. Before any work began, the
registry entry's identity (`{:id "0161" :name "Support activities for
crop production"}`) was independently verified against a fresh clone of
`kotoba-lang/industry`, per this fleet's caution against ID/name
mismatches (prior agents in this fleet had mislabeled other ISIC classes)
-- confirmed correct via `grep -oE '\{[^{}]*:id "0161"[^{}]*\}'` against
the freshly cloned `resources/kotoba/industry/registry.edn`, no mismatch.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-0161` as a crop-support-service
OPERATIONS COORDINATION actor (not field-equipment operation authority),
mirroring `cloud-itonami-isic-0163`'s verified module shape
(`facts`/`registry`/`store`/`governor`/`operation`/`phase`/`advisor`/
`sim`, `deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY) with fresh, crop-support-service-specific domain
logic under the `cropsupport` namespace. ISIC 0161 covers CUSTOM FARM
WORK performed for OTHER farms' crops on a fee/contract basis
(harvesting, spraying, pest-control) -- the operator never grows or owns
the crop being serviced, which is the key domain distinction from the
growing divisions (011x) themselves, and it drives every domain-specific
check below:

1. **`cropsupport.facts`** -- service-type safety windows (applicator-
   license currency, sprayer-equipment calibration, pre-harvest interval,
   restricted-entry interval, max wind speed, min buffer zone) split
   across mechanical harvest service types (combine-grain, hay-baling --
   `nil` for every chemical-specific field, no drying/spray step to have
   a target for) and chemical-application service types
   (herbicide-broadcast, fungicide-foliar, insecticide-ground -- each
   with a genuine numeric pre-harvest interval/restricted-entry
   interval/max-wind-speed/min-buffer-zone, with insecticide's tighter
   21-day PHI / 24-hour REI / 16 km/h wind ceiling / 30 m buffer vs.
   herbicide's 14-day / 12-hour / 24 km/h / 15 m reflecting insecticide's
   materially higher drift and residue hazard), plus jurisdiction
   evidence-checklist requirements (JP MAFF / US EPA / EU Reg 1107/2009).
   The Governor's chemical-specific checks are written to skip cleanly
   on `nil`, never fabricating a spec that doesn't apply to a mechanical
   service type (mirrors 0163's own moisture/cold-chain nil-guard
   discipline).
2. **`cropsupport.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify physical/
   regulatory constraints: applicator-license expiry, sprayer-
   calibration age (90-day limit), pre-harvest-interval violation,
   restricted-entry-interval violation, wind-speed exceedance, and
   buffer-zone violation.
3. **`cropsupport.store`** -- plain-data store (`{:service-orders {...}
   :facts [...]}`) with service-order lookup/registration/logged/
   scheduled flags and an append-only audit ledger.
4. **`cropsupport.governor`** -- 14 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-service-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies` (all `:effect
   :propose`); anything else -- most importantly direct field-equipment
   operation (combine, sprayer, applicator) -- is refused unconditionally
   (`:op-not-allowed`), never a soft escalation. A dedicated
   `:field-equipment-or-pesticide-decision-blocked` check adds
   defense-in-depth against a proposal that covertly requests direct
   field-equipment control or a final pesticide-application decision via
   a marker nested inside an otherwise-allowed op's `:value` -- evaluated
   unconditionally against every op, a permanent block never overridable
   by human approval, matching this fleet's established pattern of
   structural (not merely documented) scope boundaries. The
   `:service-order-not-registered` invariant is applied across ALL FOUR
   allowed ops (broader than 0163's shipment-only registration check),
   per this actor's explicit domain-design requirement that a
   service-order/client-farm record be independently verified/registered
   before any action at all. `:flag-crop-health-concern` always
   escalates to a human regardless of confidence, as does
   `:log-service-record` (the one real actuation event this actor
   performs); `:order-supplies` above a 5000 USD cost threshold
   (`governor/supply-order-cost-threshold-usd`) likewise always
   escalates, while at or below the threshold it may auto-commit when
   the Governor is otherwise clean -- a genuinely new value-driven
   escalation dimension for this fleet's crop-support/agriculture
   cluster.
5. **`cropsupport.phase`** -- `:intake -> :survey -> :advise -> :treat ->
   :record -> :audit`. This sequence was NOT invented fresh: it is the
   registry's own already-registered `:operating-states` for `"0161"`
   (present since the entry's original `:spec` placeholder), confirmed
   meaningful (not a placeholder sequence) and adopted verbatim rather
   than overwritten. Uses the same portable `keep-indexed`-based
   `index-of` helper 0163/0164 established (not the JVM-only
   `.indexOf`), so this actor ships cljs-portable from day one.
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-0163`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).
   `:itonami.blueprint/robotics` is honestly `false` (this actor holds no
   field-equipment-control authority).

### What this actor does NOT do

Combine, sprayer, and applicator field-equipment operation remain
exclusive to licensed field-equipment operators, permanently, with no
actor or human-approval override path -- enforced structurally by both
the closed operation allowlist and the dedicated
`field-equipment-or-pesticide-decision-blocked` defense-in-depth check,
not just documented. This actor also does not finalize a pesticide-
application decision on its own (same permanent block), and does not
grow or own the crop being serviced -- that is ISIC 011x, the growing
divisions, a separate part of this fleet.

## Verification

- `cloud-itonami-isic-0161`: `clojure -M:test` -- raw final line: `Ran
  51 tests containing 178 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Grepped for stray JVM-only interop (`.indexOf`, `java.`, unguarded
  `System/`) outside `#?(:clj ...)` reader conditionals -- none found;
  every host-clock call is behind a `:clj`/`:cljs` reader conditional.
- Repo created fresh (`gh repo create` + push), initial commit
  `19fbe657e071bbefb31a1fb33612d05da81dc36e` on
  `cloud-itonami-isic-0161`'s `main` (no prior history), confirmed via
  `git rev-parse HEAD` == the branch tip returned by the GitHub Git Data
  API. Independently re-verified against a brand-new fresh clone: `Ran
  51 tests containing 178 assertions.` / `0 failures, 0 errors.`
- `kotoba-lang/industry` registry `"0161"` entry updated in place via a
  direct GitHub Contents API single-file PUT (sha-checked optimistic
  concurrency, exact-block edit only, diff-verified single-block change
  -- only `:repo`/`:business-id`/`:maturity` changed, `:required-
  technologies`/`:optional-technologies`/`:operating-states` left as
  already-registered): `:repo`/`:business-id` corrected from the
  never-populated `gftdcojp/cloud-itonami-A0161` placeholder to
  `cloud-itonami/cloud-itonami-isic-0161`, `:maturity` `:spec` ->
  `:implemented`. Landed directly on `main`, commit
  `78164f791645d7e9bfa7e943e373a3b7872fbf52` (parent
  `226ae713dd7dde198d5f99e84b173ceadd67da79`, the freshly re-fetched tip
  immediately prior).
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  (live-recomputed via `(industry/maturity-summary)` against a freshly
  re-fetched `origin/main` immediately before each edit -- not assumed),
  plus a new `testing` block asserting `:implemented` for `"0161"`. This
  fleet is running at extremely high concurrency (100+ agents active);
  the shared count drifted repeatedly during this promotion's own
  verification window: 324 (this promotion's own pre-edit baseline) ->
  325 (further concurrent landings) -> this promotion's own edit 325 ->
  326, merged clean via `gh api repos/kotoba-lang/industry/merges`,
  commit `7a4a81152bb49be4ffed61e524970368473b6ab9` -> a subsequent
  326 -> 328 re-verification attempt found the count already correctly
  advanced by other concurrent agents' own bumps (no edit needed) -> a
  328 -> 329 bump merged clean, commit
  `f2e68615aa95596c41af6e203aa6f9942f05f331` -> a final 329 -> 330
  attempt hit a genuine `409 Merge conflict`, and re-fetching confirmed
  another concurrent agent had already landed the identical fix
  (`main` tip commit `94864a6836ab7f9c147ed9656797664d4122af08`,
  "bump industry_test.clj :implemented count to 330"). Rather than chase
  this shared, self-healing counter indefinitely (out of this ADR's
  ISIC-0161-only scope, and every other concurrently active agent is
  independently converging on the same live-recomputed value), this
  promotion's own registry-entry work was independently re-verified
  correct and intact at every stage -- see below. Full
  `kotoba-lang/industry` suite re-run green after every one of this
  promotion's own edits (fresh clone each time, plus a fresh
  `kotoba-lang/technology` sibling clone for `deps.edn` resolution).
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone at commit `f2e68615aa95596c41af6e203aa6f9942f05f331`
  plus a fresh `kotoba-lang/technology` sibling): `(industry/get-industry
  "0161")` returned `{:id 0161, :name Support activities for crop
  production, :repo https://github.com/cloud-itonami/cloud-itonami-isic-0161,
  :business-id cloud-itonami-isic-0161, ... :maturity :implemented,
  :operating-states [:intake :survey :advise :treat :record :audit]}` --
  entry survived every subsequent concurrent commit intact.
  `grep -c "â" resources/kotoba/industry/registry.edn` returned `0` (no
  file-wide UTF-8 mojibake).

## Consequences

(+) `cloud-itonami-isic-0161` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"0161"` entry promoted to
`:maturity :implemented`; the shared fleet-wide `:implemented` count
advanced by this promotion's own +1 (independently confirmed against
multiple fresh clones across the promotion's own verification window,
notwithstanding the count's continuous concurrent drift from unrelated
agents' simultaneous promotions).
(+) `cropsupport.facts`/`cropsupport.governor`'s `nil`-guarded
service-type-conditional checks (chemical-application safety window only
for spray/pest-control service types, entirely absent for mechanical
harvest) are a reusable pattern for any future ISIC class in this
fleet's agriculture cluster whose catalog genuinely varies which
physical/regulatory constraints apply per sub-type.
(+) The `:order-supplies` cost-threshold escalation
(`supply-order-cost-threshold-usd`) is this actor's own value-driven
escalation dimension, distinct from the always-escalate/always-hard
binary most prior fleet actors use for their non-actuation ops --
a genuinely new degree of freedom for low-risk, low-cost proposals to
auto-commit while high-cost ones still require a human.
(+) The `field-equipment-or-pesticide-decision-blocked` defense-in-depth
check (evaluated against every op, not just a specific one) demonstrates
that a scope boundary can be enforced both by the closed op-allowlist
AND by an independent marker-based check nested inside an
otherwise-permitted proposal's own `:value` -- closing a covert-request
loophole the allowlist alone does not structurally prevent.
(-) `cropsupport.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `cropsupport.operation/run-operation`
takes an already-formed proposal plus an injected `governor-fn` rather
than internally invoking an advisor. This matches
`cloud-itonami-isic-0163`'s own current shape and is a natural,
contained future extension, not required for this ADR's verification
bar.
