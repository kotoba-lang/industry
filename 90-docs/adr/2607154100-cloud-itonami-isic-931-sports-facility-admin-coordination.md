# ADR-2607154100: cloud-itonami-isic-931 — Sports Facility & League Administrative Coordination

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-931`, `orgs/kotoba-lang/industry` registry

## Context

ISIC Rev.4 division 931 (Sports activities) encompasses sports clubs, facilities, and league operations. This actor targets the administrative back-office of sports facilities, leagues, and clubs — specifically the coordination of facility/court bookings, team roster logistics (administrative only), equipment/supply coordination, event logistics coordination, and safety concerns.

Unlike coaching decisions (athlete selection, tactical instruction, training methodology), this domain handles the **operational infrastructure only**: facility/court booking scheduling, administrative roster-logistics PROPOSAL (which athletes are registered for sessions, not lineup/selection decisions), non-competitive consumables (office supplies, facility maintenance), event-day coordination, and facility/player-safety-concern flagging.

Implemented as part of Wave 4 (human-services & entertainment vertical) in ADR-2607121000, adapting the actor pattern established in ADR-2607152700 (ISIC 873, eldercare), with domain-adapted operations for sports facility/league coordination.

## Decision

### 1. Repo: `cloud-itonami/cloud-itonami-isic-931`

**Lineage**: Adapted from `cloud-itonami/cloud-itonami-isic-900` (performing-arts venue operations) with namespace/domain changes only (`sportsleagueadminops` / `:services/sports-facility-league-admin`). Scope exclusions and hard checks follow the same pattern — three HARD, permanent checks with no override capability.

**Public**: Yes (verified via `gh api repos/cloud-itonami/cloud-itonami-isic-931 --jq '.private'` = `false`).

**Governance**: AGPL-3.0-or-later. Same contributor guidelines, code-of-conduct, security policies as ISIC 900 and ISIC 873.

### 2. Actor Architecture

**Modules**: `sportsleagueadminops.{store, advisor, governor, phase, operation, sim}` — all `.cljc`, langgraph-clj StateGraph, one run = one operation.

**Three HARD governor checks** (permanent, un-overridable):
1. **Facility/booking record unverified**: target facility or booking must exist AND be `:registered?`/`:verified?` in the store before any proposal can commit or escalate.
2. **Effect is :propose**: any `:effect` value other than `:propose` is rejected outright.
3. **Scope exclusion**: any proposal touching coaching/athlete-selection/competitive-scheduling/disciplinary/pricing/safety-authority territory is HARD-blocked. Legitimate `:flag-safety-concern` (facility/player safety) is never self-blocked.

**Closed :propose-only op allowlist**:
- `:schedule-facility-booking` — facility/court booking scheduling — explicitly NOT competitive scheduling/seeding
- `:coordinate-team-roster-logistics-proposal` — administrative roster-logistics PROPOSAL only (which players are registered for a session, not selection/lineup/coaching decisions) — never binding, never a coaching decision
- `:coordinate-supply-request` — non-competitive consumables (office/facility supplies, cleaning) — explicitly NEVER competitive equipment or performance-affecting materials
- `:coordinate-event-logistics` — event-day operational coordination (parking, check-in, not rules/officiating)
- `:flag-safety-concern` — facility/player-safety concerns (e.g. equipment hazard, injury report) — **ALWAYS escalates**, never auto-commits

**Staged rollout** (Phase 0→3):
- **Phase 0**: read-only.
- **Phase 1**: facility booking & roster logistics proposal (approval-gated).
- **Phase 2**: + supply coordination & event logistics (approval-gated).
- **Phase 3**: auto-commits clean, high-confidence proposals (safety concerns always escalate).

**Audit ledger**: append-only, every decision logged immutably.

### 3. Scope Exclusions (HARD, Permanent)

| Category | Out of Scope | Rationale |
|----------|-------------|-----------|
| **Coaching** | Coaching decisions, athlete instruction, training methodology | LLM/actor has no authority over coaching practice. |
| **Athlete Selection** | Athlete selection, lineup decisions, eligibility determination | Selection requires human coaching/sports authority judgment. |
| **Competitive Scheduling** | Competition seeding, bracket decisions, scheduling with competitive intent | Competitive decisions are sports-authority territory. |
| **Disciplinary Action** | Discipline enforcement, eligibility enforcement, penalty determination | Discipline is sports-authority territory. |
| **Pricing** | Ticket/membership pricing policy, revenue decisions | Pricing is business policy, not operational coordination. |
| **Safety Authority** | Injury investigation, license enforcement, mandatory reporting overrides | Escalation only; actor never acts unilaterally. |

### 4. Registry Update

`kotoba-lang/industry` registry entry `931`:
- `:maturity` → `:implemented` (was `:spec`).
- `:repo` → `"https://github.com/cloud-itonami/cloud-itonami-isic-931"`.
- `:business-id` → `"cloud-itonami-isic-931"`.
- `:required-technologies` → `[:identity :forms :dmn :bpmn :audit-ledger]` (removed `:robotics`; sports facility operations is administrative coordination, not robotics-dependent).

## Consequences

- (+) Sports facility & league (ISIC 931) actor available for trial in cloud-itonami Wave 4 rollout.
- (+) Consistent with Wave 4 architecture (governance+ledger) and Wave 0 dependency structure (registry now reflects :implemented status).
- (+) Scope exclusions enforce hard boundary between operational support and coaching/athletic authority.
- (−) No integration with actual league/facility management systems yet (pilot phase).
- (−) No ISCO-08 occupation mapping (future work per ADR-2607121000).

## References

- ADR-2607121000: cloud-itonami global ISIC/ISCO Wave structure and rollout plan.
- ADR-2607152500: cloud-itonami-wave4-rollout-start-amendment (this batch context).
- ADR-2607153800: cloud-itonami-isic-900 performing-arts venue (actor pattern precedent).
- ADR-2607152700: cloud-itonami-isic-873 eldercare (sibling ISIC).
