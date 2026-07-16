# ADR-2617000000: cloud-itonami-isic-5914 — Motion Picture Projection Operations Coordination

## Status

Accepted. `cloud-itonami-isic-5914` promoted from `:spec` (registry
placeholder, `:repo` pointing at the stale `gftdcojp/cloud-itonami-J5914`
target that was never created) to `:implemented` in the
`kotoba-lang/industry` registry.

## Context

ISIC Rev.4 5914 (Motion picture projection activities — cinema/theater
exhibition) is part of Wave 4's human-facing/personal-services fleet
(ADR-2607152500 amendment to ADR-2607121000). No prior repository existed
at either the stale `gftdcojp/cloud-itonami-J5914` placeholder or the real
`cloud-itonami` org target (`gh api` 404 confirmed for both before this work
began). The registry entry's identity (`{:id "5914" :name "Motion picture
projection activities"}`) was independently verified against a fresh clone
before any work began, per this fleet's ID/name-mismatch caution — distinct
from siblings 5911 (motion picture/video production), 5912
(post-production), and 5913 (distribution), built in parallel by sibling
agents in this same batch.

**Scope**: OPERATIONS COORDINATION ONLY, mirrored module-for-module on
`cloud-itonami-isic-873` (Residential care activities for the elderly and
disabled, ADR-2607152700), this fleet's verified Wave 4 person-facing-
service module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed target directory, append-only audit ledger). Domain-adapted
for cinema/theater exhibition: showtime/attendance/print-quality logging,
showtime/screen-allocation scheduling proposals, digital-cinema-package
(DCP)/print delivery coordination, and patron-safety-concern flagging
(fire/egress, disturbance, age-rating-admission concerns) — never directly
finalizing a patron-safety-authority decision (an evacuation override, or
an age-rating admission-check override) and never directly actuating
projection/booth or fire/life-safety equipment.

**Wave 4 person-facing-service safety guardrail (ADR-2607152500)**:
cinema/theater operations have a direct patron-safety dimension (fire/
egress, age-rating enforcement for admission). Per this ADR's own
guardrail, the closed op allowlist below contains no op that itself
finalizes a patron-safety-authority decision — such decisions are always
either a hard, permanent governor block (if an advisor drifts into
attempting one) or handled entirely by the real safety authority on scene,
outside this actor's authority. The only op through which this actor may
touch patron-safety territory at all, `:flag-patron-safety-concern`, is
structurally an always-escalate op, never auto-commit-eligible at any
phase.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-screening-record` — showtime/attendance/print-quality data logging
- `:schedule-screening-operation` — showtime/screen-allocation scheduling proposal
- `:coordinate-print-delivery` — DCP/print delivery coordination
- `:flag-patron-safety-concern` — fire/egress/disturbance/age-rating-admission concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Screening unverified** — the target screening record must exist in
   the store AND be independently `:registered?`/`:verified?` before any
   proposal for it may commit or even escalate. Re-derived from the
   screening's own store record every time, never from the proposal's own
   `:screening-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   directly finalizes a patron-safety-authority decision (an evacuation
   override, an age-rating admission-check override) or directly actuates
   projection/booth or fire/life-safety equipment, is a permanent,
   un-overridable block. Evaluated **unconditionally** on every proposal
   via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own
   framing.

   **CRITICAL, per this fleet's own repeatedly-discovered self-tripping
   bug class**: `scope-excluded-terms` is deliberately phrased as the
   finalization/execution ACTION ("override the evacuation order",
   "directly control the projector"), never as a bare noun ("evacuation",
   "age rating", "projector"). A bare-noun phrasing would self-trip on
   this actor's own core valid use case — `:flag-patron-safety-concern`'s
   entire purpose is to report raw observations that legitimately mention
   fire alarms, evacuation routes, and age-rating admission concerns. This
   governor test suite exercises the distinction directly via
   `legitimate-patron-safety-concern-is-not-scope-excluded` (a concern
   report describing an observed fire-alarm/egress/age-rating-admission
   situation must never trip the gate) and, as a dedicated regression test
   required by this fleet's own known-bug-pattern caution,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` (every
   op the default mock advisor can produce, exercised end-to-end through
   `governor/check`, must never self-trip on its own default rationale/
   summary text).

Escalation (SOFT, always human sign-off, only reached when the governor is
otherwise clean):

- `:flag-patron-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`cinemaops.phase`'s 0→3 rollout table independently agrees:
`:flag-patron-safety-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant
(exercised directly by `patron-safety-concern-never-in-any-auto-set`).

### 3. Module shape

`cinemaops.store` (MemStore, string-keyed screening directory),
`cinemaops.advisor` (CinemaOpsAdvisor, mock + a real-LLM seam, plus an
`:out-of-scope?` test hook that deliberately drafts evacuation-override/
projector-control-scope content so the governor's scope scan can be
exercised end to end), `cinemaops.governor` (CinemaOpsGovernor),
`cinemaops.phase` (0→3 rollout), `cinemaops.operation` (the `langgraph-clj`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval), `cinemaops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5914" ...}` block, not appended): `:repo`/`:business-id` updated
  from the stale `gftdcojp/cloud-itonami-J5914` placeholder to
  `"cloud-itonami/cloud-itonami-isic-5914"`, `:maturity` `:spec`→
  `:implemented`, `:required-technologies` trimmed from the stale
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :phone]` placeholder
  set to the coordination-only shape actually implemented
  `[:identity :forms :dmn :bpmn :audit-ledger]` (matching sibling
  `cloud-itonami-isic-873`'s own required-technologies), `:operating-states`
  updated from the stale telecom-flavored `[:intake :provision :route :bill
  :support :audit]` placeholder to match this actor's own state machine
  (`[:intake :advise :govern :approve :commit :audit]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-5914` scaffolded and pushed
  to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 42 tests containing 125 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`, both at push time and again from a fresh post-push
  clone). `clojure -M:lint`: 0 errors, 0 warnings.

## References

- `cloud-itonami-isic-873/` (module-shape mirror, ADR-2607152700 — Wave 4
  flagship, verified-redo pattern precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"5914"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort plan)
