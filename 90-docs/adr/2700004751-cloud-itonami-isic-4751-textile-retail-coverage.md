# ADR-2700004751: cloud-itonami-isic-4751 — Textile-Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4751` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4751` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 4751 (Retail sale of textiles in specialized stores) is a
Wave 2 (coordination/logistics/trade, ADR-2607121000) target — Wave 3
and Wave 4 class-level scope having already reached completion in this
fleet, Wave 2 is the next phase of the reverse-toposort rollout plan.
Identity independently verified against a fresh clone of
`kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "4751" ...}` entry's `:name` is
exactly "Retail sale of textiles in specialized stores" — fabric/notions
storefronts selling bolts of woven/knit fabric, thread, trims, patterns
and sewing notions, distinct from ISIC 4771-family apparel retail (which
sells finished garments, not yardage). No mismatch found. The registry
also carries a separate, redundant 3-digit group entry `{:id "475" ...}`
— not touched, only the `"4751"` class-level block was edited.

**Scope**: fabric/notions-retail OPERATIONS COORDINATION, NOT direct
pricing-authority or loss-prevention-enforcement control. Mirrored
closely on the sibling `cloud-itonami-isic-4719` (other retail sale in
non-specialized stores)'s verified coordination-only module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed store AND vendor
directories, append-only audit ledger) — the closest already-
`:implemented` structural precedent for a pure coordination actor in the
47xx retail family. Domain-adapted for fabric/notions storefronts:
sales/inventory/cut-yardage transaction logging, floor-staff scheduling,
textile supply-order coordination with a registered/verified vendor
(fabric mill/wholesaler), and quality-concern flagging (a defective
bolt, mislabeled fiber content, or a dye-lot mismatch) — never setting or
overriding a shelf/unit price, and never directly finalizing a
quality-dispute resolution (issuing a refund/replacement, voiding a
sale, charging back a vendor, revoking or terminating a vendor's
registration/contract).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/cut-yardage data logging
- `:schedule-staffing-operation` — floor-staff scheduling proposal
- `:coordinate-supply-order` — textile procurement proposal
- `:flag-quality-concern` — surface a defective-bolt/mislabeling/dye-lot-mismatch concern — **ALWAYS escalates**

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
   actors (e.g. ISIC 4719), reapplied here to fabric mills/wholesalers.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a quality-dispute resolution (issuing a
   refund or replacement, voiding a sale, charging back a vendor,
   revoking or terminating a vendor's registration/contract), is a
   permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, most recently ISIC
   4719): every scope-excluded term is phrased as the
   finalization/execution ACTION (e.g. "issue the refund", "void the
   sale", "charge back the vendor"), never as a bare noun (bare
   "refund", "dispute" or "quality concern") that could accidentally
   match inside this same namespace's own default mock-advisor text.
   This mattered concretely here: `textileops.advisor`'s own printed
   `:op` keyword for the legitimate concern-flagging proposal literally
   contains the substring "quality-concern"
   (`:flag-quality-concern`), and its default rationale legitimately
   discusses "品質懸念" (quality concern) and even mentions "品質紛争"
   (quality dispute) as a bare noun in the sales-record rationale's
   disclaimer clause ("値付けや品質紛争の判断は含まない" — "does not
   include pricing or quality-dispute judgment") — a bare-noun term list
   would have self-tripped this actor's own core happy path on every
   single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-quality-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above an $800 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`textileops.phase`'s 0→3 rollout table independently agrees:
`:flag-quality-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `quality-concern-holds-when-not-enabled` /
`quality-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`textileops.store` (MemStore, string-keyed store AND vendor directories
— two distinct registries, not one), `textileops.advisor`
(TextileRetailAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts quality-dispute-resolution-
finalization-scope content so the governor's scope scan can be exercised
end to end), `textileops.governor` (TextileRetailGovernor),
`textileops.phase` (0→3 rollout), `textileops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `textileops.sim` (demo driver,
`clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4751" ...}` block only, not appended, not touching any other
  entry, and not touching the separate `{:id "475" ...}` group entry):
  `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4751` / `cloud-itonami-G4751`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4751` /
  `cloud-itonami-isic-4751`, `:maturity` `:spec`→`:implemented`.
  `:required-technologies`/`:operating-states` left as-is (already
  matched the sibling 47xx retail-actor pattern:
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :retail]`,
  `[:intake :stock :sell :reconcile :reorder :audit]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-4751` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`textileops.sim` demo) walked all scenarios (phase-1
  approval-gated commit, phase-3 auto-commit for the three
  non-dispute-resolution/low-cost ops, always-escalating quality-concern
  flag, always-escalating over-threshold supply order, and five
  HARD-hold scenarios: unregistered store, unverified store, unverified
  supply-order vendor, non-`:propose` effect, scope-excluded content)
  without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows,
  and the immediately-preceding Wave 2 47xx sibling that most recently
  hit and fixed the same scope-exclusion self-trip failure mode)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4751"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2680004719 (`cloud-itonami-isic-4719`, closest structural sibling)
