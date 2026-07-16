# ADR-2616005630: cloud-itonami-isic-5630 — Beverage Serving Activities Operations Coordination

## Status

Accepted. `cloud-itonami-isic-5630` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-I5630` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before
any work began) to `:implemented` in the registry.

## Context

ISIC Rev.4 5630 (Beverage serving activities) is a Wave 4
(human-facing/personal-services, ISIC sections I/P/Q/R/S/T) target
under ADR-2607121000's reverse-toposort rollout plan and
ADR-2607152500's Wave 4 rollout amendment (Wave 4 authorized to
proceed in parallel with Wave 3, with an explicit person-facing-service
safety guardrail). Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began, per this
fleet's ID/name-mismatch caution: the live `{:id "5630" ...}` entry's
`:name` is exactly "Beverage serving activities" — bars, pubs, coffee
shops, juice bars; on-premise beverage service, distinct from beverage
manufacturing — matching the assignment. A separate, redundant 3-digit
group entry `{:id "563" ...}` exists at `:maturity :implemented` (a
separate registry artifact, deliberately NOT touched by this ADR —
only the `"5630"` class-level block was edited).

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-873` (Residential care for elderly/disabled)'s
verified Wave 4 module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed venue directory, append-only audit ledger). Domain-adapted
for bar/pub/coffee-shop/juice-bar beverage-serving operations:
order/tab/inventory-draw service-record logging, staffing/prep
scheduling, beverage/ingredient supply-order coordination, and
guest-safety-concern flagging (suspected over-service, suspected
underage attempt, altercation) — never directly finalizing a
responsible-service-of-alcohol (RSA) authority decision, and never
directly controlling equipment (taps, POS terminals, access control).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — order/tab/inventory-draw data logging
- `:schedule-staffing-operation` — shift/prep scheduling proposal
- `:coordinate-supply-order` — beverage/ingredient procurement proposal
- `:flag-guest-safety-concern` — surface an over-service/underage-attempt/altercation concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the Wave 4 person-facing-service safety guardrail
(ADR-2607152500), applied specifically to this domain's direct
guest-safety dimension (alcohol over-service, minor-access control,
intoxicated-patron handling): the closed op allowlist NEVER includes
any op that directly finalizes a responsible-service-of-alcohol
authority decision. Every op above is `:effect :propose` only, and the
one "flag a concern" op (`:flag-guest-safety-concern`) always escalates
to human sign-off and is never a member of any phase's `:auto` set.

1. **Venue unverified** — the target venue record must exist in the
   store AND be independently `:registered?`/`:verified?` (business
   registration + beverage-service license verification) before any
   proposal for it may commit or even escalate. Re-derived from the
   venue's own store record every time, never from the proposal's own
   `:venue-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **RSA-decision / scope exclusion** — any proposal (regardless of
   op) outside the closed allowlist, or whose rationale/summary/
   citations/draft value directly finalizes a responsible-service-of-
   alcohol authority decision (continuing/resuming/authorizing service
   to an intoxicated patron; overriding/bypassing/waiving an
   age-verification/ID-check failure; serving a minor), is a
   permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's
   own content (English + Japanese term list) — never trusting the
   advisor's own framing. The blocked-term list is deliberately phrased
   as decision-finalizing multi-word phrases (e.g. "continue serving
   the intoxicated", "override age verification", not the bare nouns
   "intoxicated"/"underage" alone), so this HARD block never collides
   with the actor's own core valid use case — legitimately flagging a
   suspected over-service or underage-attempt concern as a raw
   observation via `:flag-guest-safety-concern` — a failure mode this
   ADR's own governor test suite exercises directly
   (`legitimate-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-guest-safety-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a 5000 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`beverageops.phase`'s 0→3 rollout table independently agrees:
`:flag-guest-safety-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant
(exercised directly by `guest-safety-concern-never-in-any-auto-set`).

### 3. Module shape

`beverageops.store` (MemStore, string-keyed venue directory),
`beverageops.advisor` (BeverageServiceAdvisor, mock + a real-LLM seam,
plus an `:out-of-scope?` test hook that deliberately drafts
RSA-authority-decision content so the governor's scope scan can be
exercised end to end), `beverageops.governor` (BeverageServiceGovernor),
`beverageops.phase` (0→3 rollout), `beverageops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit
| hold | request-approval), `beverageops.sim` (demo driver, `clojure
-M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5630" ...}` block only, not appended, not touching the
  separate `"563"` group entry): `:repo`/`:business-id` de-placeholdered
  from `https://github.com/gftdcojp/cloud-itonami-I5630` /
  `cloud-itonami-I5630` to `https://github.com/cloud-itonami/cloud-itonami-isic-5630` /
  `cloud-itonami-isic-5630`, `:maturity` `:spec`→`:implemented`,
  `:required-technologies` trimmed from the stale placeholder set
  (`:robotics` removed — coordination-only) to `[:identity :forms :dmn
  :bpmn :audit-ledger]`, `:operating-states` updated to match the
  actor state machine (`[:intake :advise :govern :approve :commit
  :audit]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-5630` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target) and pushed to `main`
  (`59eb4ad8518dba780bda90b657f30c1600751b9f`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 48 tests containing 132 assertions. 0 failures, 0 errors.`**
  (`clojure -M:dev:test`), independently re-verified against a fresh
  clone with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:dev:run` (`beverageops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the
  non-safety ops, always-escalating guest-safety-concern flag,
  always-escalating over-threshold supply order, and the three
  HARD-hold scenarios: unregistered venue, license-unverified venue,
  non-`:propose` effect, RSA-decision-excluded content) without error.

## References

- `cloud-itonami-isic-873/` (module-shape mirror, ADR-2607152700 —
  Wave 4 flagship precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"5630"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan)
- ADR-2607152700 (ISIC-873 eldercare coordination, Wave 4 module-shape precedent)
