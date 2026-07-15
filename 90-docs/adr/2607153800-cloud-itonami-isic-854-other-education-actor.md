# ADR-2607153800: cloud-itonami-isic-854 — Other Education Actor (Adult Vocational & Continuing Education)

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-854`, `orgs/kotoba-lang/industry` registry

## Context

Other education (ISIC Rev.4 class 854) covers educational institutions and programs not classified elsewhere: driving schools, language schools, exam-prep and tutoring centers, and vocational/trade skill courses for adults. This actor focuses narrowly on **adult continuing-education and vocational-skills courses** — the clearest administrative coordination scope, with minimal child-safeguarding complexity compared to primary (851) and secondary (852) education.

This actor coordinates administrative operations only: course enrollment/registration scheduling, facility booking logistics, supply coordination (training-room consumables), instructor shift proposals, and safety-concern flagging. It never touches course content decisions, instructional-materials selection, tutor/instructor competency/certification determinations, learner progress/outcomes, disciplinary action, or any safety-authority overrides.

Implemented as part of Wave 4 (human-services vertical) in ADR-2607121000, following the actor pattern established in ADR-2607072915 (ISIC 851, primary education) and ADR-2607153700 (ISIC 852, secondary education).

## Decision

### 1. Repo: `cloud-itonami/cloud-itonami-isic-854`

**Lineage**: Adapted from `cloud-itonami/cloud-itonami-isic-852` (secondary education) with namespace/domain changes only (`vocationalops` / `:services/adult-vocational-education`). Scope exclusions and hard checks remain identical structure (students → enrollees, adapted for adult learners in vocational/continuing-ed context).

**Public**: No (org default `cloud-itonami` → private in `repos.edn` `:orgs :visibility`, per ADR-2607021330).

**Governance**: AGPL-3.0-or-later. Same contributor guidelines, code-of-conduct, security policies as 852.

### 2. Actor Architecture

**Modules**: `vocationalops.{store, advisor, governor, phase, operation, sim}` — all `.cljc`, langgraph-clj StateGraph, one run = one operation.

**Three HARD governor checks** (permanent, un-overridable):
1. **Enrollee verified**: target enrollee must exist AND be `:registered?`/`:verified?` in the store.
2. **Effect is :propose**: any `:effect` value other than `:propose` is rejected outright.
3. **Scope exclusion**: any proposal touching instructional-content decisions, curriculum/pedagogy, tutor-competency/certification determinations, learner-progress or completion decisions, disciplinary action, or safety-authority overrides is HARD-blocked. Legitimate `:flag-safety-concern` is never self-blocked.

**Closed :propose-only op allowlist**:
- `:schedule-course-enrollment` — course enrollment/registration scheduling logistics — explicitly NOT an admissions-eligibility or prerequisite-waiver decision.
- `:coordinate-facility-booking` — classroom/training-room/equipment booking logistics.
- `:coordinate-supply-request` — non-instructional consumables (office/training-room supplies) — explicitly NEVER instructional materials selection.
- `:schedule-instructor-shift-proposal` — administrative instructor-shift PROPOSAL only, never binding, never a teaching-qualification/certification decision.
- `:flag-safety-concern` — facility/wellbeing safety concerns — **ALWAYS escalates**, never auto-commits.

**Staged rollout** (Phase 0→3):
- **Phase 0**: read-only.
- **Phase 1**: enrollment scheduling (approval-gated).
- **Phase 2**: + facility booking, supply, instructor shift proposals (approval-gated).
- **Phase 3**: auto-commits clean, high-confidence proposals (safety concerns always escalate).

**Audit ledger**: append-only, every decision logged immutably.

### 3. Scope Exclusions (Distinct from Primary/Secondary Education)

Permanent scope boundaries (never negotiable in Phase 3 or later):

| Category | Out of Scope | Rationale |
|----------|-------------|-----------|
| **Instructional** | Course content, curriculum, lesson/training plan | Adult learner autonomy; tutor/educator has curricular authority. |
| **Competency** | Tutor/instructor qualification, certification, assignment | Authority reserved to course provider / professional bodies. |
| **Learner Progress** | Learner assessment, grading, completion/certification decisions, prerequisites waiver | Requires professional judgment of instructors/evaluators. |
| **Disciplinary** | Learner suspension/expulsion, conduct sanctions, enrollment termination | Authority reserved to course provider leadership. |
| **Safety authority** | Mandatory reporting overrides, law-enforcement liaison | Escalation only; actor never acts unilaterally. |

### 4. Registry Update

`kotoba-lang/industry` registry entry `854`:
- `:maturity` → `:implemented` (was `:spec`).
- `:repo` → `"https://github.com/cloud-itonami/cloud-itonami-isic-854"`.
- `:business-id` → `"cloud-itonami-isic-854"`.
- `:required-technologies` → `[:identity :forms :dmn :bpmn :audit-ledger]` (removed `:robotics`; adult vocational education is administrative coordination, not robotics-dependent).

## Consequences

- (+) Other education (ISIC 854) actor available for trial in cloud-itonami Wave 4 rollout.
- (+) Consistent with Wave 4 architecture (governance+ledger) and Wave 0 dependency structure (registry now reflects :implemented status).
- (+) Scope exclusions enforce hard boundary between administrative support and instructional/competency authority.
- (+) Narrow focus on adult continuing education avoids child-safeguarding complexity of primary/secondary.
- (−) No integration with actual vocational training providers yet (pilot phase).
- (−) No ISCO-08 occupation mapping (future work per ADR-2607121000).

## References

- ADR-2607121000: cloud-itonami global ISIC/ISCO Wave structure and rollout plan.
- ADR-2607153700: cloud-itonami-isic-852 secondary education (direct lineage).
- ADR-2607072915: cloud-itonami-isic-851 primary education (pattern reference).
- ADR-2607051621: murakumo AGPL model + treasury.
- `kotoba-lang/industry` registry (registry.edn).
- `cloud-itonami/cloud-itonami-isic-854` GitHub repository.
