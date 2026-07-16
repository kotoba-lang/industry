# ADR-2607153700: cloud-itonami-isic-852 — Secondary Education Actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-852`, `orgs/kotoba-lang/industry` registry

## Context

Secondary education (ISIC Rev.4 class 852) serves middle and high school students aged ~11–19. Unlike primary education (ISIC 851), secondary students have greater cognitive autonomy and peer-based social dynamics, yet remain minors requiring parental/guardian oversight. This actor coordinates administrative operations only — attendance logging, parent/guardian meeting scheduling, supply coordination, staff shift proposals, and safety-concern flagging — never academic grading, curriculum decisions, disciplinary action, or special-education determinations.

Implemented as part of Wave 4 (human-services vertical) in ADR-2607121000, following the actor pattern established in ADR-2607072915 (ISIC 851, primary education) and ADR-2607152700 (ISIC 873, eldercare).

## Decision

### 1. Repo: `cloud-itonami/cloud-itonami-isic-852`

**Lineage**: Adapted from `cloud-itonami/cloud-itonami-isic-851` (primary education) with namespace/domain changes only (`secondaryops` / `:services/secondary-education`). Scope exclusions and hard checks remain identical to 851 (minors; parents/guardians responsible for consent).

**Public**: Yes (org default `cloud-itonami` → private in `repos.edn` `:orgs :visibility`, but verified as private in GitHub).

**Governance**: AGPL-3.0-or-later. Same contributor guidelines, code-of-conduct, security policies as 851.

### 2. Actor Architecture

**Modules**: `secondaryops.{store, advisor, governor, phase, operation, sim}` — all `.cljc`, langgraph-clj StateGraph, one run = one operation.

**Three HARD governor checks** (permanent, un-overridable):
1. **Student verified**: target student must exist AND be `:registered?`/`:verified?` in the store.
2. **Effect is :propose**: any `:effect` value other than `:propose` is rejected outright.
3. **Scope exclusion**: any proposal touching grading/academic-assessment, curriculum/pedagogical, disciplinary action/suspension/expulsion, special-education/IEP, custody/guardianship, or safety-authority overrides is HARD-blocked. Legitimate `:flag-safety-concern` is never self-blocked.

**Closed :propose-only op allowlist**:
- `:log-attendance-note` — routine attendance/logistics, never academic performance assessment.
- `:schedule-parent-guardian-meeting` — coordination of parent/guardian meetings.
- `:coordinate-supply-request` — non-instructional consumables (classroom/cafeteria/office supplies), never curriculum materials.
- `:schedule-staff-shift-proposal` — administrative shift PROPOSAL only, never teaching-qualification/assignment decisions.
- `:flag-safety-concern` — wellbeing/safeguarding concerns (suspected abuse/neglect, bullying, cyberbullying, substance abuse, self-harm risk signals). **ALWAYS escalates**, never auto-commits.

**Staged rollout** (Phase 0→3):
- **Phase 0**: read-only.
- **Phase 1**: attendance logging (approval-gated).
- **Phase 2**: + parent meeting, supply, shift proposals (approval-gated).
- **Phase 3**: auto-commits clean, high-confidence proposals (safety concerns always escalate).

**Audit ledger**: append-only, every decision logged immutably.

### 3. Scope Exclusions (Identical to ISIC 851)

Permanent scope boundaries (never negotiable in Phase 3 or later):

| Category | Out of Scope | Rationale |
|----------|-------------|-----------|
| **Academic** | Grading, assessment, performance evaluation | LLM/actor has no authority over learning outcomes. |
| **Pedagogical** | Curriculum selection, lesson planning, instructional methods | Teaching decisions require certified educators. |
| **Disciplinary** | Suspension, expulsion, behavioral sanctions | Authority reserved to school leadership + legal process. |
| **Special needs** | IEP modifications, special-education placement | Requires formal evaluation + parent consent. |
| **Custodial** | Custody/guardianship decisions, family-court involvement | Exclusive to courts. |
| **Safety authority** | Mandatory reporting overrides, law-enforcement liaison, CPS intervention | Escalation only; actor never acts unilaterally. |

### 4. Registry Update

`kotoba-lang/industry` registry entry `852`:
- `:maturity` → `:implemented` (was `:spec`).
- `:repo` → `"https://github.com/cloud-itonami/cloud-itonami-isic-852"`.
- `:business-id` → `"cloud-itonami-isic-852"`.
- `:required-technologies` → `[:identity :forms :dmn :bpmn :audit-ledger]` (removed `:robotics`; secondary education is administrative coordination, not robotics-dependent).

## Consequences

- (+) Secondary education (ISIC 852) actor available for trial in cloud-itonami Wave 4 rollout.
- (+) Consistent with Wave 4 architecture (governance+ledger) and Wave 0 dependency structure (registry now reflects :implemented status).
- (+) Scope exclusions enforce hard boundary between administrative support and pedagogical authority.
- (−) No integration with actual school systems yet (pilot phase).
- (−) No ISCO-08 occupation mapping (future work per ADR-2607121000).

## References

- ADR-2607121000: cloud-itonami global ISIC/ISCO Wave structure and rollout plan.
- ADR-2607152700: cloud-itonami-isic-873 eldercare (similar actor pattern).
- ADR-2607072915: cloud-itonami-isic-851 primary education (direct lineage).
- ADR-2607051621: murakumo AGPL model + treasury.
- `kotoba-lang/industry` registry (registry.edn).
- `cloud-itonami/cloud-itonami-isic-852` GitHub repository.
