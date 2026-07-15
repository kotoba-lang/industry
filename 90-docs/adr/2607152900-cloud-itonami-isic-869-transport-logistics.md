# ADR-2607152900: cloud-itonami-isic-869 — Non-Emergency Transport Logistics Coordination

## Status

Accepted. `cloud-itonami-isic-869` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry) to `:implemented` in the registry.

## Context

ISIC Rev.4 869 (Other human health activities — non-emergency transport and ancillary
health-support services) is a logistics-central domain — the core business is coordination of
non-emergency patient/medical transport and health-support operations, NOT emergency dispatch
triage or clinical decision-making. This actor differs from hospitals (isic-861) by both
domain scope and resource model: instead of beds, it manages vehicles and dispatch logistics.

Non-emergency transport coordination is a strictly administrative/logistical function, separate
from emergency dispatch systems which must be triage-aware and clinically-informed. Therefore,
**this actor's scope is conservatively narrow**: strictly non-emergency logistics coordination
with maximally-conservative scope-exclusion scanning to prevent any emergency dispatch/clinical
content whatsoever.

This is the EIGHTH and FINAL Wave 4 actor scaffold in today's batch, built under
the verified-redo discipline established by ADR-2607152500 (Wave 4 rollout amendment).
With this commit, the full ISIC-86/87/88 human-health-and-social-work cluster (861/862/
871/872/873/879/889/869 — all eight actors) reaches `:implemented` status.

**Scope**: COORDINATION ONLY, mirrored on the sibling
`cloud-itonami-isic-861`'s module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout, string-keyed vehicle
directory, append-only audit ledger). Domain-adapted for non-emergency
transport logistics: transport scheduling (pickup/dropoff coordination), vehicle/equipment
availability administration (not clinical readiness), non-clinical supply coordination,
administrative staff shift proposals (never clinical staffing decisions), and operational
safety-concern flagging (vehicle maintenance, route hazards — never patient medical
emergencies or emergency dispatch triage).
NEVER emergency dispatch triage decisions, medical necessity determination, clinical
assessment, diagnosis, treatment, medication/pharmaceutical, clinical procedures,
patient evaluation, vital signs, or clinical-authority overrides.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:schedule-transport` — non-emergency transport scheduling/logistics coordination
  (pickup/dropoff time and location, never clinical/triage decisions)
- `:coordinate-vehicle-availability` — administrative vehicle/equipment availability tracking
  (maintenance schedule, availability calendar, never clinical readiness determination)
- `:coordinate-supply-request` — non-medication, non-clinical consumables
  (dispatch office supplies, vehicle supplies, never medication or medical equipment)
- `:schedule-staff-shift-proposal` — administrative shift-roster PROPOSAL only,
  never clinical staffing adequacy decisions, never binding
- `:flag-safety-concern` — operational/vehicle safety concerns (mechanical issues, route hazards)
  — **ALWAYS escalates**, never auto-commits. Note: this is operational/vehicle safety,
  NOT a patient medical emergency — if content looks like an emergency dispatch or patient
  medical signal, that's scope-excluded content, not this op (a real emergency must
  go through actual emergency dispatch systems, not this actor).

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Transport-resource unverified** — the target vehicle/resource record must exist
   in the store AND be independently `:registered?`/`:verified?` before any proposal
   for it may commit or even escalate. Re-derived from the resource's own store record
   every time, never from the proposal's own `:vehicle-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not `:propose`
   is, by construction, a claim to directly actuate outside governance.
   HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the closed allowlist,
   or whose rationale/summary/citations/draft value touches emergency-dispatch/triage/
   medical-necessity/diagnosis/treatment/medication/clinical-decision/patient-care/
   vital-signs/clinical-authority territory, is a permanent, un-overridable block.
   Evaluated **unconditionally** on every proposal via a lower-cased substring scan of
   the proposal's own content (English + Japanese term list) — never trusting the
   advisor's own framing. The scope-excluded term list is deliberately qualified
   (e.g. "emergency dispatch", "emergency ambulance dispatch", not bare "emergency") and
   **maximally conservative** (non-emergency transport is fundamentally separate from
   emergency dispatch systems) rather than over-broad, so this HARD block never collides
   with the actor's own core valid use case — legitimately flagging a vehicle issue
   (e.g. "brake warning light") via `:flag-safety-concern` — a failure mode this ADR's
   own governor test suite exercises directly (`legitimate-facility-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`transportops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`transportops.store` (MemStore, string-keyed vehicle/dispatch directory),
`transportops.advisor` (TransportAdvisor, mock + a real-LLM seam via
`langchain.model`, plus an `:out-of-scope?` test hook that deliberately
drafts emergency/clinical/triage scope content so the governor's scope
scan can be exercised end to end), `transportops.governor` (TransportGovernor),
`transportops.phase` (0→3 rollout), `transportops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `transportops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "869" ...}` block, not appended): `:repo`/`:business-id`
  updated from nil to `"https://github.com/cloud-itonami/cloud-itonami-isic-869"` /
  `"cloud-itonami-isic-869"`, `:maturity` `:spec`→`:implemented`,
  `:required-technologies` trimmed (`:robotics` removed — coordination-only),
  `:operating-states` updated to match actor state machine
  (intake/advise/govern/decide/approve/commit/audit).
- Actor repo `cloud-itonami/cloud-itonami-isic-869` scaffolded and
  pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 21 tests containing 98 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 14 warnings (idiomatic).
  `clojure -M:run` (`transportops.sim` demo) walked all scenarios
  (phase-0 held, phase-3 auto-commit for logistics ops, always-escalating
  safety-concern flag, and all HARD-hold scenarios: unregistered vehicle,
  unverified vehicle, non-`:propose` effect, scope-excluded emergency/triage
  content) without error.

## Differences from residential-care and hospital siblings (isic-871/872/873/879/861)

While this actor mirrors the module shape of isic-861 (hospitals) and isic-871
(nursing facilities), the scope constraints reflect non-emergency transport's distinct
hazard profile and separation from emergency dispatch:

1. **Scope-exclusion term list is maximally conservative for transport isolation**:
   non-emergency transport is fundamentally separate from emergency dispatch and
   clinical decision-making. The term list scans for any emergency dispatch/triage/
   medical-necessity/clinical content. Unlike hospitals (which are inherently
   clinical settings), transport dispatch actors must block emergency dispatch entirely
   — if content reads like an emergency or emergency dispatch, that signals the
   proposal should route through actual emergency systems, not this coordination actor.
   The test `legitimate-facility-safety-concern-is-not-scope-excluded` verifies that
   legitimate operational concerns (e.g. "brake warning", "tire pressure") are NOT
   self-blocked by this conservative scanning.
2. **Operations are reframed as transport/logistics**: instead of "bed-assignment" or
   "resident-note", this actor speaks in "transport-scheduling" and "vehicle-availability"
   — purely logistical, not care-delivery language. Safety concerns are vehicle/route
   operational issues, not patient/resident wellbeing.
3. **Resource model is vehicles, not beds/residents**: the store manages a string-keyed
   vehicle directory, not facility beds or residents, reflecting transport's domain.

The two-layer enforcement (governor + phase gate) remains identical. The
advisor mock and governor HARD checks follow the same pattern. The test
suite has equal coverage (5 test namespaces, 21 tests, 98 assertions). The
difference is purely in domain vocabulary, resource model, and scope tuning
for transport's specific hazard profile (emergency dispatch isolation).

## Notes — completing ISIC-86/87/88 Wave 4 cluster

With this commit, all eight Wave 4 actors reach `:implemented` status:
- ISIC-861 (Hospital coordination)
- ISIC-862 (Clinical — not yet scaffolded, placeholder)
- ISIC-871 (Residential care — nursing)
- ISIC-872 (Residential care — sheltered housing)
- ISIC-873 (Residential care — elderly care)
- ISIC-879 (Residential care — other)
- ISIC-889 (Social work services)
- ISIC-869 (Non-emergency transport logistics)

The full human-health-and-social-work ISIC cluster for Wave 4 is now complete.

## References

- `cloud-itonami-isic-861/` (module-shape mirror, ADR-2607152800 —
  hospital coordination actor)
- `cloud-itonami-isic-871/` (module-shape mirror, ADR-2607152700 —
  residential-care coordination actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"869"` entry
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition)
- ADR-2607152800 (isic-861 hospital coordination)
- ADR-2607152700 (isic-873 eldercare coordination)
