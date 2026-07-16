# ADR-2700004761: cloud-itonami-isic-4761 — Books/Newspapers/Stationery-Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4761` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4761` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 4761 (Retail sale of books, newspapers and stationary in
specialized stores) is a Wave 2 (coordination/logistics/trade,
ADR-2607121000) target. Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "4761" ...}` entry's `:name` was
found genuinely truncated with a literal `"..."` (the known ~10%
pre-existing seed-data truncation bug) — `"Retail sale of books,
newspapers and stationary in speciali..."`, a verified prefix-match of
"Retail sale of books, newspapers and stationary in specialized stores",
de-truncated in place as part of this ADR's registry edit. No class
mismatch found. The registry also carries a separate, redundant 3-digit
group entry `{:id "476" ...}` ("Retail sale of cultural and recreation
goods in specialized stores") — not touched, only the `"4761"`
class-level block was edited.

**Scope**: bookstore/newsstand/stationery-retail OPERATIONS
COORDINATION, NOT direct content-censorship authority and NOT direct
returns-authorization authority. Mirrored closely on the sibling
`cloud-itonami-isic-4751` (retail sale of textiles in specialized
stores)'s verified coordination-only module shape (advisor/governor/
phase/operation/store/sim, `langgraph-clj` StateGraph, independent
Governor, phase 0→3 rollout, string-keyed store AND vendor directories,
append-only audit ledger) — the closest already-`:implemented`
structural precedent for a pure coordination actor in the 47xx retail
family. Domain-adapted for bookstore/newsstand/stationery storefronts:
sales/inventory/return transaction logging, floor-staff scheduling,
book/newspaper/stationery supply-order coordination with a
registered/verified vendor (publisher/distributor), and inventory-concern
flagging (damaged stock, a mis-shipment, a packing-slip quantity
shortage) — never setting or overriding a shelf/unit price, never
directly finalizing a return/refund (issuing a refund/replacement,
voiding a sale, charging back a vendor, revoking or terminating a
vendor's registration/contract), and never directly finalizing a
content-inclusion/exclusion editorial decision (banning, pulling, or
prohibiting a title from sale) — a domain-specific fourth exclusion this
vertical adds beyond the sibling retail actors' return/refund-only scope
exclusion, because a bookstore-operations coordinator must never be able
to claim it decided which titles a store may or may not carry.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/return data logging
- `:schedule-staffing-operation` — floor-staff scheduling proposal
- `:coordinate-supply-order` — book/newspaper/stationery procurement proposal (publisher/distributor orders)
- `:flag-inventory-concern` — surface a damaged-stock/mis-shipment concern — **ALWAYS escalates**

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
   proposal's own drafted `:value` must name a `:vendor-id` that
   resolves to an independently `:registered?`/`:verified?` vendor
   record in the store. A missing vendor-id, or one that resolves to an
   unregistered/unverified vendor, is a HARD block — a supply-chain
   counterparty-verification gate shared with sibling 47xx retail
   actors (e.g. ISIC 4719, 4751), reapplied here to
   publishers/distributors.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a return/refund (issuing a refund or
   replacement, voiding a sale, charging back a vendor, revoking or
   terminating a vendor's registration/contract) OR directly finalizing
   a content-inclusion/exclusion editorial decision (banning, pulling,
   or prohibiting a title from sale), is a permanent, un-overridable
   block. Evaluated **unconditionally** on every proposal via a
   lower-cased substring scan of the proposal's own content (English +
   Japanese term list) — never trusting the advisor's own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, most recently ISIC
   4751): every scope-excluded term is phrased as the
   finalization/execution ACTION (e.g. "issue the refund", "ban the
   title from the store"), never as a bare noun (bare "refund",
   "return", "title" or "inventory") that could accidentally match
   inside this same namespace's own default mock-advisor text. This
   mattered concretely here: `bookstoreops.advisor`'s own printed `:op`
   keyword for the legitimate concern-flagging proposal literally
   contains the substring "inventory-concern"
   (`:flag-inventory-concern`), and its default rationale legitimately
   discusses damaged-stock/mis-shipment/quantity-mismatch observations —
   a bare-noun term list (e.g. a literal "inventory" or "title" entry)
   would have self-tripped this actor's own core happy path on every
   single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-inventory-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above an $800 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`bookstoreops.phase`'s 0→3 rollout table independently agrees:
`:flag-inventory-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant
(exercised directly by `inventory-concern-holds-when-not-enabled` /
`inventory-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`bookstoreops.store` (MemStore, string-keyed store AND vendor
directories — two distinct registries, not one), `bookstoreops.advisor`
(BookstoreRetailAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts return/refund-finalization-scope
content so the governor's scope scan can be exercised end to end),
`bookstoreops.governor` (BookstoreRetailGovernor), `bookstoreops.phase`
(0→3 rollout), `bookstoreops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`bookstoreops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4761" ...}` block only, not appended, not touching any other
  entry, and not touching the separate `{:id "476" ...}` group entry):
  `:name` de-truncated to "Retail sale of books, newspapers and
  stationary in specialized stores"; `:repo`/`:business-id`
  de-placeholdered from `https://github.com/gftdcojp/cloud-itonami-G4761`
  / `cloud-itonami-G4761` to
  `https://github.com/cloud-itonami/cloud-itonami-isic-4761` /
  `cloud-itonami-isic-4761`, `:maturity` `:spec`→`:implemented`.
  `:required-technologies`/`:operating-states` left as-is (already
  matched the sibling 47xx retail-actor pattern:
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :retail]`,
  `[:intake :stock :sell :reconcile :reorder :audit]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-4761` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 58 tests containing 170 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`bookstoreops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-finalization/low-cost ops, always-escalating inventory-concern
  flag, always-escalating over-threshold supply order, and five
  HARD-hold scenarios: unregistered store, unverified store, unverified
  supply-order vendor, non-`:propose` effect, scope-excluded content)
  without error.

## References

- `cloud-itonami-isic-4751/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows,
  and the immediately-preceding Wave 2 47xx sibling that most recently
  hit and fixed the same scope-exclusion self-trip failure mode)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4761"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2700004751 (`cloud-itonami-isic-4751`, closest structural sibling)
