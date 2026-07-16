# ADR-2680004719: cloud-itonami-isic-4719 — Non-Specialized-Store Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4719` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4719` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 4719 (Other retail sale in non-specialized stores) is a Wave 2
(coordination/logistics/trade, ADR-2607121000) target — Wave 3 and Wave 4
class-level scope having already reached completion in this fleet, Wave 2
is the next phase of the reverse-toposort rollout plan. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "4719" ...}` entry's `:name` is exactly "Other retail sale in
non-specialized stores" — department stores and general-merchandise
stores without predominant food sales, distinct from sibling ISIC 4711
("Community Retail Operations", predominantly-food, already
`:implemented`) and distinct from the specialized single-category 47xx
stores (4721 food, 4741 computers, 4772 pharmacy, 4774 second-hand, etc).
No mismatch found.

**Scope**: general-merchandise-retail OPERATIONS COORDINATION, NOT direct
pricing-authority or loss-prevention-enforcement control. Mirrored
closely on the sibling `cloud-itonami-isic-5610` (restaurants and mobile
food service)'s verified coordination-only module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed store directory,
append-only audit ledger) — the closest already-`:implemented` structural
precedent for a pure coordination actor (as opposed to ISIC 4711's own
dual-actuation sale-post/reorder-commit shape, which this actor
deliberately does NOT replicate: every op here is `:effect :propose`
only, never a direct sale/inventory actuation). Domain-adapted for
general-merchandise storefronts: transaction/inventory/return sales-record
logging, floor-staff scheduling, merchandise supply-order coordination
with a registered/verified vendor, and loss-prevention-concern flagging
(shoplifting, inventory shrinkage, product-safety observations) — never
setting or overriding a shelf/unit price, and never finalizing a
loss-prevention-enforcement action (detention, search, arrest,
confiscation).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — transaction/inventory/return data logging
- `:schedule-staffing-operation` — floor-staff scheduling proposal
- `:coordinate-supply-order` — merchandise procurement proposal
- `:flag-loss-prevention-concern` — surface a shoplifting/inventory-shrinkage/product-safety concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Store unverified** — the target store's record (business
   registration) must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the store's own store record every
   time, never from the proposal's own `:store-id` claim.
2. **Vendor unverified** (FLAGSHIP NEW, grep-verified absent fleet-wide as
   a check name) — for `:coordinate-supply-order` ONLY, the proposal's
   own drafted `:value` must name a `:vendor-id` that resolves to an
   independently `:registered?`/`:verified?` vendor record in the store.
   A missing vendor-id, or one that resolves to an unregistered/
   unverified vendor, is a HARD block. This is the flagship genuinely new
   check this vertical adds — a supply-chain counterparty-verification
   gate no sibling 47xx actor (4711/4721/4772/4774) has had reason to
   add, since none of them models a distinct vendor/counterparty entity
   separate from the store itself.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a loss-prevention-enforcement action
   (detention, search, arrest, confiscation of a suspect's belongings),
   is a permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the advisor's
   own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "detain the suspect", "conduct a search of", "make an arrest"),
   never as a bare noun (bare "shoplifting", "detention", "search" or
   "loss prevention") that could accidentally match inside this same
   namespace's own default mock-advisor text. This mattered concretely
   here: `merchandiseops.advisor`'s own printed `:op` keyword for the
   legitimate concern-flagging proposal literally contains the substring
   "loss-prevention" (`:flag-loss-prevention-concern`), and its default
   rationale legitimately discusses "万引き疑い" (suspected shoplifting) —
   a bare-noun term list would have self-tripped this actor's own core
   happy path on every single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-loss-prevention-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $1000 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`merchandiseops.phase`'s 0→3 rollout table independently agrees:
`:flag-loss-prevention-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant
(exercised directly by `loss-prevention-concern-holds-when-not-enabled` /
`loss-prevention-concern-escalates-when-enabled`). The high-cost
supply-order escalate gate requires no extra phase-layer code: the
governor's own `high-stakes?` already turns the base disposition into
`:escalate` before the phase gate runs, so phase 3's `:auto` membership
for `:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`merchandiseops.store` (MemStore, string-keyed store AND vendor
directories — two distinct registries, not one), `merchandiseops.advisor`
(MerchandiseRetailAdvisor, mock + a real-LLM seam, plus an
`:out-of-scope?` test hook that deliberately drafts loss-prevention-
enforcement-finalization-scope content so the governor's scope scan can
be exercised end to end), `merchandiseops.governor`
(MerchandiseRetailGovernor), `merchandiseops.phase` (0→3 rollout),
`merchandiseops.operation` (the `langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | request-approval),
`merchandiseops.sim` (demo driver, `clojure -M:run`).

An implementation bug was caught and fixed before this build was
considered done: `vendor-unverified-violations` initially destructured
`:op` from the governor's `request` argument rather than the `proposal`
argument, so the check silently never fired when `request` didn't carry
an `:op` key (as in every unit test calling `governor/check` directly).
Fixed to read `(:op proposal)`, matching the same discipline
`effect-not-propose-violations`/`scope-exclusion-violations` already use
— all governor-owned proposal-shape checks read from the proposal, not
the request.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4719" ...}` block only, not appended, not touching any other
  entry): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4719` / `cloud-itonami-G4719`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4719` /
  `cloud-itonami-isic-4719`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling ISIC 5610/4721
  pattern of `:robotics true` in `blueprint.edn` for retail-adjacent
  domains).
- Actor repo `cloud-itonami/cloud-itonami-isic-4719` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:dev:test`), independently re-verified against a fresh
  clone with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`merchandiseops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-enforcement/low-cost ops, always-escalating loss-prevention-concern
  flag, always-escalating over-threshold supply order, and five HARD-hold
  scenarios: unregistered store, unverified store, unverified supply-order
  vendor, non-`:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-5610/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows,
  as opposed to ISIC 4711's own dual-actuation shape)
- `cloud-itonami-isic-4711/` (closest ISIC sibling, already `:implemented`
  — read in full but NOT mirrored structurally, since its sale-post/
  reorder-commit actuation model does not match this actor's
  coordination-only scope)
- `cloud-itonami-isic-4721/` (ADR-2670004721 — the immediately-preceding
  Wave 2 47xx sibling, same coordination-only shape and the same
  scope-exclusion self-trip precedent this actor also applies)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4719"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
