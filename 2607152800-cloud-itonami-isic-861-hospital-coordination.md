# ADR-2607152800: cloud-itonami-isic-861 — Hospital Activities Coordination

## Status

Accepted. `cloud-itonami-isic-861` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry) to `:implemented` in the registry.

## Context

ISIC Rev.4 861 (Hospital activities) is a clinically-central industry
— the core business of a hospital IS clinical care delivery. This actor
differs fundamentally from the residential-care actors (isic-871/872/873/879,
ADR-2607152700 et al.) by both domain and scope constraint.

Residential care actors treat care coordination as their primary domain.
Hospitals treat clinical care as primary, with *back-office coordination*
as strictly secondary. Therefore, **this actor's scope is more
conservatively narrow than even the residential-care siblings**: strictly
administrative/facility coordination only, with maximally-conservative
scope-exclusion scanning to prevent any clinical/patient-care content
whatsoever.

This is the SIXTH Wave 4 actor scaffold in today's batch, built under
the verified-redo discipline established by ADR-2607152500 (Wave 4 rollout
amendment) and exemplified by ADR-2607152300 (ISIC-0520 lignite mining).

**Scope**: COORDINATION ONLY, mirrored on the sibling
`cloud-itonami-isic-871`'s module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed bed/resource directory, append-only audit ledger). Domain-adapted for
hospital *administrative* coordination: bed/room assignment logistics (never
triage or admission-priority), visitor access scheduling, non-clinical
consumable supply coordination (linens, food service, administrative supplies —
never medication or medical equipment), administrative staff shift proposals
(never clinical staffing/coverage adequacy), and facility safety-concern
flagging (equipment malfunction, facility hazards — never patient-safety/
clinical-emergency). NEVER diagnosis, treatment decisions, medication/
pharmaceutical, clinical procedures, patient assessment, triage/discharge,
vital signs monitoring, physical restraint decisions, end-of-life determinations,
or clinical-authority overrides.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:coordinate-bed-assignment` — non-clinical bed/room logistics coordination
  (which physical bed is free, room turnover scheduling) — explicitly NOT a
  triage, admission-priority, or discharge-readiness clinical decision
- `:schedule-visitor-access` — visitor scheduling/access coordination
- `:coordinate-supply-request` — non-medication, non-clinical-equipment
  consumables (linens, food service, administrative supplies) — explicitly
  NEVER medication or medical equipment/devices
- `:schedule-staff-shift-proposal` — administrative shift-roster PROPOSAL only,
  never a clinical staffing/coverage-adequacy decision, never binding
- `:flag-safety-concern` — facility/operational safety concerns (equipment
  malfunction reports, facility hazards) — **ALWAYS escalates**, never
  auto-commits. Note: this is facility-safety, NOT a patient-safety/clinical-
  emergency flag — if content looks like a clinical/patient-safety signal,
  that's scope-excluded content, not this op (a real clinical emergency must
  go through actual clinical staff/systems, not this coordination actor).

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Facility-resource/location unverified** — the target bed/room/resource
   record must exist in the store AND be independently `:registered?`/
   `:verified?` before any proposal for it may commit or even escalate.
   Re-derived from the resource's own store record every time, never from
   the proposal's own `:bed-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not `:propose`
   is, by construction, a claim to directly actuate outside governance.
   HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the closed
   allowlist, or whose rationale/summary/citations/draft value touches
   diagnosis/treatment/medication/clinical-decision/patient-care/clinical-
   procedure/patient-assessment/triage/discharge/vital-signs/end-of-life/
   clinical-authority territory, is a permanent, un-overridable block.
   Evaluated **unconditionally** on every proposal via a lower-cased substring
   scan of the proposal's own content (English + Japanese term list) — never
   trusting the advisor's own framing. The scope-excluded term list is
   deliberately qualified (e.g. "patient diagnosis", "treatment plan",
   "medication administration", not bare keywords) and **maximally
   conservative** (hospitals are inherently clinical settings) rather than
   over-broad, so this HARD block never collides with the actor's own core
   valid use case — legitimately flagging a facility issue (e.g. "elevator
   malfunction near ward 3") via `:flag-safety-concern` — a failure mode
   this ADR's own governor test suite exercises directly
   (`legitimate-facility-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`hospitalops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`hospitalops.store` (MemStore, string-keyed bed/resource directory),
`hospitalops.advisor` (HospitalAdvisor, mock + a real-LLM seam via
`langchain.model`, plus an `:out-of-scope?` test hook that deliberately
drafts clinical/diagnosis/treatment scope content so the governor's scope
scan can be exercised end to end), `hospitalops.governor` (HospitalGovernor),
`hospitalops.phase` (0→3 rollout), `hospitalops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `hospitalops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "861" ...}` block, not appended): `:repo`/`:business-id`
  updated from nil to `"https://github.com/cloud-itonami/cloud-itonami-isic-861"` /
  `"cloud-itonami-isic-861"`, `:maturity` `:spec`→`:implemented`,
  `:required-technologies` trimmed (`:robotics` removed — coordination-only),
  `:operating-states` updated to match actor state machine
  (intake/advise/govern/decide/approve/commit/audit).
- Actor repo `cloud-itonami/cloud-itonami-isic-861` scaffolded and
  pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 42 tests containing 123 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`hospitalops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for all four
  non-safety ops, always-escalating safety-concern flag, and all four
  HARD-hold scenarios: unregistered bed, unverified bed, non-`:propose`
  effect, scope-excluded clinical content) without error.

## Differences from residential-care siblings (isic-871/872/873/879)

While this actor mirrors the module shape of isic-871 (nursing facilities),
the scope constraints are **tighter and more conservative** due to hospitals'
clinical centrality:

1. **Scope-exclusion term list is maximally conservative**: hospitals inherit
   clinical work as their core business. The residential-care siblings
   explicitly exclude clinical diagnosis, medication, wound care, etc.
   This actor goes further: it scans for ANY clinical/patient-care/patient-
   assessment/triage/discharge content, tuned to catch even peripheral
   clinical language. The test `legitimate-facility-safety-concern-is-not-
   scope-excluded` verifies that legitimate facility-safety concerns (e.g.
   "elevator down") are NOT self-blocked by this conservative scanning.
2. **Operations are reframed as facility/administrative**: instead of
   "resident-note" and "family-visit", this actor speaks in "bed-assignment"
   and "visitor-access" — purely logistical, not care-delivery language.
3. **Safety-concern op is facility-scoped only**: residential actors flag
   resident wellbeing concerns (falls, distress). Hospitals flag facility/
   operational concerns (equipment down, spill on floor). Patient-safety
   concerns (clinical emergency, vital-sign change) are not this actor's
   territory — they route through actual clinical staff.

The two-layer enforcement (governor + phase gate) remains identical. The
advisor mock and governor HARD checks follow the same pattern. The test
suite has equal coverage (5 test namespaces, 42 tests, 123 assertions).
The difference is purely in domain vocabulary and scope tuning for
hospitals' specific hazard profile.

## References

- `cloud-itonami-isic-871/` (module-shape mirror, ADR-2607152700 —
  residential-care coordination actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"861"` entry
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition)
- ADR-2607152300 (0520 lignite, verified-redo pattern)
- ADR-2607152700 (isic-873 residential-care, scope comparison)
