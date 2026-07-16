# ADR-2607153400: cloud-itonami-isic-862 — Outpatient Clinic Activities Coordination

## Status

Accepted. `cloud-itonami-isic-862` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry) to `:implemented` in the registry.

## Context

ISIC Rev.4 862 (Medical and dental practice activities) — outpatient clinics
(doctor/dentist offices) — is a clinically-central industry, like hospitals
(isic-861). The core business IS clinical care delivery. Therefore this actor's
scope is **strictly back-office administrative coordination**, with maximally-
conservative scope-exclusion scanning to prevent any clinical/patient-care
content whatsoever.

While isic-861 (hospitals) and isic-862 (outpatient clinics) both handle patient
care, they differ in operational logistics. Hospitals coordinate bed/room
assignment and visitor access; outpatient clinics coordinate appointment
scheduling, referral logistics, supply requests, staff shifts, and facility
safety concerns — all logistical, never clinical.

This is the SEVENTH Wave 4 actor scaffold in today's batch, built under
the verified-redo discipline established by ADR-2607152500 (Wave 4 rollout
amendment) and exemplified by ADR-2607152800 (ISIC-861 hospital coordination).

**Scope**: COORDINATION ONLY, mirrored on the sibling
`cloud-itonami-isic-861`'s module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed appointment/provider directory, append-only audit ledger). Domain-adapted for
outpatient clinic *administrative* coordination: appointment scheduling logistics
(never clinical triage or urgency decisions), referral coordination logistics
(paperwork/scheduling handoff — never the clinical decision to refer), non-clinical
consumable supply coordination (office supplies, forms — never medication or medical/dental
equipment), administrative staff shift proposals (never clinical staffing adequacy),
and facility safety-concern flagging (equipment malfunction, facility hazards — never
patient-safety/clinical-emergency). NEVER diagnosis, treatment decisions, medication/
pharmaceutical, clinical procedures, patient assessment, triage/discharge,
vital signs monitoring, physical restraint decisions, end-of-life determinations,
or clinical-authority overrides.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:schedule-appointment` — appointment scheduling/rescheduling logistics
  (which appointment slots are available, provider calendar coordination) —
  explicitly NOT clinical triage, urgency assessment, or medical-necessity decisions
- `:coordinate-referral-logistics` — administrative logistics of sending/receiving
  a referral (paperwork, scheduling handoff) — explicitly NOT the clinical decision
  to refer, and NOT reviewing/acting on referral clinical content
- `:coordinate-supply-request` — non-medication, non-clinical-equipment consumables
  (office supplies, administrative forms) — explicitly NEVER medication or medical/
  dental equipment/instruments
- `:schedule-staff-shift-proposal` — administrative shift-roster PROPOSAL only,
  never a clinical staffing/coverage-adequacy decision, never binding
- `:flag-safety-concern` — facility/operational safety concerns (equipment
  malfunction, facility hazards) — **ALWAYS escalates**, never auto-commits.
  Note: this is facility-safety, NOT a patient-safety/clinical-emergency flag —
  if content looks like a clinical/patient-safety signal, that's scope-excluded
  content, not this op (a real clinical emergency must go through actual clinical
  staff/systems, not this coordination actor).

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Appointment-slot/resource unverified** — the target appointment/provider/resource
   record must exist in the store AND be independently `:registered?`/`:verified?`
   before any proposal for it may commit or even escalate. Re-derived from the
   resource's own store record every time, never from the proposal's own `:appt-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not `:propose` is,
   by construction, a claim to directly actuate outside governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the closed
   allowlist, or whose rationale/summary/citations/draft value touches
   diagnosis/treatment/medication/clinical-decision/patient-care/clinical-procedure/
   patient-assessment/triage/discharge/vital-signs/end-of-life/clinical-authority
   territory, is a permanent, un-overridable block. Evaluated **unconditionally**
   on every proposal via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own framing.
   The scope-excluded term list is deliberately qualified and **maximally
   conservative** (outpatient clinics are inherently clinical settings) rather than
   over-broad, so this HARD block never collides with the actor's own core valid
   use case — legitimately flagging a facility issue (e.g. "equipment malfunction
   in procedure room") via `:flag-safety-concern` — a failure mode this ADR's own
   governor test suite exercises directly (`legitimate-facility-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`clinicops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`clinicops.store` (MemStore, string-keyed appointment/provider directory),
`clinicops.advisor` (ClinicAdvisor, mock + a real-LLM seam via
`langchain.model`, plus an `:out-of-scope?` test hook that deliberately
drafts clinical/diagnosis/treatment scope content so the governor's scope
scan can be exercised end to end), `clinicops.governor` (ClinicGovernor),
`clinicops.phase` (0→3 rollout), `clinicops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `clinicops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "862" ...}` block, not appended): `:repo`/`:business-id`
  updated from nil to `"https://github.com/cloud-itonami/cloud-itonami-isic-862"` /
  `"cloud-itonami-isic-862"`, `:maturity` `:spec`→`:implemented`,
  `:required-technologies` trimmed (`:robotics` removed — coordination-only),
  `:operating-states` updated to match actor state machine
  (intake/advise/govern/decide/approve/commit/audit).
- Actor repo `cloud-itonami/cloud-itonami-isic-862` scaffolded and
  pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 9 tests containing 84 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 11 minor warnings (test style).
  `clojure -M:run` (`clinicops.sim` demo) walked all scenarios
  (phase-1 escalation, phase-3 auto-commit for all four non-safety ops,
  always-escalating safety-concern flag, and all three HARD-hold scenarios:
  unregistered appointment, non-`:propose` effect, scope-excluded clinical content)
  without error.

## Differences from isic-861 (Hospital Coordination)

While this actor mirrors the module shape of isic-861 (hospitals), the
domain logistics are adapted for outpatient clinics:

1. **Operations reframed for clinic workflows**: instead of "bed-assignment"
   and "visitor-access", this actor speaks in "appointment-scheduling" and
   "referral-logistics" — purely logistical, not care-delivery language.
2. **Scope-exclusion term list remains maximally conservative**: clinics inherit
   clinical work as their core business. The term-scanning is tuned to catch even
   peripheral clinical language. The test `legitimate-facility-safety-concern-is-not-
   scope-excluded` verifies that real facility issues (equipment malfunction in
   procedure room) are NOT self-blocked by this conservative scanning.
3. **Same governance discipline**: three HARD checks (unverified resource, effect
   not `:propose`, scope exclusion) + phase rollout (0: read-only, 1: assisted
   appointment, 2: assisted coordination, 3: supervised auto). Safety-concern op
   never auto-commits at any phase — two layers (governor + phase) enforce it.
4. **Same test coverage**: 5 test namespaces, 9 tests (fewer than isic-861's 42
   due to simpler demo store setup), 84 assertions, 0 failures/errors.

## References

- `cloud-itonami-isic-861/` (module-shape mirror, ADR-2607152800 —
  hospital coordination actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"862"` entry
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition — outpatient clinics in Wave 4)
- ADR-2607152800 (isic-861 hospital coordination, same governance pattern)
