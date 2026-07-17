# ADR-2780000128: cloud-itonami-isic-0128 (Growing of spices, aromatic, drug and pharmaceutical crops) farm-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-17
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-0129 (Growing of other perennial crops --
the reference module shape this actor mirrors most closely, independently
re-read in full before use), the `kotoba-lang/industry` registry's `"0128"`
catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-A0128` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"0128"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-A0128` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests; confirmed via three independent methods -- REST `gh api`
repeated 404, GraphQL `gh repo view` "Could not resolve to a Repository",
and the search API `total_count: 0` -- that no repo exists at the real
`cloud-itonami/cloud-itonami-isic-0128` target either, before any work
began). This ADR covers ISIC 0128 only, and closes out **Wave 3's last
genuine implementation gap** (production/robotics wave, ADR-2607121000):
Wave 3 was otherwise already complete apart from this class and two
deliberately-excluded sensitive classes unrelated to it (2520 weapons,
3040 military vehicles). Before any work began, the registry entry's
identity (`{:id "0128" :name "Growing of spices, aromatic, drug and
pharmaceutical crops"}`) was independently verified against a fresh clone
of `kotoba-lang/industry`, per this fleet's caution against ID/name
mismatches -- confirmed correct, no mismatch, and the `:name` was not one
of the registry's known truncated-`"..."` seed-data entries (already
fully spelled out). The separate, redundant 3-digit group entry `{:id
"012" ...}` was identified and deliberately left untouched -- only the
`"0128"` class-level block was edited.

**Regulatory-compliance sensitivity, distinct from the two permanently-
excluded classes**: ISIC 0128 includes licit medicinal/pharmaceutical-
precursor crop cultivation (e.g. licensed opium poppy for pharmaceutical
morphine production, licensed coca leaf for pharmaceutical/traditional
use in the few jurisdictions where this is legal, licensed cannabis for
pharmaceutical use) alongside entirely mundane spice/aromatic crops
(pepper, vanilla, cinnamon, mint). This class was **not** flagged as one
of the two permanently-excluded sensitive classes -- it is a legitimate,
buildable agricultural-coordination actor -- but the drug/pharmaceutical-
crop sub-scope required a real regulatory-compliance guardrail: the
closed op allowlist never includes any op that directly finalizes a
controlled-substance cultivation-license approval/renewal or a
diversion-control-compliance clearance -- always a hard, permanent block
or an always-escalate op, never auto-commit-eligible (see Decision §2
below).

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-0128` as a SPICE/AROMATIC/
DRUG-AND-PHARMACEUTICAL-CROP FARM OPERATIONS COORDINATION actor (not
cultivation-license or diversion-control-clearance authority), mirroring
`cloud-itonami-isic-0129`'s verified module shape (`facts`/`registry`/
`store`/`governor`/`operation`/`phase`/`advisor`/`sim`, `deps.edn`/
`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY)
with fresh, spice/aromatic/drug-and-pharmaceutical-crop-specific domain
logic under the `spicecrop` namespace:

1. **`spicecrop.facts`** -- crop-category compliance shapes split across
   non-controlled spice/aromatic crop categories (black pepper, vanilla,
   cinnamon, peppermint -- `false` for every controlled-substance-crop
   field, no license/quota-tracking requirement to have a target for) and
   controlled substance crop categories (licensed opium poppy, licensed
   coca leaf, licensed medicinal cannabis -- each with a genuine
   cultivation-license expiry requirement, quota-tracking reconciliation
   freshness requirement, and licensed-quota ceiling), plus jurisdiction
   evidence-checklist requirements (JP MAFF narcotics-control / US
   DEA-USDA / EU Reg 1307/2013). The Governor's controlled-crop-specific
   checks are written to skip cleanly when a crop category has no
   license/quota requirement, never fabricating a spec that doesn't apply
   to a non-controlled crop category.
2. **`spicecrop.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify physical/
   regulatory constraints: cultivation-license expiry, quota-tracking
   reconciliation lapse (30-day limit), and harvest-quota-exceeded (a
   reported harvest above the farm-lot's licensed quota ceiling -- a
   genuine diversion-risk compliance signal).
3. **`spicecrop.store`** -- plain-data store (`{:farm-lots {...} :facts
   [...]}`) with farm-lot lookup/registration/logged/scheduled flags and
   an append-only audit ledger. A farm-lot is the operator's OWN field/
   plot with an independently-verified/registered farm/grower-license
   record already on file -- this actor only checks that the record
   exists, it never issues that registration itself.
4. **`spicecrop.governor`** -- 11 independently-verified hard-violation
   checks plus a closed operation allowlist as a hard, permanent block
   per the domain design: the advisor may only ever propose
   `:log-harvest-record`, `:schedule-farm-operation`,
   `:flag-compliance-concern`, `:coordinate-supply-order` (all `:effect
   :propose`); anything else is refused unconditionally (`:op-not-
   allowed`), never a soft escalation. A dedicated
   `:cultivation-license-or-diversion-clearance-blocked` check adds
   defense-in-depth against a proposal that covertly requests to finalize
   a controlled-substance cultivation-license approval/renewal or a
   diversion-control-compliance clearance via explicit boolean `:value`
   flags (`:finalize-cultivation-license-approval?` /
   `:finalize-cultivation-license-renewal?` /
   `:finalize-diversion-control-clearance?`) -- evaluated unconditionally
   against every op, a permanent block never overridable by human
   approval. The `:farm-lot-not-registered` invariant is applied across
   ALL FOUR allowed ops, per this actor's explicit domain-design
   requirement that a farm/grower-license record be independently
   verified/registered before any action at all. `:flag-compliance-
   concern` always escalates to a human regardless of confidence, as does
   `:log-harvest-record` (the one real actuation event this actor
   performs); `:coordinate-supply-order` above a 5000 USD cost threshold
   (`governor/supply-order-cost-threshold-usd`) likewise always
   escalates, while at or below the threshold it may auto-commit when the
   Governor is otherwise clean. A `phase-auto-ops` map (per-phase
   auto-commit-eligible op sets, plus an `auto-eligible-at-phase?`
   defense-in-depth helper) makes the same invariant structural at a
   second layer: `:flag-compliance-concern` and `:log-harvest-record` are
   deliberately absent from every phase's auto-commit set, and no op that
   would finalize a cultivation-license or diversion-control-clearance
   decision appears anywhere in `phase-auto-ops` at all, because no such
   op is ever a member of `allowed-ops` in the first place.

   **Known self-tripping bug class, guarded against by construction and
   by a dedicated regression test.** Multiple sibling actors in this
   fleet have independently discovered and fixed the same bug class: a
   Governor scope-exclusion term list phrased as a bare noun (e.g.
   "license") can accidentally match inside the advisor's own default
   rationale/disclaimer text for a legitimate, allowed proposal --
   causing the actor to self-block on its own happy path. This actor's
   `:cultivation-license-or-diversion-clearance-blocked` rule is phrased
   as the finalization/execution ACTION (explicit `:finalize-*?` boolean
   `:value` flags) and never scans `:rationale` text at all --
   structurally immune to this bug class. `spicecrop.advisor/default-
   mock-proposals` deliberately includes disclaimer text containing the
   words "license", "clearance", and "diversion" as ordinary, correct
   compliance language for all four allowed ops, and a dedicated test,
   `default-mock-proposals-never-self-trip-scope-exclusion-test` in
   `governor_test.cljc`, asserts every one of them clears the governor
   with `:cultivation-license-or-diversion-clearance-blocked` and
   `:op-not-allowed` both absent from its violations, before this build
   was considered done. A GitHub code search for the exact governor
   keyword (`:spice-crop-governor`) confirmed no collision anywhere else
   in the `cloud-itonami` org before finalizing.
5. **`spicecrop.phase`** -- `:intake -> :survey -> :advise -> :treat ->
   :record -> :audit`. This sequence was not invented fresh: it is the
   registry's own already-registered `:operating-states` for `"0128"`
   (identical to sibling ISIC 0129's registered sequence -- both entries
   share the same `:required-technologies` list too), confirmed
   meaningful and adopted verbatim rather than overwritten. Uses the same
   portable `keep-indexed`-based `index-of` helper the 012x lineage
   established (not the JVM-only `.indexOf`), so this actor ships
   cljs-portable from day one.
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-0129`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).
   `:itonami.blueprint/robotics` is honestly `false` (this actor holds no
   license/clearance-finalization authority).

### What this actor does NOT do

Finalizing a controlled-substance cultivation-license approval or
renewal, and finalizing a diversion-control-compliance clearance, remain
exclusive to the licensing/regulatory authority, permanently, with no
actor or human-approval override path -- enforced structurally by both
the closed operation allowlist and the dedicated
`cultivation-license-or-diversion-clearance-blocked` defense-in-depth
check, not just documented. This actor also does not perform custom farm
work for other farms' crops, and does not cover regulatory interpretation
beyond the jurisdiction specifications a proposal cites -- the Governor
enforces only published requirements, never inventing one.

## Verification

- `cloud-itonami-isic-0128`: `clojure -M:test` -- raw final line: `Ran 45
  tests containing 198 assertions.` / `0 failures, 0 errors.` Re-run
  green against a brand-new fresh clone of the pushed repo.
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Grepped for stray JVM-only interop (`.indexOf`, `java.`, unguarded
  `System/`) outside `#?(:clj ...)` reader conditionals -- none found;
  every host-clock call is behind a `:clj`/`:cljs` reader conditional.
  All source is `.cljc`.
- Repo created fresh (`gh repo create` + push, first attempt succeeded, no
  secondary rate-limit retry needed), initial commit on
  `cloud-itonami-isic-0128`'s `main` (no prior history), confirmed via
  the GitHub Commits API to match the local pushed commit exactly.
- `kotoba-lang/industry` registry `"0128"` entry updated in place (exact-
  text edit of the existing `{:id "0128" ...}` block only, never the
  separate `{:id "012" ...}` group entry): `:repo`/`:business-id`
  de-placeholdered from `https://github.com/gftdcojp/cloud-itonami-A0128`
  / `cloud-itonami-A0128` to `https://github.com/cloud-itonami/cloud-
  itonami-isic-0128` / `cloud-itonami-isic-0128`, `:maturity` `:spec` ->
  `:implemented`, `:required-technologies`/`:optional-technologies`/
  `:operating-states` left as already-registered (verified meaningful,
  matching the actor's actual phase sequence).
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  (live-recomputed via `(industry/maturity-summary)` against a freshly
  re-fetched `origin/main` immediately before the edit -- not assumed),
  using a freshly-refetched buffer for the PUT per this fleet's hot-file
  discipline for this heavily-contended file.
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone plus a fresh `kotoba-lang/technology` sibling):
  `clojure -M:test` re-run, `(industry/get-industry "0128")` confirmed
  `:maturity :implemented` with the corrected `:repo`/`:business-id`, and
  a sample of unrelated entries confirmed intact (not clobbered by this
  edit). No mojibake found in `registry.edn`.

Exact commit SHAs, raw test-output lines for both repos, and the final
`:implemented` count are recorded in the executing agent's final report
(this ADR intentionally does not duplicate the fast-drifting shared
`:implemented` counter's exact value at time of writing, since the fleet
runs at extremely high concurrency and the counter is continuously
advanced by unrelated concurrent agents; this promotion's own +1 was
independently confirmed correct and intact against a fresh post-merge
clone).

## Consequences

(+) `cloud-itonami-isic-0128` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
This closes out **Wave 3's last genuine implementation gap**.
(+) `kotoba-lang/industry` registry `"0128"` entry promoted to
`:maturity :implemented`; the shared fleet-wide `:implemented` count
advanced by this promotion's own +1.
(+) `spicecrop.facts`/`spicecrop.governor`'s conditional checks
(cultivation-license/quota-tracking/harvest-quota only for controlled
substance crop categories, entirely absent for non-controlled spice/
aromatic crop categories) reuse the exact `nil`-guard pattern the 012x
crop-growing lineage established, now demonstrated on a domain with a
genuine regulatory-compliance guardrail (a permanent, structural block on
license/clearance finalization) not present in the other 012x siblings.
(+) `spicecrop.governor/phase-auto-ops` plus the dedicated mock-advisor
self-trip regression test are a deliberate two-layer + regression-test
defense against a bug class this fleet has hit repeatedly, applied here
proactively rather than reactively.
(-) `spicecrop.advisor` provides `default-mock-proposals` (a fixed
happy-path fixture set) rather than a full `MockAdvisor`
StateGraph-driving implementation; `spicecrop.operation/run-operation`
takes an already-formed proposal plus an injected `governor-fn` rather
than internally invoking an advisor. This matches the established
012x-lineage shape and is a natural, contained future extension, not
required for this ADR's verification bar.

## References

- `cloud-itonami-isic-0129/` (module-shape mirror -- verified working
  reference for this coordination-only actor pattern, this actor's
  closest structural precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"0128"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan -- Wave
  3 production/robotics)
- ADR-2608400500 (`cloud-itonami-isic-0129`, the immediately-preceding
  structural sibling -- same coordination-only shape, same `nil`-guard
  crop-category-conditional pattern this actor also applies)
