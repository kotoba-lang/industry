# ADR-2611200000: cloud-itonami-isic-0170 (Hunting, trapping and related service activities) wildlife-harvest operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-0161 (Support activities for crop
production -- the reference module shape this actor mirrors, independently
re-read in full before use), the `kotoba-lang/industry` registry's
`"0170"` catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-A0170` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"0170"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-A0170` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests; confirmed 404 via `gh api` before any work began for
`cloud-itonami/cloud-itonami-isic-0170` itself). This is part of an
ongoing careful, smaller-batch rollout (one ISIC class per agent, a
capable model, mandatory verification) after a prior 18-agent haiku batch
produced a 61% defect rate on other ISIC classes; 132+ consecutive agents
on this stricter protocol had all succeeded since. This ADR covers ISIC
0170 only. Before any work began, the registry entry's identity (`{:id
"0170" :name "Hunting, trapping and related service activities"}`) was
independently verified against a fresh clone of `kotoba-lang/industry`,
per this fleet's caution against ID/name mismatches (prior agents in this
fleet had mislabeled other ISIC classes) -- confirmed correct, no
mismatch.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-0170` as a wildlife-harvest
OPERATIONS COORDINATION actor (not direct firearm/trap-deployment
authority, not a wildlife-license-issuing authority), mirroring
`cloud-itonami-isic-0161`'s verified module shape
(`facts`/`registry`/`store`/`governor`/`operation`/`phase`/`advisor`/
`sim`, `deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY) with fresh, wildlife-harvest-specific domain logic
under the `huntharvest` namespace. ISIC 0170 covers COMMERCIAL/LICENSED
wildlife harvesting -- for meat, pelts, or population-control purposes --
and related service activities (e.g. game-ranching support services),
performed under an independently-verified harvest license and species
quota allocation, which drives every domain-specific check below:

1. **`huntharvest.facts`** -- harvest-method safety windows (trap-check
   interval, minimum trap setback distance) split across trap-based
   harvest methods (leg-hold fur-bearer trapping, live-cage predator-
   control trapping -- each with a genuine trap-check interval, a
   humane-treatment requirement, and a minimum trap-setback distance, a
   public-safety requirement) and direct-take harvest methods (big-game
   rifle, small-game shotgun -- `nil` for both trap-specific fields, no
   trap to check or set back), plus jurisdiction evidence-checklist
   requirements (JP wildlife protection law / US USFWS / EU Habitats
   Directive). Hunter/trapper license currency, trap-equipment inspection
   currency, species quota, and open-season windows all apply to EVERY
   harvest method regardless of shape -- every jurisdiction in this
   actor's scope requires a license/tag and a quota allocation for both
   hunting and trapping, unlike 0161's chemical-application-gated
   applicator-license check. The Governor's trap-specific checks are
   written to skip cleanly on `nil`, never fabricating a spec that
   doesn't apply to a direct-take method.
2. **`huntharvest.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify physical/
   regulatory constraints: hunter-license expiry, trap-inspection age
   (180-day limit), trap-check-interval violation, trap-setback
   violation, species-quota exceedance, and open-season violation.
3. **`huntharvest.store`** -- plain-data store (`{:harvest-records {...}
   :facts [...]}`) with harvest-record lookup/registration/logged/
   scheduled/shipped flags and an append-only audit ledger.
4. **`huntharvest.governor`** -- 14 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-harvest-record`, `:schedule-harvest-operation`,
   `:flag-conservation-concern`, `:coordinate-shipment` (all `:effect
   :propose`); anything else -- most importantly direct firearm/trap-
   deployment control -- is refused unconditionally (`:op-not-allowed`),
   never a soft escalation. A dedicated
   `:firearm-or-trap-deployment-or-licensing-authority-blocked` check
   adds defense-in-depth against a proposal that covertly requests
   direct firearm/trap-deployment control or a wildlife-license-issuing-
   authority decision via a marker nested inside an otherwise-allowed
   op's `:value` -- evaluated unconditionally against every op, a
   permanent block never overridable by human approval, matching this
   fleet's established pattern of structural (not merely documented)
   scope boundaries. The `:harvest-record-not-registered` invariant is
   applied across ALL FOUR allowed ops, per this actor's explicit
   domain-design requirement that a harvest-license/quota record be
   independently verified/registered before any action at all.
   `:flag-conservation-concern` always escalates to a human regardless of
   confidence, as does `:log-harvest-record` (the one real actuation
   event this actor performs); `:coordinate-shipment` above a 5000 USD
   value threshold (`governor/shipment-value-threshold-usd`) likewise
   always escalates, while at or below the threshold it may auto-commit
   when the Governor is otherwise clean.
5. **`huntharvest.phase`** -- `:intake -> :survey -> :advise -> :treat ->
   :record -> :audit`. This sequence was NOT invented fresh: it is the
   registry's own already-registered `:operating-states` for `"0170"`
   (present since the entry's original `:spec` placeholder, and identical
   to `"0161"`'s own sequence), confirmed meaningful (not a placeholder
   sequence) and adopted verbatim rather than overwritten. Uses the same
   portable `keep-indexed`-based `index-of` helper 0161 established (not
   the JVM-only `.indexOf`), so this actor ships cljs-portable from day
   one.
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-0161`'s shape (`:test`/`:lint`/`:run`/`:dev`
   aliases, `itonami.blueprint/*` metadata, scope/design/testing README
   sections). `:itonami.blueprint/robotics` is honestly `false` (this
   actor holds no firearm/trap-deployment-control authority).

### What this actor does NOT do

Discharging a firearm and setting/checking a trap remain exclusive to the
licensed hunter/trapper in the field, permanently, with no actor or
human-approval override path -- enforced structurally by both the closed
operation allowlist and the dedicated
`firearm-or-trap-deployment-or-licensing-authority-blocked` defense-in-
depth check, not just documented. This actor also does not issue or
finalize a wildlife harvest license on its own (same permanent block),
and does not set or allocate species quotas -- it only coordinates
against an already-independently-verified allocation.

## Verification

- `cloud-itonami-isic-0170`: `clojure -M:test` -- raw final line: `Ran 52
  tests containing 182 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- All source under `src/`/`test/` is `.cljc`; the only host-clock call
  (`now-epoch-ms` in `huntharvest.governor`) is behind a `:clj`/`:cljs`
  reader conditional, no stray JVM-only interop.
- Repo created fresh (`gh repo create` + push), initial commit
  `1aebef498552e474d4767679f866db79969f2a88` on
  `cloud-itonami-isic-0170`'s `main` (no prior history), confirmed via
  `git rev-parse HEAD` == the branch tip returned by the GitHub commits
  API.
- `kotoba-lang/industry` registry `"0170"` entry updated in place (exact-
  text edit of the literal `{:id "0170" ...}` block only, no other
  entry's block touched): `:repo`/`:business-id` corrected from the
  never-populated `gftdcojp/cloud-itonami-A0170` placeholder to
  `cloud-itonami/cloud-itonami-isic-0170` / `cloud-itonami-isic-0170`,
  `:maturity` `:spec` -> `:implemented`, `:required-technologies`/
  `:optional-technologies`/`:operating-states` left as already-
  registered.
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  (live-recomputed via `(industry/maturity-summary)` against a freshly
  re-fetched `origin/main` immediately before the edit, not assumed --
  this is an extremely hot, high-concurrency shared file; the first
  server-side merge attempt (`gh api repos/kotoba-lang/industry/merges`)
  hit a genuine `409 Merge conflict` from a concurrent sibling landing,
  resolved by re-fetching `origin/main`, redoing the exact-block edit and
  the count re-sync against the fresh tip, and retrying -- the second
  attempt landed clean).
- Full `kotoba-lang/industry` suite re-run green after the registry edit
  (fresh clone, plus a fresh `kotoba-lang/technology` sibling clone for
  `deps.edn` resolution). Registry merge commit
  `44506f800377ed322e7993b5a09fef27bbd30231` (parents
  `50953695efc0f5766dbfca5a82d938157e58c642` and
  `5d73de2dd9f150f04390bee19c321e438ee0f998`), landed on `main` via the
  GitHub merges API, no PR needed.
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone of both `cloud-itonami-isic-0170` and
  `kotoba-lang/industry`, plus a fresh `kotoba-lang/technology` sibling):
  `clojure -M:test` re-run green in the actor repo; the registry's
  `"0170"` entry confirmed `:maturity :implemented` with the corrected
  `:repo`/`:business-id`, and a sample of 2-3 unrelated entries confirmed
  untouched. `grep -c "�" resources/kotoba/industry/registry.edn`
  returned `0` (no file-wide UTF-8 mojibake).

## Consequences

(+) `cloud-itonami-isic-0170` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"0170"` entry promoted to `:maturity
:implemented`.
(+) `huntharvest.facts`/`huntharvest.governor`'s `nil`-guarded harvest-
method-conditional checks (trap-specific safety window only for
trap-based methods, entirely absent for direct-take methods) are a
reusable pattern for any future ISIC class in this fleet's
agriculture/forestry/fishing cluster whose catalog genuinely varies which
physical/regulatory constraints apply per sub-type -- while the
universally-applicable checks (license, quota, season) demonstrate the
complementary pattern of a constraint that applies across every sub-type
regardless of method shape.
(+) The `:coordinate-shipment` value-threshold escalation
(`shipment-value-threshold-usd`) mirrors 0161's `:order-supplies`
cost-threshold pattern, giving low-value shipments a genuine auto-commit
path while high-value ones still require a human.
(+) The `firearm-or-trap-deployment-or-licensing-authority-blocked`
defense-in-depth check (evaluated against every op, not just a specific
one) demonstrates that a scope boundary can be enforced both by the
closed op-allowlist AND by an independent marker-based check nested
inside an otherwise-permitted proposal's own `:value` -- closing a
covert-request loophole the allowlist alone does not structurally
prevent.
(-) `huntharvest.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `huntharvest.operation/run-operation`
takes an already-formed proposal plus an injected `governor-fn` rather
than internally invoking an advisor. This matches
`cloud-itonami-isic-0161`'s own current shape and is a natural, contained
future extension, not required for this ADR's verification bar.
