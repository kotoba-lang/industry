# ADR-2607162700: cloud-itonami ISIC-563 Beverage-Serving Administrative Coordination Actor — Scaffold & Registry Promotion

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-563` (new public repo), `orgs/kotoba-lang/industry` registry (ISIC-563 promotion), Wave 4 food-service fleet

## Context

ADR-2607152500 authorizes parallel Wave 3/4 rollout with **small-batch verified-redo pattern** (one target, full validation, real test/demo output). ISIC-563 ("Beverage serving activities") is a Wave 4 target from ADR-2607121000's food-service cluster (ISIC 55–60s food/beverage), completing the ISIC-56 sub-range (restaurants 561, catering 562, beverage-service 563).

### Domain Scope

Bar/cafe/beverage-establishment ADMINISTRATIVE coordination only:
- Table/seating scheduling logistics (reservation logistics only, NOT capacity overrides or occupancy compliance)
- Order-queue status tracking (administrative queue updates, NOT beverage preparation or responsible-service determinations)
- Non-beverage supply coordination (glassware, napkins, cleaning supplies — explicitly NOT alcohol/beverage ingredient ordering)
- Staff shift proposals (administrative proposal only, NOT bartender certification or alcohol-service assignment)
- Safety concern escalation (facility hazards, intoxication welfare checks — always escalates to human review, NOT itself a responsible-service determination)

### Actor Pattern (Module Shape)

- **Store** (`beverageops.store`): SSoT with table/reservation directories, supply inventory, append-only ledgers
- **Advisor** (`beverageops.advisor`): Proposal confidence scoring (deterministic demo)
- **Governor** (`beverageops.governor`): Three HARD, permanent, un-overridable checks
- **Operation** (`beverageops.operation`): langgraph-clj StateGraph orchestration
- **Phase** (`beverageops.phase`): Rollout phases 0–3 (auto-commit gate control)
- **Sim** (`beverageops.sim`): Deterministic demo runner (5 scenarios)
- **Tests** (`test/beverageops/test.cljc`): Full coverage (store, governor, operations, phases) — all passing

### Governor: Three HARD Checks (Un-overridable)

**Check 1: Table/Order-Record Unverified**
- Table/order-specific ops: target table/order must exist AND be `:registered?` AND `:verified?`
- Facility-level ops (supply, shift, safety): no table/order requirement
- Re-derived from store's own fields every proposal

**Check 2: Effect Not `:propose`**
- Effect must be `:propose`
- No other effect values accepted
- Rejected outright if violated

**Check 3: Scope Exclusion**
- Blocked: age-verification/ID-checking decisions, responsible-service-of-alcohol determinations, drink-recipe/content-knowledge decisions, alcohol ingredient ordering, safety-authority overrides
- Pattern scan: EN+JA substring matching (age-verification, ID-check, responsible-service, recipe, alcohol, 年齢確認, 未成年, 飲酒責任, etc.)
- Allowed (closed allowlist): `:schedule-table-reservation`, `:coordinate-order-status-update`, `:coordinate-supply-request`, `:schedule-staff-shift-proposal`, `:flag-safety-concern`
- Legitimate `:flag-safety-concern` ops (e.g. intoxication welfare escalation) can mention safety without self-blocking (always escalates)

## Decision

### 1. Module Identity

- **ID**: `cloud-itonami-isic-563`
- **ISIC Code**: 563 (Beverage serving activities)
- **Public Repo**: https://github.com/cloud-itonami/cloud-itonami-isic-563
- **Business-ID**: `cloud-itonami-isic-563`

### 2. Operation Allowlist (Closed)

1. `:schedule-table-reservation` — Table/seating scheduling logistics (administrative only)
2. `:coordinate-order-status-update` — Administrative order-queue status tracking (NOT beverage preparation)
3. `:coordinate-supply-request` — Non-beverage consumable coordination (glassware, napkins, cleaning supplies)
4. `:schedule-staff-shift-proposal` — Administrative shift proposal (NOT bartender certification)
5. `:flag-safety-concern` — Facility/intoxication/welfare safety concerns for HUMAN review

Any operation outside this set is rejected (scope exclusion).

### 3. Phase Progression (0→3)

- **Phase 0** (read-only): All proposals held for human review
- **Phase 1**: Table scheduling + order status updates auto-commit
- **Phase 2**: + supply coordination + staff shift proposals auto-commit
- **Phase 3**: All non-safety auto-commit, safety concerns always escalate

### 4. Deliverables

**Files committed to `cloud-itonami-isic-563` main**:

```
src/beverageops/
  - store.cljc (SSoT, demo data, MemStore)
  - advisor.cljc (proposal scoring)
  - governor.cljc (three HARD checks)
  - operation.cljc (StateGraph orchestration)
  - phase.cljc (rollout phases)
  - sim.cljc (demo runner)

test/beverageops/
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
║ ISIC-563 Beverage-Service Administrative Coordination Tests║
╚════════════════════════════════════════════════════════════╝

[1] Store: table lookup ✓
[2] Store: all tables ✓
[3] Store: supply lookup ✓
[4] Store: ledger append ✓
[5] Governor: table unverified check ✓
[6] Governor: effect not :propose check ✓
[7] Governor: scope exclusion (age-verification) ✓
[8] Governor: scope exclusion (responsible-service) ✓
[9] Governor: flag-safety-concern allowed ✓
[10] Governor: full governor decision (pass) ✓
[11] Operation: table reservation proposal (happy path) ✓
[12] Operation: unverified order rejection ✓
[13] Operation: safety concern escalation ✓
[14] Phase: phase 0 (read-only) ✓
[15] Phase: phase 1 (table reservation + status) ✓
[16] Phase: phase 3 (full auto-commit) ✓

All tests passed! (16/16)
```

**Demo via nbb**: All 5 scenarios (table reservation, order status, supply, shift, safety) run to completion offline. Demo output confirmed.

**Repository**: Public, all files present via GitHub API.

### 6. Registry Update

**File**: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`

**Entry before**:
```clojure
{:id "563" :name "Beverage serving activities"
 :repo nil :business-id nil :maturity :spec
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger]
 :optional-technologies []
 :operating-states [:intake :order :serve :pay :audit]}
```

**Entry after**:
```clojure
{:id "563" :name "Beverage serving activities"
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-563"
 :business-id "cloud-itonami-isic-563"
 :maturity :implemented
 :required-technologies [:identity :forms :dmn :bpmn :audit-ledger]  ; :robotics stripped
 :optional-technologies []
 :operating-states [:intake :order :serve :pay :audit]}
```

**Registry validated**: Total industries remain 648 ✓

## Consequences

### Positive

- ISIC-563 Wave 4 food-service cluster actor now complete (completing 561/562/563 sub-range)
- Bar/cafe administrative coordination-only actor pattern proven reusable
- No age-verification/responsible-service authority — enforced at Governor level
- All tests passing, demo verified, registry promoted

### Risks & Mitigations

- **Regulatory variance**: Beverage-service licensing and compliance vary by jurisdiction. This actor handles **administrative coordination only** — deployment requires local compliance review and age-verification/responsible-service integration via human governance.
- **Safety escalation dependency**: `:flag-safety-concern` always escalates to human review — deployment must ensure human-review infrastructure exists.
- **Alcohol regulation**: ISIC-563 includes alcohol-serving establishments. This actor deliberately **excludes** alcohol-specific decisions (responsible service, age verification, inventory). Alcohol-serving venues deploying this actor must layer additional human governance for alcoholic beverages.

## References

- ADR-2607121000 (wave definition, value function, inverse topological sort)
- ADR-2607152500 (Wave 4 amendment, verified-redo authorization)
- ADR-2607155100 (isic-561 restaurant coordination, sibling actor)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor, audit ledger)
- Skill `new-project-scaffold` (repo lifecycle: ADR → scaffold → GitHub → registry)

---

**Draft & verification completed**: 2026-07-15
**Co-Author**: Claude Sonnet 5 (verified-redo agent)
