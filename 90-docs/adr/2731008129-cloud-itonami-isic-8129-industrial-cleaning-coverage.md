# ADR-2731008129: cloud-itonami-isic-8129 — Other Building and Industrial Cleaning Activities Operations Coordination

## Status

Accepted. `cloud-itonami-isic-8129` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-N8129` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 8129 (Other building and industrial cleaning activities) is a
Wave 2 (coordination/logistics/trade, ADR-2607121000) target — the final
class dispatched in this wave's last batch of 4-digit gaps. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began: the live `{:id "8129" ...}` entry's `:name` was
found NOT truncated — `"Other building and industrial cleaning
activities"` — a genuine exact/prefix match of the assigned class name,
so no de-truncation was needed in this build's registry edit (unlike
several sibling Wave 2 entries that did carry the known ~10%
pre-existing seed-data truncation bug). The separate, redundant 3-digit
group entry `{:id "812" ...}` ("Cleaning activities") was left
untouched, per this fleet's standing instruction not to touch or promote
group-level entries.

**Scope**: industrial/building-cleaning-services OPERATIONS
COORDINATION, NOT direct hazmat-handling-safety authority or
confined-space-entry authority. ISIC 8129 covers industrial-scale
cleaning that often involves hazardous cleaning chemicals, confined-space
entry, and specialized equipment (pressure washing, chemical degreasing)
beyond routine janitorial work — a materially different hazard profile
from a general office-cleaning actor, and the reason this class needs its
own distinct scope-exclusion vocabulary (confined-space-entry
authorization, not just hazmat-handling-safety clearance) rather than
reusing a plain janitorial-services term list. Mirrored closely on the
sibling `cloud-itonami-isic-4752` (hardware/paint/glass specialized-store
retail)'s verified coordination-only module shape (advisor/governor/
phase/operation/store/sim, `langgraph-clj` StateGraph, independent
Governor, phase 0→3 rollout, string-keyed site AND vendor directories,
append-only audit ledger) — the closest already-`:implemented` structural
precedent for a pure coordination actor with both a hazmat-handling-
safety scope-exclusion check and a supply-chain vendor-verification
check. Domain-adapted for industrial-cleaning contractors: job/site
cleaning-completion data logging, cleaning-crew/equipment dispatch
scheduling, cleaning-chemical/equipment supply-order coordination with a
registered/verified vendor, and hazmat/confined-space/chemical-exposure
safety-concern flagging — never setting or overriding a service price,
never finalizing a hazmat-handling-safety clearance (certifying chemical-
storage/degreasing-area compliance, clearing a chemical spill as
contained, signing off on a handling permit), and never finalizing a
confined-space-entry authorization (authorizing, granting or issuing
confined-space entry, declaring confined-space ventilation compliant).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — job/site cleaning-completion data logging
- `:schedule-service-operation` — cleaning-crew/equipment dispatch scheduling proposal
- `:coordinate-supply-order` — cleaning-chemical/equipment procurement proposal
- `:flag-safety-concern` — surface a hazmat/confined-space/chemical-exposure concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out. **No op in this allowlist directly
finalizes a hazmat-handling-safety clearance or a confined-space-entry
authorization** — those actions are structurally excluded from the
actor's vocabulary, not merely gated by a phase or an escalation rule.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Site unverified** — the target client site's contractor/site-access
   record must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the site's own site record every time,
   never from the proposal's own `:site-id` claim.
2. **Vendor unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:vendor-id` that resolves
   to an independently `:registered?`/`:verified?` vendor record in the
   store. A missing vendor-id, or one that resolves to an unregistered/
   unverified vendor, is a HARD block — same supply-chain
   counterparty-verification discipline as sibling ISIC 4752.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a hazmat-handling-safety clearance
   (certifying a chemical-storage/degreasing area as compliant, clearing
   a chemical spill as contained, signing off on a hazmat handling
   permit) OR a confined-space-entry authorization (authorizing,
   granting or issuing confined-space entry, declaring confined-space
   ventilation compliant), is a permanent, un-overridable block.
   Evaluated **unconditionally** on every proposal via a lower-cased
   substring scan of the proposal's own content (English + Japanese term
   list) — never trusting the advisor's own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, and pre-empted here
   by design): every scope-excluded term is phrased as the finalization/
   execution ACTION (e.g. "certified the chemical storage area as
   compliant", "authorized the confined-space entry", "issued the
   confined-space entry permit"), never as a bare noun (bare "hazmat",
   "confined space" or "chemical") that could accidentally match inside
   this same namespace's own default mock-advisor text. This mattered
   concretely here: `industrialcleaningops.advisor`'s own printed `:op`
   keyword for the legitimate concern-flagging proposal literally
   contains the substring "safety" (`:flag-safety-concern`), and its
   default rationale legitimately discusses 薬剤曝露・密閉空間・換気
   (chemical exposure / confined space / ventilation) — a bare-noun term
   list would have self-tripped this actor's own core happy path on
   every single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done. The chosen governor keyword,
   `:industrial-cleaning-ops-governor`, was independently confirmed
   unique across the `cloud-itonami` org (`gh api search/code`, zero
   hits) before finalizing, including against the concurrently-built
   sibling `cloud-itonami-isic-8110` (combined facilities support) in
   this same dispatch batch.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $1000 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`industrialcleaningops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `safety-concern-holds-when-not-enabled` /
`safety-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate` before
the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`industrialcleaningops.store` (MemStore, string-keyed site AND vendor
directories — two distinct registries, not one), `industrialcleaningops.advisor`
(IndustrialCleaningOpsAdvisor, mock + a real-LLM seam, plus an
`:out-of-scope?` test hook that deliberately drafts hazmat-handling-
safety-clearance/confined-space-entry-authorization-finalization-scope
content so the governor's scope scan can be exercised end to end),
`industrialcleaningops.governor` (IndustrialCleaningOpsGovernor),
`industrialcleaningops.phase` (0→3 rollout), `industrialcleaningops.operation`
(the `langgraph-clj` StateGraph: intake → advise → govern → decide →
commit | hold | request-approval), `industrialcleaningops.sim` (demo
driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "8129" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "812" ...}` group entry which was
  left untouched): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-N8129` / `cloud-itonami-N8129`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-8129` /
  `cloud-itonami-isic-8129`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :labor]`, matching the registry's own
  pre-existing entry for this class).
- Actor repo `cloud-itonami/cloud-itonami-isic-8129` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.

## References

- `cloud-itonami-isic-4752/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows,
  including both the hazmat scope-exclusion check and the
  vendor-verification supply-chain check)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"8129"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2691004752 (module-shape structural sibling: ISIC 4752 hardware/
  paint/glass specialized-store retail operations coordination)
