# ADR-2607153900: cloud-itonami-isic-855 — Educational Support Activities Actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-855`, `orgs/kotoba-lang/industry` registry

## Context

Educational support activities (ISIC Rev.4 code 855) encompasses services adjacent to but distinct from educational provision itself: educational testing/assessment-administration services, educational consulting, student-exchange organization, and instructional materials provisioning. This actor focuses narrowly on **standardized test administration logistics** — the clearest, most administratively bounded subdomain with minimal pedagogical complexity.

The actor coordinates logistical operations only: test-session scheduling, proctor-assignment proposals, consumables coordination (answer sheets, pencils, timers), attendance check-in logging, and integrity-concern flagging. It never touches test-content decisions, grading/scoring, eligibility/accommodation determinations, academic policies, or safety-authority overrides.

Implemented as part of Wave 4 (human-services vertical) in ADR-2607121000, following the actor pattern established in ADR-2607152900 (ISIC 851, primary education), ADR-2607153700 (ISIC 852, secondary education), ADR-2607153800 (ISIC 853, higher education), and ADR-2607153800 (ISIC 854, adult vocational education).

## Decision

### 1. Repo: `cloud-itonami/cloud-itonami-isic-855`

**Lineage**: Adapted from `cloud-itonami/cloud-itonami-isic-854` (adult vocational education) with namespace/domain changes only (`testadmn` / `:services/test-administration-logistics`). Scope exclusions and hard checks follow the identical structure.

**Public**: Yes (GitHub org setting `cloud-itonami` defaults to private per `repos.edn`, but this repo was created with `--public` flag explicitly).

**Governance**: AGPL-3.0-or-later. Contributor guidelines and security policies match 851–854.

### 2. Actor Architecture

**Modules**: `testadmn.{store, advisor, governor, phase, operation, sim}` — all `.cljc`, langgraph-clj StateGraph, one run = one operation.

**Three HARD governor checks** (permanent, un-overridable):
1. **Test-session/candidate-record verified** — target session/test record must exist AND be `:registered?`/`:verified?` in the store, re-derived every operation.
2. **Effect is :propose** — any `:effect` value other than `:propose` is rejected outright.
3. **Scope exclusion** — any proposal (any op) or effect touching: test-content/answer-key access, scoring/grading decisions, eligibility/accommodation/academic-policy decisions, academic-integrity adjudication (as opposed to merely flagging a concern), or safety-authority overrides — is EN+JA substring-scanned and rejected. Legitimate `:flag-safety-concern` (concern about a possible integrity incident, facility hazard, or student wellbeing) is never self-blocked.

**Closed :propose-only op allowlist**:
- `:schedule-test-session` — exam session scheduling/room logistics — explicitly NOT a decision about which test a student takes or eligibility.
- `:coordinate-proctor-assignment-proposal` — administrative proctor-assignment PROPOSAL only, never binding, never a proctor-qualification decision.
- `:coordinate-supply-request` — non-content consumables (answer sheets, pencils, timers) — explicitly NEVER test-content materials or answer keys.
- `:log-attendance-note` — test-session attendance/check-in logging — explicitly NOT a scoring or eligibility record.
- `:flag-safety-concern` — facility/integrity/wellbeing concerns (e.g., suspected cheating incident, facility hazard) — **ALWAYS escalates**, never auto-commits.

**Staged rollout** (Phase 0→3):
- **Phase 0**: read-only.
- **Phase 1**: `:schedule-test-session` (approval-gated).
- **Phase 2**: + `:coordinate-proctor-assignment-proposal`, `:coordinate-supply-request` (approval-gated).
- **Phase 3**: auto-commits clean, high-confidence proposals; safety concerns always escalate.

**Audit ledger**: append-only, every decision logged immutably.

### 3. Scope Exclusions

Permanent scope boundaries (never negotiable):

| Category | Out of Scope | Rationale |
|----------|-------------|-----------|
| **Test content** | Test content, answer keys, scoring rubrics | Actor has no authority over assessment design. |
| **Grading/scoring** | Score calculation, performance evaluation, pass/fail decisions | Pedagogical authority reserved to educators. |
| **Eligibility** | Accommodation decisions, special-education placements, prerequisite waivers | Requires formal evaluation + legal process. |
| **Academic policy** | Curriculum decisions, test format changes, timing adjustments | Policy decisions require institutional authority. |
| **Academic integrity** | Unilateral adjudication, sanctions, disciplinary action | Actor flags concerns only; adjudication reserved to authorities. |
| **Safety authority** | Mandatory reporting overrides, law-enforcement liaison, CPS intervention | Escalation only; actor never acts unilaterally. |

### 4. Registry Update

`kotoba-lang/industry` registry entry `855`:
- `:maturity` → `:implemented` (was `:spec`).
- `:repo` → `"https://github.com/cloud-itonami/cloud-itonami-isic-855"`.
- `:business-id` → `"cloud-itonami-isic-855"`.
- `:required-technologies` → `[:identity :forms :dmn :bpmn :audit-ledger]` (removed `:robotics`; test administration is administrative coordination, not robotics-dependent).

## Consequences

- (+) Educational support activities (ISIC 855) actor available for trial in cloud-itonami Wave 4 rollout.
- (+) Completes Wave 4 education cluster (851/852/853/854/855 all `:implemented`).
- (+) Consistent with Wave 4 architecture (governance+ledger) and Wave 0 dependency structure (registry now reflects :implemented status).
- (+) Scope exclusions enforce hard boundary between administrative support and academic/pedagogical authority.
- (−) No integration with actual testing platforms yet (pilot phase).
- (−) No ISCO-08 occupation mapping (future work per ADR-2607121000).

## References

- ADR-2607121000: cloud-itonami global ISIC/ISCO Wave structure and rollout plan.
- ADR-2607152900: cloud-itonami-isic-851 primary education.
- ADR-2607153700: cloud-itonami-isic-852 secondary education.
- ADR-2607153800: cloud-itonami-isic-853 higher education (and 854 adult vocational).
- ADR-2607051621: murakumo AGPL model + treasury.
- `kotoba-lang/industry` registry (registry.edn).
- `cloud-itonami/cloud-itonami-isic-855` GitHub repository.
