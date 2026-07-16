# ADR-2710004789: cloud-itonami-isic-4789 — Stall/Market Retail of Other Goods Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4789` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4789` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 4789 (Retail sale via stalls and markets of other goods) is a
Wave 2 (coordination/logistics/trade, ADR-2607121000) target. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "4789" ...}` entry's `:name` is exactly "Retail sale via
stalls and markets of other goods" — the catch-all market-stall class
for general/other goods not covered by sibling ISIC 4781 (food,
beverages and tobacco stalls, already `:implemented` per
ADR-2700004781) or ISIC 4782 (textiles, clothing and footwear stalls,
already `:implemented`): household goods, hardware, electronics
accessories, books, toys, and similar merchandise sold from a market
stall or pitch. No class mismatch found. The redundant 3-digit group
entry `{:id "478" ...}` was left untouched.

**Scope**: market-stall OPERATIONS COORDINATION, NOT direct
permit-issuance authority or dispute-resolution authority. Mirrored
closely on the sibling `cloud-itonami-isic-4719` (non-specialized-store
retail)'s verified coordination-only module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed directory,
append-only audit ledger) — the reference this build was explicitly
briefed to mirror. Domain-adapted for mobile/temporary market-stall
vendors selling general/other goods: sales/inventory transaction
logging, stall placement/staffing scheduling, inventory supply-order
coordination, and compliance-concern flagging (permit legitimacy,
suspected counterfeit goods, product-quality/safety observations) —
never directly issuing/finalizing a market-stall permit, and never
directly resolving a vendor/customer dispute.

Unlike ISIC 4719's sibling actor, this vertical deliberately does NOT
add a second supply-chain vendor-verification entity for
`:coordinate-supply-order` — the single stall/market-permit-verification
check gates every op in the closed allowlist, including supply orders.
This was a deliberate scope decision for the catch-all "other goods"
class (no flagship new counterparty-verification check), not an
oversight.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale data logging
- `:schedule-stall-operation` — stall placement/staffing scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-compliance-concern` — surface a permit/counterfeit/quality concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Stall unverified** — the target stall's market/council permit
   registration must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the stall's own record every time,
   never from the proposal's own `:stall-id` claim. This single check
   gates EVERY op in the closed allowlist, including
   `:coordinate-supply-order`.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) whose
   rationale/summary/citations/draft value touches directly
   issuing/finalizing a market-stall permit, or directly resolving a
   vendor/customer dispute, is a permanent, un-overridable block.
   Evaluated **unconditionally** on every proposal via a lower-cased
   substring scan of the proposal's own content (English + Japanese term
   list) — never trusting the advisor's own framing.
4. **Op not allowed** — an op outside the closed four-op allowlist
   (folded into the same implementation as check 3, listed separately
   here as a structurally distinct failure mode).

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "issued the permit", "resolved the dispute", "granted the
   permit", "adjudicated the dispute"), never as a bare noun (bare
   "permit", "license" or "dispute") that could accidentally match
   inside this same namespace's own default mock-advisor text. This
   mattered concretely here: `stallmarketops.advisor`'s own default
   `:flag-compliance-concern` rationale legitimately discusses "出店許可の
   正当性疑義" (permit-legitimacy doubt) and "模倣品(カウンターフィット)疑い"
   (suspected counterfeit goods) — a bare-noun term list (bare "許可" /
   "permit") would have self-tripped this actor's own core happy path on
   every single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-compliance-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above an $800 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`stallmarketops.phase`'s 0→3 rollout table independently agrees:
`:flag-compliance-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant
(exercised directly by `compliance-concern-holds-when-not-enabled` /
`compliance-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`stallmarketops.store` (MemStore, string-keyed `stalls` directory only —
no separate vendor registry, see scope note above),
`stallmarketops.advisor` (StallMarketAdvisor, mock + a real-LLM seam,
plus an `:out-of-scope?` test hook that deliberately drafts
permit-issuance/dispute-resolution-finalization-scope content so the
governor's scope scan can be exercised end to end),
`stallmarketops.governor` (StallMarketGovernor), `stallmarketops.phase`
(0→3 rollout), `stallmarketops.operation` (the `langgraph-clj`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval), `stallmarketops.sim` (demo driver, `clojure -M:run`).

**Governor-keyword collision caught and fixed before this build was
considered done**: the initially-chosen `blueprint.edn`
`:itonami.blueprint/governor` value `:stall-market-governor` was already
claimed by sibling `cloud-itonami-isic-4782` (Retail Sale via Stalls and
Markets of Textiles, Clothing and Footwear). Renamed to
`:stall-market-other-goods-governor` — distinct from 4719's
`:merchandise-retail-governor`, 4781's `:market-stall-retail-governor`,
and 4782's `:stall-market-governor` — verified via
`gh api search/code` returning zero hits before adoption, and by
directly reading both sibling repos' live `blueprint.edn`.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4789" ...}` block only, not appended, not touching any other
  entry, not touching the redundant `{:id "478" ...}` group entry):
  `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4789` /
  `cloud-itonami-G4789` to
  `https://github.com/cloud-itonami/cloud-itonami-isic-4789` /
  `cloud-itonami-isic-4789`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling stall/market
  47xx pattern).
- Actor repo `cloud-itonami/cloud-itonami-isic-4789` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 49 tests containing 142 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`stallmarketops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating compliance-concern flag,
  always-escalating over-threshold supply order, and four HARD-hold
  scenarios: unregistered stall, permit-unverified stall, non-`:propose`
  effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — the reference this
  build was explicitly briefed to mirror, verified working)
- `cloud-itonami-isic-4781/` (ADR-2700004781 — closest same-family
  sibling, same market-stall vertical, same closed four-op naming
  convention independently converged on: `:log-sales-record`,
  `:schedule-stall-operation`, `:coordinate-supply-order`,
  `:flag-compliance-concern`; read for governor-keyword collision check,
  not structurally mirrored beyond the shared op-naming convention)
- `cloud-itonami-isic-4782/` (governor-keyword collision source —
  `:stall-market-governor` already claimed; read for collision check
  only)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4789"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
