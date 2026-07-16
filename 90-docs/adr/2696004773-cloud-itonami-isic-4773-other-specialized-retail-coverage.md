# ADR-2696004773: cloud-itonami-isic-4773 — Other-Specialized-Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4773` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4773` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 4773 (Other retail sale of new goods in specialized stores) is
a Wave 2 (coordination/logistics/trade, ADR-2607121000) target. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "4773" ...}` entry's `:name` is exactly "Other retail sale of
new goods in specialized stores" — fully spelled out, no pre-existing
truncation-bug de-truncation needed. This is a catch-all class for
single-category specialty shops not covered by other 477x classes (e.g.
books/stationery, sporting goods, toys and games, hardware/paint/glass,
furniture/lighting/household articles, electrical appliances, jewelry/
watches, photographic/optical equipment, flowers/plants/pets), distinct
from ISIC 4771 (textiles/clothing/footwear/leather), 4772 (pharmaceutical/
medical/cosmetic), 4774 (second-hand goods) and the redundant 3-digit
group entry `{:id "477" ...}` (a separate registry artifact, deliberately
left untouched — only the `"4773"` class-level block was edited).

**Scope**: specialized-retail OPERATIONS COORDINATION, NOT direct
pricing-authority or quality-dispute-resolution control. Mirrored closely
on the sibling `cloud-itonami-isic-4719` (other retail sale in
non-specialized stores)'s verified coordination-only module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed store AND vendor
directories, append-only audit ledger) — the closest already-`:implemented`
structural precedent for this coordination-only pattern, including its
own flagship vendor-verification gate. Domain-adapted for specialized
single-category storefronts: transaction/inventory/return sales-record
logging, floor-staff scheduling, specialty-merchandise supply-order
coordination with a registered/verified vendor, and quality-concern
flagging (defective goods, mis-shipments, product-safety observations) —
never setting or overriding a shelf/unit price, and never directly
finalizing a quality-dispute resolution (a refund decision, a replacement
authorization, or a liability determination).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/return data logging
- `:schedule-staffing-operation` — floor-staff scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-quality-concern` — surface a defective-goods/mis-shipment concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Store unverified** — the target store's record (business
   registration) must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the store's own store record every
   time, never from the proposal's own `:store-id` claim.
2. **Vendor unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:vendor-id` that resolves
   to an independently `:registered?`/`:verified?` vendor record in the
   store. A missing vendor-id, or one that resolves to an unregistered/
   unverified vendor, is a HARD block — the same supply-chain
   counterparty-verification gate ISIC 4719 pioneered in this fleet,
   reapplied here.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a quality-dispute resolution (a refund
   decision, a replacement authorization, a liability determination, or
   otherwise closing a customer's complaint unilaterally), is a
   permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the advisor's
   own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, most recently ISIC
   4719): every scope-excluded term is phrased as the finalization/
   execution ACTION (e.g. "finalize the refund decision", "close the
   dispute unilaterally", "authorize the replacement unilaterally"),
   never as a bare noun (bare "refund", "dispute", "defect" or
   "complaint") that could accidentally match inside this same
   namespace's own default mock-advisor text. This mattered concretely
   here: `specialtyretailops.advisor`'s own printed `:op` keyword for the
   legitimate concern-flagging proposal literally contains the substring
   "quality" (`:flag-quality-concern`), and its default rationale
   legitimately discusses defective goods/mis-shipments/product-safety
   observations — a bare-noun term list would have self-tripped this
   actor's own core happy path on every single run. A dedicated
   regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-quality-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $1500 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`specialtyretailops.phase`'s 0→3 rollout table independently agrees:
`:flag-quality-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `quality-concern-holds-when-not-enabled` /
`quality-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate` before
the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`specialtyretailops.store` (MemStore, string-keyed store AND vendor
directories — two distinct registries, not one), `specialtyretailops.advisor`
(SpecialtyRetailAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts quality-dispute-resolution-
finalization-scope content so the governor's scope scan can be exercised
end to end), `specialtyretailops.governor` (SpecialtyRetailGovernor),
`specialtyretailops.phase` (0→3 rollout), `specialtyretailops.operation`
(the `langgraph-clj` StateGraph: intake → advise → govern → decide →
commit | hold | request-approval), `specialtyretailops.sim` (demo driver,
`clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4773" ...}` block only, not appended, not touching any other
  entry, including the separate redundant `{:id "477" ...}` group entry):
  `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4773` / `cloud-itonami-G4773`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4773` /
  `cloud-itonami-isic-4773`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling ISIC 4719/4721
  pattern of `:robotics true` in `blueprint.edn` for retail-adjacent
  domains).
- Actor repo `cloud-itonami/cloud-itonami-isic-4773` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`specialtyretailops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-dispute/low-cost ops, always-escalating quality-concern flag,
  always-escalating over-threshold supply order, and five HARD-hold
  scenarios: unregistered store, unverified store, unverified
  supply-order vendor, non-`:propose` effect, scope-excluded content)
  without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the coordination-only actor pattern with a vendor-
  verification gate, this actor's closest structural precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4773"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2680004719 (`cloud-itonami-isic-4719`, the immediately-preceding
  structural sibling — same coordination-only shape, same vendor-
  verification gate, same scope-exclusion self-trip precedent this actor
  also applies)
