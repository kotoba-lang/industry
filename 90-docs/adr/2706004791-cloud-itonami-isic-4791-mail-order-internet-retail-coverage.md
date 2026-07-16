# ADR-2706004791: cloud-itonami-isic-4791 — Mail-Order/Internet Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4791` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4791` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 4791 (Retail sale via mail order houses or via Internet) is a
Wave 2 (coordination/logistics/trade, ADR-2607121000) target. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "4791" ...}` entry's `:name` is exactly "Retail sale via mail
order houses or via Internet" — no mismatch found. The redundant
3-digit group entry `{:id "479" ...}` (`:repo nil`) was left untouched,
as instructed.

**Structurally distinct from every physical-retail sibling in this
fleet** (ISIC 4719 non-specialized stores, 4711 predominantly-food
community retail, 4721 specialized food retail, and the rest of the
47xx family): this class has **no physical storefront**. The template
was mirrored on `cloud-itonami-isic-4719`'s verified module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed directories,
append-only audit ledger) — but the domain, not just the naming, was
substantially adapted:

- The primary gate is a **seller/merchant-account verification** check
  (a registered, independently-verified e-commerce merchant account,
  additionally required to be linked to a payment processor), not a
  physical-store-verification check — there is no physical store to
  verify.
- This class carries a **payment-fraud/chargeback dimension** entirely
  absent from physical retail: the closed op allowlist NEVER includes
  any op that directly finalizes a fraud determination, a chargeback
  ruling, or a payment-dispute resolution. That territory is a HARD,
  permanent, un-overridable block; the only op that may touch it at all
  (`:flag-fraud-concern`, an OBSERVATION-only surfacing step) ALWAYS
  escalates to a human and can never become auto-commit-eligible at any
  rollout phase.

**Scope**: mail-order/e-commerce-retail OPERATIONS COORDINATION, NOT
direct fraud-determination or payment-dispute authority.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-order-record` — order/shipment/return data logging
- `:schedule-fulfillment-operation` — warehouse pick/pack/ship
  scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-fraud-concern` — surface a suspected-fraud/chargeback/
  payment-dispute concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out. CRITICAL: no op that directly finalizes
a fraud determination, a chargeback ruling, or a payment-dispute
resolution is EVER a member of this allowlist, by design.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Seller/merchant-account unverified** (E-COMMERCE-SPECIFIC PRIMARY
   GATE, replacing the physical-retail "store verification" check) —
   the target seller's record must exist AND be independently
   `:registered?`, `:verified?` AND `:payment-processor-linked?` before
   any proposal for it may commit or even escalate. A seller that is
   registered and verified but not yet linked to a payment processor
   (e.g. mid-onboarding) is treated exactly like an unregistered seller
   — HARD hold, not a lesser caution. Re-derived from the seller's own
   record every time, never from the proposal's own `:seller-id` claim.
2. **Vendor unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:vendor-id` that
   resolves to an independently `:registered?`/`:verified?` inventory
   vendor record. A missing vendor-id, or one that resolves to an
   unregistered/unverified vendor, is a HARD block (reused from the
   47xx template — inventory-supplier verification is not
   e-commerce-specific, but remains necessary since a mail-order
   retailer still procures inventory from vendors).
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** (PAYMENT-FRAUD/CHARGEBACK DIMENSION, the
   e-commerce-specific adaptation of the physical-retail "loss-
   prevention-enforcement" exclusion) — any proposal (regardless of op)
   outside the closed allowlist, or whose rationale/summary/citations/
   draft value touches directly finalizing a fraud determination, a
   chargeback ruling, or a payment-dispute resolution, is a permanent,
   un-overridable block. Evaluated **unconditionally** on every proposal
   via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own
   framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/resolution ACTION
   (e.g. "finalized the chargeback ruling", "resolved the payment
   dispute", "approved the chargeback"), never as a bare noun (bare
   "fraud", "chargeback" or "payment dispute") that could accidentally
   match inside this same namespace's own default mock-advisor text.
   This mattered concretely here: `mailorderops.advisor`'s own printed
   `:op` keyword for the legitimate concern-flagging proposal literally
   contains the substring "fraud" (`:flag-fraud-concern`), and its
   default rationale legitimately discusses "不正利用の疑い" (suspected
   fraudulent use) and "チャージバック通知" (chargeback notice) — a
   bare-noun term list would have self-tripped this actor's own core
   happy path on every single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done. A companion sanity test,
   `out-of-scope-injection-still-trips-scope-exclusion`, confirms the
   check is not accidentally a no-op by asserting the advisor's
   `:out-of-scope?` test hook (which appends finalization-action
   language) DOES trip `:scope-excluded`.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-fraud-concern` — always, regardless of confidence. Can never be
  promoted to auto-commit-eligible at any rollout phase.
- `:coordinate-supply-order` above a $1500 estimated-cost threshold —
  always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`mailorderops.phase`'s 0→3 rollout table independently agrees:
`:flag-fraud-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `fraud-concern-holds-when-not-enabled` /
`fraud-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`mailorderops.store` (MemStore, string-keyed seller AND vendor
directories — two distinct registries, not one; seller records carry a
third boolean dimension, `:payment-processor-linked?`, absent from the
physical-retail template's store records), `mailorderops.advisor`
(MailOrderRetailAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts fraud/chargeback-finalization-scope
content so the governor's scope scan can be exercised end to end),
`mailorderops.governor` (MailOrderRetailGovernor), `mailorderops.phase`
(0→3 rollout), `mailorderops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`mailorderops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4791" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "479" ...}` group entry):
  `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4791` / `cloud-itonami-G4791`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4791` /
  `cloud-itonami-isic-4791`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-4791` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`
  (`e0b7251099a7a4e74778f340c1d623bf434c1796`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 61 tests containing 181 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`mailorderops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-fraud/low-cost ops, always-escalating fraud-concern flag,
  always-escalating over-threshold supply order, and six HARD-hold
  scenarios: unregistered seller, unverified seller, seller
  registered+verified but NOT payment-processor-linked, unverified
  supply-order vendor, non-`:propose` effect, scope-excluded content)
  without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows;
  domain substantially adapted per the e-commerce-specific guidance
  above, not copied verbatim)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4791"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2680004719 (cloud-itonami-isic-4719 — the mirrored structural
  template)
