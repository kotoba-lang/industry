# ADR-2607153800: cloud-itonami-isic-853 — Higher Education Actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-853`, `orgs/kotoba-lang/industry` registry

## Context

Higher education (ISIC Rev.4 class 853) serves university and college students, typically legal adults with greater autonomy than secondary-education (ISIC 852) cohorts. This actor coordinates administrative operations only — enrollment/advising appointment scheduling, facility/classroom booking, supply coordination, staff shift proposals, and safety-concern flagging — never academic grading, admissions decisions, academic-standing determinations, degree conferral, disciplinary action, or safety-authority overrides.

Implemented as part of Wave 4 (human-services vertical) in ADR-2607121000, following the actor pattern established in ADR-2607072915 (ISIC 851, primary education), ADR-2607153700 (ISIC 852, secondary education), and ADR-2607152700 (ISIC 873, eldercare).

## Decision

### 1. Repo: `cloud-itonami/cloud-itonami-isic-853`

**Lineage**: Adapted from `cloud-itonami/cloud-itonami-isic-852` (secondary education) with namespace/domain changes only (`highereds` / `:services/higher-education`). Scope exclusions and hard checks follow identical pattern to 851/852 but with higher-education-specific scope boundaries (no admissions/academic-standing/degree-conferral determinations).

**Public**: Yes (verified via GitHub API: `private: false`).

**Governance**: AGPL-3.0-or-later. Same contributor guidelines, code-of-conduct, security policies as 851/852.

### 2. Actor Architecture

**Modules**: `highereds.{store, advisor, governor, phase, operation, sim}` — all `.cljc`, langgraph-clj StateGraph, one run = one operation.

**Three HARD governor checks** (permanent, un-overridable):
1. **Student verified**: target student must exist AND be `:registered?`/`:verified?` in the store.
2. **Effect is :propose**: any `:effect` value other than `:propose` is rejected outright.
3. **Scope exclusion**: any proposal touching grading/academic-assessment, admissions decisions, academic-standing/probation/expulsion decisions, degree-conferral decisions, disciplinary action, or safety-authority overrides is HARD-blocked. Legitimate `:flag-safety-concern` is never self-blocked.

**Closed :propose-only op allowlist**:
- `:schedule-enrollment-appointment` — enrollment/advising appointment scheduling, never admissions decisions.
- `:coordinate-facility-booking` — classroom/lab/facility booking logistics coordination.
- `:coordinate-supply-request` — non-instructional consumables (office/admin supplies), never instructional materials.
- `:schedule-staff-shift-proposal` — administrative shift PROPOSAL only, never teaching-qualification/assignment decisions.
- `:flag-safety-concern` — wellbeing/safety concerns (e.g., mental-health risk signals, campus safety incidents). **ALWAYS escalates**, never auto-commits.

**Staged rollout** (Phase 0→3):
- **Phase 0**: read-only.
- **Phase 1**: enrollment-appointment scheduling (approval-gated).
- **Phase 2**: + facility booking, supply, shift proposals (approval-gated).
- **Phase 3**: auto-commits clean, high-confidence proposals (safety concerns always escalate).

**Audit ledger**: append-only, every decision logged immutably.

### 3. Scope Exclusions (Higher-Ed-Specific)

Permanent scope boundaries (never negotiable in Phase 3 or later):

| Category | Out of Scope | Rationale |
|----------|-------------|-----------|
| **Academic** | Grading, assessment, performance evaluation | LLM/actor has no authority over learning outcomes. |
| **Pedagogical** | Curriculum selection, lesson planning, instructional methods | Teaching decisions require certified educators. |
| **Admissions** | Admissions decisions, acceptance/rejection determinations | Authority reserved to admissions office + institutional policy. |
| **Academic Standing** | Probation, academic disqualification, good-standing determinations | Requires formal evaluation + appeal process. |
| **Degree** | Degree conferral, diploma issuance, graduation approvals | Authority reserved to registrar + graduation committee. |
| **Disciplinary** | Suspension, expulsion, conduct sanctions | Authority reserved to student conduct office + legal process. |
| **Safety authority** | Mandatory reporting overrides, law-enforcement liaison, CPS intervention | Escalation only; actor never acts unilaterally. |

### 4. Registry Update

`kotoba-lang/industry` registry entry `853`:
- `:maturity` → `:implemented` (was `:spec`).
- `:repo` → `"https://github.com/cloud-itonami/cloud-itonami-isic-853"`.
- `:business-id` → `"cloud-itonami-isic-853"`.
- `:required-technologies` → `[:identity :forms :dmn :bpmn :audit-ledger]` (removed `:robotics`; higher education is administrative coordination, not robotics-dependent).

## Consequences

- (+) Higher education (ISIC 853) actor available for trial in cloud-itonami Wave 4 rollout.
- (+) Consistent with Wave 4 architecture (governance+ledger) and Wave 0 dependency structure (registry now reflects :implemented status).
- (+) Scope exclusions enforce hard boundary between administrative support and academic authority.
- (+) All students remain legal adults; actor respects institutional autonomy in academic decisions.
- (−) No integration with actual university systems yet (pilot phase).
- (−) No ISCO-08 occupation mapping (future work per ADR-2607121000).

## References

- ADR-2607121000: cloud-itonami global ISIC/ISCO Wave structure and rollout plan.
- ADR-2607152500: cloud-itonami Wave 4 rollout start amendment (parallel wave-0 / wave-4 execution).
- ADR-2607153700: cloud-itonami-isic-852 secondary education (direct lineage).
- ADR-2607072915: cloud-itonami-isic-851 primary education (foundational actor pattern).
- ADR-2607152700: cloud-itonami-isic-873 eldercare (similar actor pattern).
- ADR-2607051621: murakumo AGPL model + treasury.
- `kotoba-lang/industry` registry (registry.edn).
- `cloud-itonami/cloud-itonami-isic-853` GitHub repository.
