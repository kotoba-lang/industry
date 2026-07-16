# ADR-2700004799: cloud-itonami-isic-4799 — Non-Store Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4799` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4799` repo) to `:implemented` in
the registry.

## Context

ISIC Rev.5 4799 (Other retail sale not in stores, stalls or markets) is
a Wave 2 (coordination/logistics/trade, ADR-2607121000) target. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "4799" ...}` entry's `:name` is exactly "Other retail sale
not in stores, stalls or markets" — the catch-all non-store,
non-stall, non-online retail class (door-to-door selling,
vending-machine retail, direct-sales/party-plan home-demonstration
selling), distinct from the group-level `{:id "479" ...}` registry
entry ("Retail trade not in stores, stalls or markets", a separate,
redundant registry artifact, left untouched). No mismatch found. Target
repo confirmed 404 (fresh scaffold) before any work began.

**Scope**: non-store-retail OPERATIONS COORDINATION, NOT direct pricing
authority and NOT consumer-rights adjudication. Mirrored on the module
shape of sibling `cloud-itonami-isic-4719` ("Other retail sale in
non-specialized stores", already `:implemented` — read in full and used
as the structural reference: advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed dual directories, append-only audit ledger) but
substantially domain-adapted, not copied verbatim: this class has **no
fixed storefront to verify at all** — the analog to "store
verification" every fixed-location `47xx` sibling actor uses is
**seller/route/vending-machine-fleet registration**, since the
class-defining trait of ISIC 4799 is that the sale happens away from any
store, stall or market (at the customer's door, at a vending machine, or
at a home party-plan demonstration). Domain-adapted for non-store
retail: sale/order transaction logging, door-to-door-route/vending-
machine-fleet scheduling, inventory supply-order coordination with a
registered/verified vendor, and compliance-concern flagging (consumer
complaints, cooling-off/cancellation-right violations, route/territory
disputes) — never setting or overriding a unit price, and never
directly finalizing a waiver of a consumer's statutory cooling-off/
cancellation right.

This class carries a distinct **consumer-protection dimension** no
fixed-storefront sibling actor has had reason to add: door-to-door and
home-demonstration/party-plan selling are heavily regulated against
high-pressure sales tactics in most jurisdictions, typically via a
statutory cooling-off/cancellation-right period. This actor's closed op
allowlist deliberately excludes any op that could directly finalize a
waiver of that right — `:flag-compliance-concern` is a hard permanent
always-escalate op, never auto-commit-eligible, exactly the same
structural pattern sibling actors use for their own domain-specific
irreversible/high-stakes concern (e.g. ISIC 4719's loss-prevention-
concern flag), applied here to consumer cooling-off/cancellation-right
protection instead.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — sale/order data logging
- `:schedule-route-operation` — door-to-door route / vending-machine
  restock scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-compliance-concern` — surface a consumer-complaint/cooling-
  off-violation/route-dispute concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Seller unverified** — the target door-to-door route/vending-
   machine-fleet/party-plan seller's record must exist AND be
   independently `:registered?`/`:verified?` before any proposal for it
   may commit or even escalate. Re-derived from the seller's own record
   every time, never from the proposal's own `:seller-id` claim. This is
   this vertical's primary gate — the direct structural analog to every
   fixed-storefront `47xx` sibling's store-verification check, adapted
   to a class with no fixed store to verify.
2. **Vendor unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:vendor-id` that
   resolves to an independently `:registered?`/`:verified?` vendor
   record in the store. A missing vendor-id, or one that resolves to an
   unregistered/unverified vendor, is a HARD block — the same
   supply-chain counterparty-verification discipline sibling
   `cloud-itonami-isic-4719` established for its own vendor check,
   reapplied here.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a waiver of a consumer's statutory
   cooling-off/cancellation right, is a permanent, un-overridable block.
   Evaluated **unconditionally** on every proposal via a lower-cased
   substring scan of the proposal's own content (English + Japanese term
   list) — never trusting the advisor's own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, e.g.
   `cloud-itonami-isic-4719`'s loss-prevention-concern equivalent): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "finalized the cooling-off waiver", "have the customer sign
   away their cancellation right", "confirmed the customer waived
   cooling-off"), never as a bare noun (bare "cooling-off" or
   "cancellation right") that could accidentally match inside this same
   namespace's own default mock-advisor text. This mattered concretely
   here: `nonstoreops.advisor`'s own default rationale for the
   legitimate concern-flagging proposal legitimately discusses
   "クーリングオフ苦情" (cooling-off complaint) and "解約権に関する消費者懸念"
   (cancellation-right consumer concern) as bare nouns — a bare-noun
   term list would have self-tripped this actor's own core happy path on
   every single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-compliance-concern` — always, regardless of confidence. Never a
  member of any phase's `:auto` set, at any phase — a permanent
  structural fact reflecting this class's consumer-protection dimension,
  not a rollout milestone still to come.
- `:coordinate-supply-order` above a $1000 estimated-cost threshold —
  always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`nonstoreops.phase`'s 0→3 rollout table independently agrees:
`:flag-compliance-concern` is never a member of any phase's `:auto` set
— two layers, not one, enforce the same invariant (exercised directly by
`compliance-concern-holds-when-not-enabled` /
`compliance-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`nonstoreops.store` (MemStore, string-keyed seller AND vendor
directories — two distinct registries, not one; seller records carry an
optional `:channel` tag — `:door-to-door`/`:vending-machine`/
`:party-plan` — domain color only, not gated on by the governor),
`nonstoreops.advisor` (NonStoreRetailAdvisor, mock + a real-LLM seam,
plus an `:out-of-scope?` test hook that deliberately drafts
cooling-off-waiver-finalization-scope content so the governor's scope
scan can be exercised end to end), `nonstoreops.governor`
(NonStoreRetailGovernor), `nonstoreops.phase` (0→3 rollout),
`nonstoreops.operation` (the `langgraph-clj` StateGraph: intake → advise
→ govern → decide → commit | hold | request-approval), `nonstoreops.sim`
(demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4799" ...}` block only, not appended, not touching any other
  entry — including not touching the separate group-level `{:id "479"
  ...}` block): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4799` / `cloud-itonami-G4799`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4799` /
  `cloud-itonami-isic-4799`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling `47xx`
  pattern of `:robotics true` in `blueprint.edn` for retail-adjacent
  domains — here the robotics premise is autonomous vending-machine
  restocking and last-mile route delivery, not shelf/floor robotics).
- Actor repo `cloud-itonami/cloud-itonami-isic-4799` scaffolded (fresh —
  no prior repository existed, both the stale `gftdcojp` placeholder and
  the real `cloud-itonami` org target confirmed 404 before any work
  began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`nonstoreops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-consumer-rights/low-cost ops, always-escalating compliance-concern
  flag, always-escalating over-threshold supply order, and five
  HARD-hold scenarios: unregistered seller, unverified seller, unverified
  supply-order vendor, non-`:propose` effect, cooling-off-waiver-
  finalization-scope-excluded content) without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows;
  domain substantially re-derived, not copied verbatim, per this
  vertical's distinct seller/route/vending-fleet-registration gate and
  consumer cooling-off/cancellation-right scope exclusion)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4799"` entry (class-level; the separate `"479"` group-level entry
  intentionally untouched)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
