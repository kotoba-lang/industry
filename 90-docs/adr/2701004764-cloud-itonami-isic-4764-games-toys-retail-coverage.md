# ADR-2701004764: cloud-itonami-isic-4764 — Specialized Games/Toys Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4764` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4764` repo) to `:implemented` in
the registry.

## Context

ISIC Rev.5 4764 (Retail sale of games and toys in specialized stores) is
a Wave 2 (coordination/logistics/trade, ADR-2607121000) target. A fresh
clone of `kotoba-lang/industry` was made before any work began, and the
live `{:id "4764" ...}` entry's `:name` was confirmed to be exactly
"Retail sale of games and toys in specialized stores" — no ID/name
mismatch, per this fleet's caution. `gh api
repos/cloud-itonami/cloud-itonami-isic-4764` confirmed a 404 (fresh
scaffold, no pre-existing repo). The redundant 3-digit group entry
`{:id "476" ...}` ("Retail sale of cultural and recreation goods in
specializ...") was left untouched — a separate, coarser-granularity
registry artifact.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-4719` (Other retail sale in non-specialized stores)'s
verified module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed store/vendor directories, append-only audit ledger).
Domain-adapted for specialized games/toys retail stores:
sales/inventory/return transaction logging, floor-staff scheduling,
toy/game merchandise supply-order coordination with registered vendors,
and product-safety-concern flagging — never finalizing a
recall-compliance decision or an age-grading-safety decision. Games/toys
retail carries a direct child-product-safety dimension (choking/
small-parts hazards, banned substances, age-grading label accuracy), so
this vertical receives a dedicated safety guardrail: the closed op
allowlist NEVER includes any op that directly finalizes a
recall-compliance or age-grading-safety decision — always a hard
permanent block or an always-escalate op, never auto-commit-eligible.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/return data logging
- `:schedule-staffing-operation` — floor-staff scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-safety-concern` — surface a product-recall/choking-hazard/
  age-grading concern — **ALWAYS escalates, never in any phase's `:auto`
  set**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Store unverified** — the target store's record (business
   registration + retail license) must exist AND be independently
   `:registered?`/`:verified?` in the store before any proposal for it
   may commit or even escalate. Re-derived from the store's own record
   every time, never from the proposal's own `:store-id` claim.
2. **Vendor unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:vendor-id` that
   resolves to an independently `:registered?`/`:verified?` vendor
   record — a supply-chain counterparty-verification gate that matters
   especially in this vertical, since an unverified import broker is
   exactly the channel through which unsafe or mislabeled toy/game stock
   enters a store.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly FINALIZING a recall-compliance decision (declaring a
   SKU recall-cleared, initiating/executing a recall, closing out a
   recall case) or an age-grading-safety decision (certifying/approving
   an age-grade/age-appropriate-use label as the OFFICIAL
   determination), is a permanent, un-overridable block. Evaluated
   **unconditionally** on every proposal via a lower-cased substring
   scan of the proposal's own content (English + Japanese term list) —
   never trusting the advisor's own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, e.g.
   `cloud-itonami-isic-4719`/`cloud-itonami-isic-4722`): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "finalize the recall", "certify the age grade"), never as a
   bare noun ("recall", "choking hazard", "age grade", "age rating")
   that could accidentally match inside this same namespace's own
   default mock-advisor `:flag-safety-concern` rationale, whose whole
   job is to talk about choking hazards/small parts/suspected-recall
   stock/age-grading-label concerns. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` (and
   `:op-not-allowed`) absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence. This is the
  CHILD-PRODUCT-SAFETY op in this vertical; it may only surface a
  concern for a human, never adjudicate one.
- `:coordinate-supply-order` above a $750 estimated-cost threshold —
  always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`toygameops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `safety-concern-holds-when-not-enabled` /
`safety-concern-escalates-when-enabled` /
`safety-concern-never-in-any-phase-auto-set`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`toygameops.store` (MemStore, string-keyed store/vendor directories),
`toygameops.advisor` (ToyGameRetailAdvisor, mock + a real-LLM seam, plus
an `:out-of-scope?` test hook that deliberately drafts
recall-finalization/age-grading-certification-scope content so the
governor's scope scan can be exercised end to end), `toygameops.governor`
(ToyGameRetailGovernor), `toygameops.phase` (0→3 rollout),
`toygameops.operation` (the `langgraph-clj` StateGraph: intake → advise →
govern → decide → commit | hold | request-approval), `toygameops.sim`
(demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4764" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "476" ...}` group entry which was
  left untouched): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4764` / `cloud-itonami-G4764`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4764` /
  `cloud-itonami-isic-4764`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies`/`:optional-technologies` left as-is:
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :retail]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-4764` scaffolded (fresh —
  404 confirmed before any work began) and pushed to `main`
  (commit `57038b5f97e3dac80155f30d7d50acb57518433d`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`toygameops.sim` demo) walked all scenarios (phase-1
  approval-gated commit, phase-3 auto-commit for the three non-safety/
  low-cost ops, always-escalating safety-concern flag, always-escalating
  over-threshold supply order, and all HARD-hold scenarios: unregistered
  store, unverified store, unverified vendor, non-`:propose` effect,
  scope-excluded content) without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the general-merchandise-retail actor pattern this actor
  closely adapted)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4764"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
