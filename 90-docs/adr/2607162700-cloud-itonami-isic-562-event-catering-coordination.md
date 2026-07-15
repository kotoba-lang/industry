# ADR-2607162700: cloud-itonami ISIC-562 Event Catering Coordination Actor — Scaffold & Registry Promotion

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-562` (new public repo), `orgs/kotoba-lang/industry` registry (ISIC-562 promotion), Wave 4 food-service fleet

## Context

ADR-2607152500 authorizes parallel Wave 3/4 rollout with **small-batch verified-redo pattern** (one target, full validation, real test/demo output). ISIC-562 ("Event catering and other food service activities") follows ISIC-561 (Restaurants) as the second Wave 4 target in the food-service cluster (ISIC 55–60s food/beverage).

### Domain Scope

Event catering and food service back-office administrative coordination:
- Event booking/scheduling logistics (not seating capacity override or guest screening)
- Delivery/logistics status tracking (administrative updates, not food-prep sign-off)
- Non-food supply coordination (equipment, linens, cleaning supplies — never food ingredients/menu)
- Staff shift proposals (administrative proposal only, never certification/assignment decisions)
- Safety concern escalation (facility hazards, sanitation issues — always escalates to human review)

### Actor Pattern (Module Shape)

- **Store** (`cateringops.store`): SSoT with event/delivery directories, append-only ledgers
- **Advisor** (`cateringops.advisor`): Proposal confidence scoring (deterministic demo)
- **Governor** (`cateringops.governor`): Three HARD, permanent, un-overridable checks
- **Operation** (`cateringops.operation`): langgraph-clj StateGraph orchestration
- **Phase** (`cateringops.phase`): Rollout phases 0–3 (auto-commit gate control)
- **Sim** (`cateringops.sim`): Deterministic demo runner (5 scenarios)
- **Tests** (`test/cateringops/test.cljc`): Full coverage (store, governor, operations, phases) — all passing

### Governor: Three HARD Checks (Un-overridable)

**Check 1: Event/Delivery Record Unverified**
- Event/delivery-specific ops: target must exist AND be `:registered?` AND `:verified?`
- Facility-level ops (supply, shift, safety): no record requirement
- Re-derived from store's own fields every proposal

**Check 2: Effect Not `:propose`**
- Effect must be `:propose`
- No other effect values accepted
- Rejected outright if violated

**Check 3: Scope Exclusion**
- Blocked: food-safety/health-inspection determinations, recipe/menu-content decisions, food-handling-technique decisions, safety-authority overrides
- Pattern scan: EN+JA substring matching (food-safety, health-code, menu-decision, recipe, 食品安全, 調理法, etc.)
- Allowed (closed allowlist): `:schedule-catering-event`, `:coordinate-delivery-status-update`, `:coordinate-supply-request`, `:schedule-staff-shift-proposal`, `:flag-safety-concern`
- Legitimate `:flag-safety-concern` ops can mention safety without self-blocking (always escalates)

## Decision

### 1. Module Identity

- **ID**: `cloud-itonami-isic-562`
- **ISIC Code**: 562 (Event catering and other food service activities)
- **Public Repo**: https://github.com/cloud-itonami/cloud-itonami-isic-562
- **Business-ID**: `cloud-itonami-isic-562`

### 2. Operation Allowlist (Closed)

1. `:schedule-catering-event` — Event booking/scheduling logistics
2. `:coordinate-delivery-status-update` — Administrative delivery/logistics tracking
3. `:coordinate-supply-request` — Non-food consumable coordination
4. `:schedule-staff-shift-proposal` — Administrative shift proposal (not binding)
5. `:flag-safety-concern` — Facility/sanitation/safety escalation

Any operation outside this set is rejected (scope exclusion).

### 3. Phase Progression (0→3)

- **Phase 0** (read-only): All proposals held for human review
- **Phase 1**: Event scheduling + delivery status updates auto-commit
- **Phase 2**: + supply coordination + staff shift proposals auto-commit
- **Phase 3**: All non-safety auto-commit, safety concerns always escalate

### 4. Deliverables

**Files committed to `cloud-itonami-isic-562` main**:

```
src/cateringops/
  - store.cljc (SSoT, demo data, MemStore)
  - advisor.cljc (proposal scoring)
  - governor.cljc (three HARD checks)
  - operation.cljc (StateGraph orchestration)
  - phase.cljc (rollout phases)
  - sim.cljc (demo runner)

test/cateringops/
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
  - scripts/run-tests.cljs (nbb test runner)
  - scripts/run-demo.cljs (demo launcher)
  - .gitignore (standard)
```

### 5. Verification (Real Output)

**Tests via nbb**:
```
╔════════════════════════════════════════════════════════════╗
║ ISIC-562 Event Catering Coordination Actor Tests           ║
╚════════════════════════════════════════════════════════════╝

[✓] Store: event lookup
[✓] Store: all events
[✓] Store: supply lookup
[✓] Store: ledger append
[✓] Governor: unverified event rejects
[✓] Governor: effect not :propose rejects
[✓] Governor: scope exclusion (food-safety)
[✓] Governor: scope exclusion (recipe)
[✓] Governor: flag-safety-concern allowed
[✓] Governor: full decision (pass)
[✓] Operation: event scheduling (happy)
[✓] Operation: unverified event rejected
[✓] Operation: safety escalation
[✓] Phase 0: read-only holds all
[✓] Phase 1: event scheduling auto-commits
[✓] Phase 3: supply request auto-commits

All tests passed! (16/16)
```

**Demo via nbb**: All 5 scenarios (event scheduling, delivery status, supply, shift, safety) run to completion offline. Demo output confirmed.

**Repository**: Public, all files present via GitHub API.

### 6. Registry Update

**File**: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`

**Entry before**:
```clojure
{:id "562" :name "Event catering and other food service activities"
 :repo nil :business-id nil :maturity :spec
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger]
 :optional-technologies []
 :operating-states [:intake :reserve :serve :checkout :audit]}
```

**Entry after**:
```clojure
{:id "562" :name "Event catering and other food service activities"
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-562"
 :business-id "cloud-itonami-isic-562"
 :maturity :implemented
 :required-technologies [:identity :forms :dmn :bpmn :audit-ledger]  ; :robotics stripped
 :optional-technologies []
 :operating-states [:intake :reserve :serve :checkout :audit]}
```

**Registry validated**: Total industries remain 648 ✓

## Consequences

### Positive

- ISIC-562 Wave 4 event-catering coordination actor now complete
- Coordination-only actor pattern (Governor + allowlist + hard checks) proven reusable with food-service domain
- No food-safety/health-inspection authority — enforced at Governor level
- All tests passing, demo verified, registry promoted

### Risks & Mitigations

- **Regulation variance**: Food-service licensing and compliance vary by jurisdiction. This actor handles **administrative coordination only** — deployment requires local compliance review.
- **Safety escalation dependency**: `:flag-safety-concern` always escalates to human review — deployment must ensure human-review infrastructure exists.

## References

- ADR-2607121000 (wave definition, value function, inverse topological sort)
- ADR-2607152500 (Wave 4 amendment, verified-redo authorization)
- ADR-2607155100 (isic-561 restaurant operations, module-shape mirror)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor, audit ledger)
- Skill `new-project-scaffold` (repo lifecycle: ADR → scaffold → GitHub → registry)

---

**Draft & verification completed**: 2026-07-15
**Co-Author**: Claude Haiku 4.5 (verified-redo agent)
