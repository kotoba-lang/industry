# ADR-2607152900: cloud-itonami-isic-851 — Primary Education Operations Coordination

## Status

Accepted. `cloud-itonami-isic-851` promoted from `:spec` (placeholder in
`kotoba-lang/industry` registry) to `:implemented`. This is the first Wave 4
actor in the new ISIC-85 education cluster.

## Context

ISIC Rev.4 851 (Pre-primary and primary education) is Wave 4's education anchor
— introducing administrative coordination for vulnerable populations (children)
with the same conservative governance discipline as ADR-2607152700 (ISIC-873,
elderly residential care). This actor opens the education services vertical
within Wave 4's long-term human-services TAM expansion.

Existing registry entry was a bare placeholder with no actor implementation or
repo. This is a fresh, from-scratch scaffold built as the first education actor
under the verified-redo discipline established by ADR-2607152500 (Wave 4 rollout
amendment), closely mirrored on `cloud-itonami-isic-873`'s module shape and
scope-conservatism constraints (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout, string-keyed
student directory, append-only audit ledger).

**Scope**: ADMINISTRATIVE COORDINATION ONLY — attendance/logistics logging,
parent/guardian meeting scheduling, consumable supply coordination
(classroom/office/cafeteria supplies), staff shift proposals, and
safety-concern flagging (wellbeing incidents, suspected abuse/neglect,
bullying reports) — never grading, academic assessment, curriculum selection,
pedagogical decisions, disciplinary action/suspension/expulsion, special-education
(IEP) determinations, custody/guardianship decisions, or safety-authority
overrides.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-attendance-note` — routine attendance/logistics logging (present/absent,
  pickup/dropoff) — explicitly NOT academic performance or behavioral assessment
- `:schedule-parent-guardian-meeting` — parent/guardian meeting scheduling
  coordination
- `:coordinate-supply-request` — non-instructional consumables (classroom/office/
  cafeteria supplies) — explicitly NEVER curriculum materials or instructional
  content selection
- `:schedule-staff-shift-proposal` — administrative shift PROPOSAL only, never
  final binding assignment, never teaching-qualification or assignment decision
- `:flag-safety-concern` — wellbeing/safeguarding concerns (suspected abuse/neglect,
  bullying reports, injury) — **ALWAYS escalates**, never auto-commits at any phase

An op outside this closed set is treated as the SAME failure mode as a proposal
that drifts into forbidden scope (see check 3 below), not a separate "unknown op"
carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Student unverified** — the target student record must exist in the store AND
   be independently `:registered?`/`:verified?` before any proposal for it may
   commit or even escalate. Re-derived from the student's own store record every
   time, never from the proposal's own `:student-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not `:propose` is,
   by construction, a claim to directly actuate outside governance. HARD, not
   merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the closed
   allowlist, or whose rationale/summary/citations/draft value touches
   grading/academic-assessment/curriculum/pedagogical/disciplinary/special-education/
   custody/safety-authority territory, is a permanent, un-overridable block.
   Evaluated **unconditionally** on every proposal via a lower-cased substring scan
   of the proposal's own content (English + Japanese term list) — never trusting
   the advisor's own framing. The scope-excluded term list is deliberately
   qualified (e.g. "suspend" within "suspension", not bare keywords) rather than
   over-broad, so this HARD block never collides with the actor's own core valid
   use case — legitimately flagging an observed bullying report or wellbeing
   concern via `:flag-safety-concern` — a failure mode this ADR's own governor
   test suite exercises directly (`legitimate-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the governor is
otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`schoolops.phase`'s 0→3 rollout table independently agrees: `:flag-safety-concern`
is never a member of any phase's `:auto` set, at any phase — two layers, not one,
enforce the same invariant.

### 3. Module shape

`schoolops.store` (MemStore, string-keyed student directory), `schoolops.advisor`
(SchoolOpsAdvisor, mock + real-LLM seam via `langchain.model`, plus an
`:out-of-scope?` test hook that deliberately drafts grading/curriculum/pedagogical
content so the governor's scope scan can be exercised end-to-end),
`schoolops.governor` (SchoolOpsGovernor), `schoolops.phase` (0→3 rollout),
`schoolops.operation` (the `langgraph-clj` StateGraph: intake → advise → govern →
decide → commit | hold | request-approval), `schoolops.sim` (demo driver,
`clojure -M:run`).

## Consequences

- Registry entry added (new `:id "851"` block): `:repo` set to
  `"https://github.com/cloud-itonami/cloud-itonami-isic-851"`, `:business-id`
  to `"cloud-itonami-isic-851"`, `:maturity` to `:implemented`, `:robotics`
  false — coordination-only, no `:robotics` tech.
- Actor repo `cloud-itonami/cloud-itonami-isic-851` scaffolded and pushed to
  `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 40 tests containing 119 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`schoolops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for all four
  non-safety ops, always-escalating safety-concern flag, and all four
  HARD-hold scenarios: unregistered student, unverified student, non-
  `:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-873` (module-shape mirror, ADR-2607152700 —
  verified-redo pattern precedent, elderly residential care actor)
- `cloud-itonami-isic-861` (hospital coordination, ADR-2607153000,
  healthcare variant of scope-exclusion governance)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"851"` entry
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition, ISIC 851 within education services)
