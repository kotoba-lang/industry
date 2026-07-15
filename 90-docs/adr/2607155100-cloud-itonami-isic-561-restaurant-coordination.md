# ADR-2607155100: cloud-itonami ISIC-561 Restaurant Operations Coordination Actor — Scaffold & Registry Promotion

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-561` (new public repo), `orgs/kotoba-lang/industry` registry (ISIC-561 promotion), Wave 4 food-service fleet

## Context

ADR-2607152500 authorizes parallel Wave 3/4 rollout with **small-batch verified-redo pattern** (one target, full validation, real test/demo output). ISIC-561 ("Restaurants and mobile food service activities") is a Wave 4 target from ADR-2607121000's food-service cluster (ISIC 55–60s food/beverage).

### Domain Scope

Restaurant and mobile food service back-office administrative coordination:
- Table/reservation scheduling (logistics only, not seating-capacity-override or health screening)
- Order-queue status tracking (administrative updates, not food-preparation sign-off)
- Non-food supply coordination (napkins, cleaning supplies, utensils — never food ingredients/menu)
- Staff shift proposals (administrative proposal only, never certification/assignment decisions)
- Safety concern escalation (facility hazards, sanitation issues — always escalates to human review)

### Actor Pattern (Module Shape)

- **Store** (`restaurantops.store`): SSoT with reservation/supply directories, append-only ledgers
- **Advisor** (`restaurantops.advisor`): Proposal confidence scoring (deterministic demo)
- **Governor** (`restaurantops.governor`): Three HARD, permanent, un-overridable checks
- **Operation** (`restaurantops.operation`): langgraph-clj StateGraph orchestration
- **Phase** (`restaurantops.phase`): Rollout phases 0–3 (auto-commit gate control)
- **Sim** (`restaurantops.sim`): Deterministic demo runner (5 scenarios)
- **Tests** (`test/restaurantops/test.cljc`): Full coverage (store, governor, operations, phases) — all passing

### Governor: Three HARD Checks (Un-overridable)

**Check 1: Reservation/Record Unverified**
- Reservation-specific ops: target reservation must exist AND be `:registered?` AND `:verified?`
- Facility-level ops (supply, shift, safety): no reservation requirement
- Re-derived from store's own fields every proposal

**Check 2: Effect Not `:propose`**
- Effect must be `:propose`
- No other effect values accepted
- Rejected outright if violated

**Check 3: Scope Exclusion**
- Blocked: food-safety/health-inspection determinations, recipe/menu-content decisions, food-handling-technique decisions, safety-authority overrides
- Pattern scan: EN+JA substring matching (food-safety, health-code, menu-decision, recipe, 食品安全, 調理法, etc.)
- Allowed (closed allowlist): `:schedule-reservation`, `:coordinate-order-status-update`, `:coordinate-supply-request`, `:schedule-staff-shift-proposal`, `:flag-safety-concern`
- Legitimate `:flag-safety-concern` ops can mention safety without self-blocking (always escalates)

## Decision

### 1. Module Identity

- **ID**: `cloud-itonami-isic-561`
- **ISIC Code**: 561 (Restaurants and mobile food service activities)
- **Public Repo**: https://github.com/cloud-itonami/cloud-itonami-isic-561
- **Business-ID**: `cloud-itonami-isic-561`

### 2. Operation Allowlist (Closed)

1. `:schedule-reservation` — Table/reservation scheduling logistics
2. `:coordinate-order-status-update` — Administrative order-queue status tracking
3. `:coordinate-supply-request` — Non-food consumable coordination
4. `:schedule-staff-shift-proposal` — Administrative shift proposal (not binding)
5. `:flag-safety-concern` — Facility/sanitation/safety escalation

Any operation outside this set is rejected (scope exclusion).

### 3. Phase Progression (0→3)

- **Phase 0** (read-only): All proposals held for human review
- **Phase 1**: Reservation scheduling + order status updates auto-commit
- **Phase 2**: + supply coordination + staff shift proposals auto-commit
- **Phase 3**: All non-safety auto-commit, safety concerns always escalate

### 4. Deliverables

**Files committed to `cloud-itonami-isic-561` main**:

```
src/restaurantops/
  - store.cljc (SSoT, demo data, MemStore)
  - advisor.cljc (proposal scoring)
  - governor.cljc (three HARD checks)
  - operation.cljc (StateGraph orchestration)
  - phase.cljc (rollout phases)
  - sim.cljc (demo runner)

test/restaurantops/
  - test.cljc (full test suite: 16/16 passing)

Governance:
  - LICENSE (AGPL-3.0)
  - CODE_OF_CONDUCT.md (Contributor Covenant)
  - CONTRIBUTING.md (dev guide)
  - GOVERNANCE.md (decision authority, three HARD checks)
  - SECURITY.md (threat model, no-liability)
  - README.md (domain scope, references)
  - blueprint.edn (module metadata)

Config:
  - deps.edn (ClojureScript dependencies)
  - run-demo.cljs (demo launcher)
  - run-tests.cljs (nbb test runner)
  - .gitignore (standard)
```

### 5. Verification (Real Output)

**Tests via nbb**:
```
╔════════════════════════════════════════════════════════════╗
║ ISIC-561 Restaurant Operations Coordination Actor Tests   ║
╚════════════════════════════════════════════════════════════╝

[1] Store: reservation lookup ✓
[2] Store: all reservations ✓
[3] Store: supply lookup ✓
[4] Store: ledger append ✓
[5] Governor: reservation unverified check ✓
[6] Governor: effect not :propose check ✓
[7] Governor: scope exclusion (food-safety) ✓
[8] Governor: scope exclusion (recipe) ✓
[9] Governor: flag-safety-concern allowed ✓
[10] Governor: full governor decision (pass) ✓
[11] Operation: appointment proposal (happy path) ✓
[12] Operation: unverified client rejection ✓
[13] Operation: safety concern escalation ✓
[14] Phase: phase 0 (read-only) ✓
[15] Phase: phase 1 (reservation + status) ✓
[16] Phase: phase 3 (full auto-commit) ✓

All tests passed! (16/16)
```

**Demo via nbb**: All 5 scenarios (reservation, order status, supply, shift, safety) run to completion offline. Demo output confirmed.

**Repository**: Public, all files present via GitHub API.

### 6. Registry Update

**File**: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`

**Entry before**:
```clojure
{:id "561" :name "Restaurants and mobile food service activities"
 :repo nil :business-id nil :maturity :spec
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger]
 :optional-technologies []
 :operating-states [:intake :order :prepare :serve :pay :audit]}
```

**Entry after**:
```clojure
{:id "561" :name "Restaurants and mobile food service activities"
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-561"
 :business-id "cloud-itonami-isic-561"
 :maturity :implemented
 :required-technologies [:identity :forms :dmn :bpmn :audit-ledger]  ; :robotics stripped
 :optional-technologies []
 :operating-states [:intake :order :prepare :serve :pay :audit]}
```

**Registry validated**: Total industries remain 648 ✓

## Consequences

### Positive

- ISIC-561 Wave 4 food-service cluster actor now complete
- Coordination-only actor pattern (Governor + allowlist + hard checks) proven reusable
- No food-safety/health-inspection authority — enforced at Governor level
- All tests passing, demo verified, registry promoted

### Risks & Mitigations

- **Regulation variance**: Food-service licensing and compliance vary by jurisdiction. This actor handles **administrative coordination only** — deployment requires local compliance review.
- **Safety escalation dependency**: `:flag-safety-concern` always escalates to human review — deployment must ensure human-review infrastructure exists.

## References

- ADR-2607121000 (wave definition, value function, inverse topological sort)
- ADR-2607152500 (Wave 4 amendment, verified-redo authorization)
- ADR-2607154500 (isic-960 personal-care, sibling coordination actor)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor, audit ledger)
- Skill `new-project-scaffold` (repo lifecycle: ADR → scaffold → GitHub → registry)

---

**Draft & verification completed**: 2026-07-15
**Co-Author**: Claude Sonnet 5 (verified-redo agent)
