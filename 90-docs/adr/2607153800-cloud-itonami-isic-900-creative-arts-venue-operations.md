# ADR-2607153800: cloud-itonami-isic-900 — Creative Arts & Entertainment Venue Operations Coordination

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-900`, `orgs/kotoba-lang/industry` registry

## Context

ISIC Rev.4 division 90 (Creative, arts and entertainment activities) encompasses performing arts venues, production facilities, and entertainment coordination. This actor targets the administrative back-office of performing-arts venues and production companies — specifically the coordination of venue bookings, performer schedules, supply logistics, ticketing operations, and safety concerns.

Unlike creative content decisions (artistic direction, programming/curatorial choices, casting), this domain handles the **operational infrastructure only**: venue/rehearsal-space booking scheduling, administrative performer schedule coordination (call times, rehearsal logistics), non-creative consumables (front-of-house supplies, cleaning, office materials), box-office ticketing logistics, and facility/safety-concern flagging.

Implemented as part of Wave 4 (human-services & entertainment vertical) in ADR-2607121000, adapting the actor pattern established in ADR-2607152700 (ISIC 873, eldercare), with domain-adapted operations for performing-arts venue coordination.

## Decision

### 1. Repo: `cloud-itonami/cloud-itonami-isic-900`

**Lineage**: Adapted from `cloud-itonami/cloud-itonami-isic-873` (residential care) with namespace/domain changes only (`venueadminops` / `:services/performing-arts-venue-admin`). Scope exclusions and hard checks follow the same pattern — three HARD, permanent checks with no override capability.

**Public**: Yes (verified via `gh api repos/cloud-itonami/cloud-itonami-isic-900 --jq '.private'` = `false`).

**Governance**: AGPL-3.0-or-later. Same contributor guidelines, code-of-conduct, security policies as ISIC 873.

### 2. Actor Architecture

**Modules**: `venueadminops.{store, advisor, governor, phase, operation, sim}` — all `.cljc`, langgraph-clj StateGraph, one run = one operation.

**Three HARD governor checks** (permanent, un-overridable):
1. **Venue/booking record unverified**: target venue or booking must exist AND be `:registered?`/`:verified?` in the store before any proposal can commit or escalate.
2. **Effect is :propose**: any `:effect` value other than `:propose` is rejected outright.
3. **Scope exclusion**: any proposal touching creative/artistic decisions, casting, programming/curatorial choices, pricing-policy decisions, or safety-authority overrides is HARD-blocked. Legitimate `:flag-safety-concern` (facility/crowd safety) is never self-blocked.

**Closed :propose-only op allowlist**:
- `:schedule-venue-booking` — venue/rehearsal-space booking scheduling — explicitly NOT programming/curatorial decision
- `:coordinate-performer-schedule-proposal` — administrative schedule-coordination PROPOSAL only (call times, rehearsal logistics) — explicitly NOT a casting or artistic-direction decision, never binding
- `:coordinate-supply-request` — non-creative consumables (front-of-house/office supplies, cleaning) — explicitly NEVER creative/production materials (costumes, set pieces, artistic equipment) or content decisions
- `:coordinate-ticketing-logistics` — box-office/ticketing operational coordination (seat-map logistics, not pricing/programming policy)
- `:flag-safety-concern` — venue/facility safety concerns (e.g. equipment/rigging hazard, crowd-safety issue) — **ALWAYS escalates**, never auto-commits

**Staged rollout** (Phase 0→3):
- **Phase 0**: read-only.
- **Phase 1**: venue booking & performer schedule proposal (approval-gated).
- **Phase 2**: + supply coordination & ticketing logistics (approval-gated).
- **Phase 3**: auto-commits clean, high-confidence proposals (safety concerns always escalate).

**Audit ledger**: append-only, every decision logged immutably.

### 3. Scope Exclusions (HARD, Permanent)

| Category | Out of Scope | Rationale |
|----------|-------------|-----------|
| **Creative/Artistic** | Artistic decisions, creative content, performance direction | LLM/actor has no authority over artistic expression. |
| **Programming** | Curatorial choices, performance selection, lineup decisions | Programming is business/editorial authority, not coordination. |
| **Casting** | Actor/performer selection, talent decisions | Casting requires human artistic/business judgment. |
| **Pricing** | Ticket pricing policy, revenue decisions | Pricing is business policy, not operational coordination. |
| **Safety authority** | Mandatory reporting overrides, law-enforcement liaison | Escalation only; actor never acts unilaterally. |

### 4. Registry Update

`kotoba-lang/industry` registry entry `900`:
- `:maturity` → `:implemented` (was `:spec`).
- `:repo` → `"https://github.com/cloud-itonami/cloud-itonami-isic-900"`.
- `:business-id` → `"cloud-itonami-isic-900"`.
- `:required-technologies` → `[:identity :forms :dmn :bpmn :audit-ledger]` (removed `:robotics`; venue operations is administrative coordination, not robotics-dependent).

## Consequences

- (+) Creative arts & entertainment (ISIC 900) actor available for trial in cloud-itonami Wave 4 rollout.
- (+) Consistent with Wave 4 architecture (governance+ledger) and Wave 0 dependency structure (registry now reflects :implemented status).
- (+) Scope exclusions enforce hard boundary between operational support and creative/business authority.
- (−) No integration with actual venue systems yet (pilot phase).
- (−) No ISCO-08 occupation mapping (future work per ADR-2607121000).

## References

- ADR-2607121000: cloud-itonami global ISIC/ISCO Wave structure and rollout plan.
- ADR-2607152700: cloud-itonami-isic-873 eldercare (actor pattern precedent).
- ADR-2607152800: cloud-itonami-isic-861 hospital coordination (sibling ISIC).
- ADR-2607051621: murakumo AGPL model + treasury.
- `kotoba-lang/industry` registry (registry.edn).
- `cloud-itonami/cloud-itonami-isic-900` GitHub repository.
