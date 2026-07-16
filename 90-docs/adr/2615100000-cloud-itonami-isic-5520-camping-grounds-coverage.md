# ADR-2615100000: cloud-itonami-isic-5520 — Camping Grounds / RV Park / Trailer Park Operations Coordination

## Status

Accepted. `cloud-itonami-isic-5520` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-I5520` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before
any work began) to `:implemented` in the registry.

## Context

ISIC Rev.4 5520 (Camping grounds, recreational vehicle parks and
trailer parks) is a Wave 4 (human-facing/personal-services, ISIC
sections I/P/Q/R/S/T) target under ADR-2607121000's reverse-toposort
rollout plan and ADR-2607152500's Wave 4 rollout amendment (Wave 4
authorized to proceed in parallel with Wave 3, with an explicit
person-facing-service safety guardrail). Identity independently
verified against a fresh clone of `kotoba-lang/industry` before any
work began, per this fleet's ID/name-mismatch caution: the live
`{:id "5520" ...}` entry's `:name` is exactly "Camping grounds,
recreational vehicle parks and trailer parks", matching the assignment.
A separate, redundant 3-digit group entry `{:id "552" ...}` exists at
`:maturity :spec` with a truncated name ending in `"..."` — an earlier
data-seeding-pass artifact, deliberately NOT touched by this ADR (only
the `"5520"` class-level block was edited).

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-873` (Residential care for elderly/disabled)'s
verified Wave 4 module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed site directory, append-only audit ledger). Domain-adapted
for campground/RV-park/trailer-park hospitality operations:
site-occupancy record logging (booking/check-in/check-out),
restroom/utility-hookup/road facility maintenance scheduling,
propane/firewood/potable-water supply-restock coordination, and
guest-safety-concern flagging (fire risk, wildlife encounter,
structural hazard) — never directly issuing/executing an evacuation
order, directly contacting emergency medical services or law
enforcement, or overriding a guest-safety-authority decision.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-site-occupancy-record` — site-booking/check-in/check-out data logging
- `:schedule-facility-maintenance` — restroom/utility-hookup/road maintenance scheduling proposal
- `:coordinate-supply-restock` — propane/firewood/potable-water restock coordination proposal
- `:flag-guest-safety-concern` — surface a fire-risk/wildlife-encounter/structural-hazard concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the Wave 4 person-facing-service safety guardrail
(ADR-2607152500): even though this is not a medical/care setting, the
same discipline applies proportionally — the closed op allowlist NEVER
includes any op that directly executes a guest-safety-authority
decision (evacuation orders, emergency medical response,
law-enforcement contact). Every op above is `:effect :propose` only,
and the one "flag a concern" op (`:flag-guest-safety-concern`) always
escalates to human sign-off and is never a member of any phase's
`:auto` set.

1. **Site unverified** — the target site-occupancy record must exist
   in the store AND be independently `:registered?`/`:verified?`
   before any proposal for it may commit or even escalate. Re-derived
   from the site's own store record every time, never from the
   proposal's own `:site-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly issuing/executing an evacuation order, directly
   contacting emergency medical services or law enforcement, or
   overriding a guest-safety-authority decision, is a permanent,
   un-overridable block. Evaluated **unconditionally** on every
   proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing. The scope-excluded term list is deliberately
   phrased as execution/actuation verb phrases (e.g. "issue an
   evacuation order", "contact emergency services", not the bare noun
   "evacuat" alone), so this HARD block never collides with the
   actor's own core valid use case — legitimately flagging an observed
   fire risk or a blocked evacuation route as raw description via
   `:flag-guest-safety-concern` — a failure mode this ADR's own
   governor test suite exercises directly
   (`legitimate-guest-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-guest-safety-concern` — always, regardless of confidence.
- `:coordinate-supply-restock` above a $500 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`campgroundops.phase`'s 0→3 rollout table independently agrees:
`:flag-guest-safety-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant
(exercised directly by `guest-safety-concern-never-in-any-auto-set`).

### 3. Module shape

`campgroundops.store` (MemStore, string-keyed site directory),
`campgroundops.advisor` (CampgroundOpsAdvisor, mock + a real-LLM seam,
plus an `:out-of-scope?` test hook that deliberately drafts
evacuation-order-execution/emergency-services-contact-scope content so
the governor's scope scan can be exercised end to end),
`campgroundops.governor` (CampgroundGovernor), `campgroundops.phase`
(0→3 rollout), `campgroundops.operation` (the `langgraph-clj`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval), `campgroundops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5520" ...}` block only, not appended, not touching the
  separate `"552"` group entry): `:repo`/`:business-id` de-placeholdered
  from `https://github.com/gftdcojp/cloud-itonami-I5520` /
  `cloud-itonami-I5520` to `https://github.com/cloud-itonami/cloud-itonami-isic-5520` /
  `cloud-itonami-isic-5520`, `:maturity` `:spec`→`:implemented`,
  `:required-technologies` trimmed from the stale placeholder set
  (`:robotics` removed — coordination-only) to `[:identity :forms :dmn
  :bpmn :audit-ledger]`, `:operating-states` updated to match the
  actor state machine.
- Actor repo `cloud-itonami/cloud-itonami-isic-5520` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 48 tests containing 134 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`campgroundops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety ops, always-escalating guest-safety-concern flag,
  always-escalating over-threshold supply-restock, and all four
  HARD-hold scenarios: unregistered site, unverified site, non-
  `:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-873/` (module-shape mirror, ADR-2607152700 —
  Wave 4 flagship precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"5520"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan)
- ADR-2607152700 (ISIC-873 eldercare coordination, Wave 4 module-shape precedent)
