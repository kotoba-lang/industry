# ADR-2700004759: cloud-itonami-isic-4759 — Household Appliance, Furniture and Lighting Specialized-Store Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4759` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4759` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 4759 (Retail sale of electrical household appliances,
furniture, lighting equipment and similar household articles in
specialized stores) is a Wave 2 (coordination/logistics/trade,
ADR-2607121000) target. Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "4759" ...}` entry's `:name` was
found **truncated with a literal `"..."`** — "Retail sale of electrical
household appliances, furniture, ..." — the known ~10% pre-existing
seed-data bug. This is a genuine prefix-match of the assigned class name
("Retail sale of electrical household appliances, furniture, lighting
equipment and similar household articles in specialized stores"), so the
registry edit both de-truncates the name and promotes maturity in the
same exact-text block edit. The separate, redundant 3-digit group entry
`{:id "475" ...}` ("Retail sale of other household equipment in
specialized st...") was left untouched, per this fleet's standing
instruction not to touch or promote group-level entries.

**Scope**: household-appliance/furniture/lighting-specialty-retail
OPERATIONS COORDINATION, NOT direct warranty-claim-authorization or
delivery/installation-safety authority. Mirrored closely on the sibling
`cloud-itonami-isic-4752` (hardware, paints and glass specialized-store
retail)'s verified coordination-only module shape (advisor/governor/
phase/operation/store/sim, `langgraph-clj` StateGraph, independent
Governor, phase 0→3 rollout, string-keyed store AND vendor directories,
append-only audit ledger) — itself mirrored on `cloud-itonami-isic-4719`
— the closest already-`:implemented` structural precedent for a pure
coordination actor with a supply-chain vendor-verification check.
Domain-adapted for household-appliance/furniture/lighting specialty
storefronts: sales/inventory/warranty-registration transaction logging,
delivery-and-installation scheduling coordination, merchandise
supply-order coordination with a registered/verified vendor, and
warranty/defect-dispute-concern flagging — never setting or overriding a
shelf/unit price, never finalizing a warranty-claim decision (approving,
denying or settling a claim, authorizing a refund or replacement), and
never finalizing a delivery/installation-safety clearance (certifying a
gas/electrical hookup as safe, clearing an installation hazard as
resolved, signing off on an installation safety inspection). This
actor's two-fold exclusion (warranty-claim authority AND
installation-safety authority — large appliances routinely require
gas/electrical hookup at delivery, unlike 4752's single hazmat-handling
exclusion) is the reason it needs its own distinct scope-exclusion
vocabulary covering both categories rather than reusing 4752's
hazmat-handling term list verbatim.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/warranty-registration data logging
- `:schedule-delivery-operation` — delivery/installation scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-warranty-concern` — surface a defect/warranty-dispute concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out. **No op in this allowlist directly
finalizes a warranty-claim decision or a delivery/installation-safety
clearance** — those actions are structurally excluded from the actor's
vocabulary, not merely gated by a phase or an escalation rule.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Store unverified** — the target store's record (business
   registration/retail license) must exist in the store AND be
   independently `:registered?`/`:verified?` before any proposal for it
   may commit or even escalate. Re-derived from the store's own store
   record every time, never from the proposal's own `:store-id` claim.
2. **Vendor unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:vendor-id` that resolves
   to an independently `:registered?`/`:verified?` vendor record in the
   store. A missing vendor-id, or one that resolves to an unregistered/
   unverified vendor, is a HARD block — same supply-chain
   counterparty-verification discipline as siblings 4719/4752.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a warranty-claim decision (approving,
   denying or settling a claim, authorizing a warranty refund or
   replacement, closing a claim as resolved) OR directly finalizing a
   delivery/installation-safety clearance (certifying a gas/electrical
   hookup as safe, clearing an installation hazard as resolved, signing
   off on an installation safety inspection, declaring an installation
   code-compliant), is a permanent, un-overridable block. Evaluated
   **unconditionally** on every proposal via a lower-cased substring scan
   of the proposal's own content (English + Japanese term list) — never
   trusting the advisor's own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, and pre-empted here
   by design): every scope-excluded term is phrased as the finalization/
   execution ACTION (e.g. "approved the warranty claim", "certified the
   gas hookup as safe"), never as a bare noun (bare "warranty", "claim",
   "installation" or "gas") that could accidentally match inside this
   same namespace's own default mock-advisor text. This mattered
   concretely here: `applianceops.advisor`'s own printed `:op` keyword
   for the legitimate concern-flagging proposal literally contains the
   substring "warranty" (`:flag-warranty-concern`), and its default
   rationale legitimately discusses "欠陥/保証紛争懸念" (defect/warranty-
   dispute concern) — a bare-noun term list would have self-tripped this
   actor's own core happy path on every single run. A dedicated
   regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-warranty-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $2000 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`applianceops.phase`'s 0→3 rollout table independently agrees:
`:flag-warranty-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `warranty-concern-holds-when-not-enabled` /
`warranty-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate` before
the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`applianceops.store` (MemStore, string-keyed store AND vendor
directories — two distinct registries, not one), `applianceops.advisor`
(ApplianceRetailAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts warranty-claim/installation-safety-
clearance-finalization-scope content so the governor's scope scan can be
exercised end to end), `applianceops.governor` (ApplianceRetailGovernor),
`applianceops.phase` (0→3 rollout), `applianceops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `applianceops.sim` (demo driver, `clojure
-M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4759" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "475" ...}` group entry which was
  left untouched): `:name` de-truncated from "Retail sale of electrical
  household appliances, furniture, ..." to "Retail sale of electrical
  household appliances, furniture, lighting equipment and similar
  household articles in specialized stores", `:repo`/`:business-id`
  de-placeholdered from `https://github.com/gftdcojp/cloud-itonami-G4759`
  / `cloud-itonami-G4759` to `https://github.com/cloud-itonami/cloud-itonami-isic-4759`
  / `cloud-itonami-isic-4759`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling ISIC 4719/4752
  pattern of `:robotics true` in `blueprint.edn` for retail-adjacent
  domains).
- Actor repo `cloud-itonami/cloud-itonami-isic-4759` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main` (commit `e3dca3bd869b685447e91ed49e4a33eed86d3657`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.

## References

- `cloud-itonami-isic-4752/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows,
  including the vendor-verification supply-chain check)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4759"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2691004752 (immediately-preceding structural sibling: ISIC 4752
  hardware/paint/glass specialized-store retail operations coordination)
