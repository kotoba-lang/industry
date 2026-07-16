# ADR-2616562100: cloud-itonami-isic-5621 — Event Catering Operations Coordination

## Status

Accepted. `cloud-itonami-isic-5621` promoted from `:spec` (stale
placeholder `gftdcojp/cloud-itonami-I5621` in `kotoba-lang/industry`
registry) to `:implemented` in the registry.

## Context

ISIC Rev.4 5621 (Event catering) is a Wave 4 (human-facing/personal
services) target under the reverse-topological rollout plan
(ADR-2607121000) and Wave 4's parallel-authorization amendment
(ADR-2607152500). Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began: the live
`{:id "5621" ...}` registry entry's `:name` is exactly "Event
catering", confirming the assignment. The sibling 3-digit group entry
`{:id "562" ...}` ("Event catering and other food service
activities") was already `:implemented` from an earlier data-seeding
pass and is a separate, already-covered registry artifact — this ADR
touches only the finer `"5621"` class-level entry, not `"562"`.

No prior repository existed at either the stale
`gftdcojp/cloud-itonami-I5621` placeholder or the real
`cloud-itonami` org target (`gh api` 404 confirmed for both before
scaffolding). This is a fresh, from-scratch scaffold, mirroring the
established Wave 4 person-facing-service governor pattern from
`cloud-itonami-isic-873` (Residential care for elderly/disabled,
ADR-2607152700, this fleet's Wave 4 flagship) module-for-module:
advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout, string-keyed
entity directory, append-only audit ledger. Domain-adapted from
elderly-care resident coordination to event-catering order/event
coordination: catering order/event record logging (menu, headcount,
allergen flags), prep/staging/delivery event scheduling,
ingredient/equipment supply-order coordination, and food-safety-
concern flagging — never directly finalizing a food-safety-authority
decision (allergen-exclusion override, kitchen safety certification
post-incident), a health-department/regulatory clearance, a
product-recall decision, or a food-service license suspension/
revocation.

**Wave 4 person-facing-service safety guardrail (ADR-2607152500)**:
event catering has a direct food-safety/allergen-exposure dimension,
so the closed op allowlist structurally excludes any op that could
directly finalize a food-safety-authority decision — those are always
either a hard permanent block (governor scope-exclusion) or an
always-escalate op, never auto-commit-eligible. The `:flag-food-
safety-concern` op always escalates to human sign-off and is never a
member of any phase's `:auto` set, at any phase.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-catering-order-record` — event/menu/headcount/allergen-flag data logging
- `:schedule-catering-event` — prep/staging/delivery scheduling proposal
- `:coordinate-supply-order` — ingredient/equipment procurement proposal
- `:flag-food-safety-concern` — allergen-mismatch/temperature-abuse/contamination concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Order unverified** — the target catering order/event record must
   exist in the store AND be independently `:registered?`/`:verified?`
   before any proposal for it may commit or even escalate. Re-derived
   from the order's own store record every time, never from the
   proposal's own `:order-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a food-safety-authority decision
   (allergen-exclusion override, kitchen safety certification,
   health-department/regulatory clearance, product recall, license
   suspension/revocation, compliance enforcement) is a permanent,
   un-overridable block. Evaluated **unconditionally** on every
   proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing. The scope-excluded term list is deliberately
   qualified (e.g. "override the allergen exclusion", "certify the
   kitchen as safe", not bare keywords like "allergen"/"safety") so
   this HARD block never collides with the actor's own core valid use
   case — legitimately flagging an observed allergen mismatch or
   temperature-abuse concern via `:flag-food-safety-concern` — a
   failure mode this ADR's own governor test suite exercises directly
   (`legitimate-food-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-food-safety-concern` — always, regardless of confidence.
  Never in any phase's `:auto` set.
- `:coordinate-supply-order` above `cost-threshold` (5000) — a
  high-value procurement commitment always needs a human's sign-off,
  regardless of confidence. Independently verified: the governor's
  own `high-cost-supply-order?` check forces `:escalate` even though
  `:coordinate-supply-order` IS a phase-3 `:auto` member for ordinary
  (below-threshold) proposals — the phase gate's `else` branch passes
  a governor-level `:escalate` disposition through unchanged.
- Low advisor confidence (`< 0.6`).

`cateringops.phase`'s 0→3 rollout table independently agrees:
`:flag-food-safety-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant
(`phase-test/safety-concern-never-in-any-phase-auto-set`).

### 3. Module shape

`cateringops.store` (MemStore, string-keyed catering-order
directory), `cateringops.advisor` (CateringAdvisor, mock + a
real-LLM seam, plus an `:out-of-scope?` test hook that deliberately
drafts allergen-exclusion-override/kitchen-certification-scope
content so the governor's scope scan can be exercised end to end),
`cateringops.governor` (CateringGovernor), `cateringops.phase` (0→3
rollout), `cateringops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`cateringops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5621" ...}` block, not appended): `:repo`/`:business-id`
  de-placeholdered from `gftdcojp/cloud-itonami-I5621` to
  `cloud-itonami/cloud-itonami-isic-5621`, `:maturity` `:spec`→`:implemented`.
  `:required-technologies`/`:optional-technologies`/`:operating-states`
  left unchanged (out of this promotion's scope, per this fleet's
  minimal-diff discipline).
- Actor repo `cloud-itonami/cloud-itonami-isic-5621` scaffolded and
  pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 47 tests containing 134 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh
  clone with the same result. `clojure -M:lint`: 0 errors, 0
  warnings. `clojure -M:run` (`cateringops.sim` demo) walked all
  scenarios (phase-1 approval-gated commit, phase-3 auto-commit for
  clean below-threshold ops, always-escalating high-cost supply order,
  always-escalating food-safety-concern flag, and all four HARD-hold
  scenarios: unregistered order, unverified order, non-`:propose`
  effect, scope-excluded content) without error.
- Fully portable `.cljc` sources, no JVM-only interop anywhere in
  `src/`.

## References

- `cloud-itonami-isic-873/` (module-shape mirror, ADR-2607152700 —
  Wave 4 flagship precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"5621"` entry (finer class-level; the `"562"` group-level entry is
  separate and untouched)
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service
  safety guardrail)
- ADR-2607121000 (Wave definition, reverse-topological rollout plan)
