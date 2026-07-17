# ADR-2607161900: cloud-itonami ISIC-960 Personal-Care Coordination Actor — Scaffold & Registry Promotion

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-960` (new public repo), `orgs/kotoba-lang/industry` registry (ISIC-960 promotion), Wave 4 personal-services fleet completion

## Context

ADR-2607152500 authorizes parallel Wave 3/4 rollout with **small-batch verified-redo pattern** (one target, full validation, real test/demo output). ISIC-960 completes the ISIC-95/96 personal-services cluster alongside isic-952 (repair-shop coordination):

- **isic-952**: Repair shop administrative coordination (equipment scheduling, parts supply, safety escalation)
- **isic-960**: Personal-care administrative coordination (appointment scheduling, service-status tracking, supply coordination, staff shifts, safety escalation)

Both operate **coordination-only** — no service-technique decisions, health/allergy-risk clinical judgments, or safety-authority overrides. Both use the same Governor pattern (three HARD checks, append-only ledger, phase-gated rollout).

### Domain Scope

Personal-care salon and service operations: hairdressing, beauty treatments, laundry/dry-cleaning, funeral/burial services, and other personal-care activities. This actor coordinates back-office logistics:

- Appointment scheduling (administrative only, not client-facing health/contraindication screening)
- Service-status administrative updates (never the clinical service-quality sign-off itself)
- Supply ordering (non-service-critical consumables: office paper, cleaning supplies, etc. — never clinical/treatment products)
- Staff shift proposals (administrative proposal only, never staff-qualification/assignment decisions)
- Safety concern escalation (facility hazards, sanitation issues, client-welfare concerns — always escalates to human review)

### Actor Pattern (Module Shape)

- **Store** (`personalcareops.store`): SSoT with client/service directory, append-only ledgers
- **Advisor** (`personalcareops.advisor`): Proposal confidence scoring (deterministic demo; production would use LLM)
- **Governor** (`personalcareops.governor`): Three HARD, permanent, un-overridable checks
- **Operation** (`personalcareops.operation`): langgraph-clj StateGraph orchestration (intake → advise → govern → decide → action)
- **Phase** (`personalcareops.phase`): Rollout phases 0–3 (auto-commit gate control)
- **Sim** (`personalcareops.sim`): Deterministic demo runner
- **Tests** (`test/personalcareops/test.cljc`): Full coverage (store, governor, operations, phases) — all passing

### Governor: Three HARD Checks (Un-overridable)

**Check 1: Client/Appointment-Record Unverified**
- Target client must exist in store AND be `:registered?` AND `:verified?`
- Re-derived from store's own fields every proposal (never from proposal self-report)
- Exception: `:flag-safety-concern` (facility-level) doesn't require client verification

**Check 2: Effect Not `:propose`**
- Effect must be `:propose`
- No other effect values (`:commit`, `:execute`, etc.) accepted
- Rejected outright if violated

**Check 3: Scope Exclusion**
- Blocked: service-technique decisions, health/sanitation-compliance determinations, client health/allergy-risk clinical judgments, safety-authority overrides
- Pattern scan: EN+JA substring matching (treatment-plan, allergy-risk, clinical-judgment, 施術方法, アレルギーリスク, etc.)
- Allowed (closed allowlist): `:schedule-service-appointment`, `:coordinate-service-status-update`, `:coordinate-supply-request`, `:schedule-staff-shift-proposal`, `:flag-safety-concern`
- Legitimate `:flag-safety-concern` ops can mention safety without self-blocking (always escalates anyway)

## Decision

### 1. Module Identity

- **ID**: `cloud-itonami-isic-960`
- **ISIC Code**: 960 (Other personal service activities)
- **Public Repo**: https://github.com/cloud-itonami/cloud-itonami-isic-960
- **Business-ID**: `cloud-itonami-isic-960`

### 2. Operation Allowlist (Closed)

1. `:schedule-service-appointment` — Appointment scheduling logistics
2. `:coordinate-service-status-update` — Administrative status tracking
3. `:coordinate-supply-request` — Non-service-critical office supplies
4. `:schedule-staff-shift-proposal` — Administrative shift proposal (not binding)
5. `:flag-safety-concern` — Facility/sanitation/client-welfare escalation

Any operation outside this set is rejected (scope exclusion). Any operation in this set is still scanned for forbidden territory (clinical/health/technique keywords), and rejected if found (except `:flag-safety-concern` which always escalates).

### 3. Phase Progression (0→3)

- **Phase 0** (read-only): All proposals held for human review
- **Phase 1**: Appointment scheduling + status updates auto-commit
- **Phase 2**: + supply coordination + staff shift proposals auto-commit
- **Phase 3**: All non-safety auto-commit, safety concerns always escalate

### 4. Deliverables

**Files committed to `cloud-itonami-isic-960` main**:

```
src/personalcareops/
  - store.cljc (SSoT, demo data, MemStore)
  - advisor.cljc (proposal scoring)
  - governor.cljc (three HARD checks)
  - operation.cljc (StateGraph orchestration)
  - phase.cljc (rollout phases)
  - sim.cljc (demo runner)

test/personalcareops/
  - test.cljc (full test suite)

Governance:
  - LICENSE (AGPL-3.0)
  - CODE_OF_CONDUCT.md (Contributor Covenant)
  - CONTRIBUTING.md (dev guide)
  - GOVERNANCE.md (decision authority)
  - SECURITY.md (threat model, no-liability)
  - README.md (domain scope, references)
  - blueprint.edn (module metadata)

Config:
  - deps.edn (Clojure/ClojureScript dependencies)
  - run-demo.clj (demo launcher)
  - run-tests.cljs (nbb test runner)
  - .gitignore (standard)
```

### 5. Verification (Real Output)

**Tests via nbb**:
```
╔════════════════════════════════════════════════════════════╗
║ ISIC-960 Personal-Care Coordination Actor Tests           ║
╚════════════════════════════════════════════════════════════╝

=== Store Tests ===
[1] Client lookup ✓
[2] All clients ✓
[3] Service lookup ✓
[4] Ledger append ✓

=== Governor Tests ===
[1] Client unverified check ✓
[2] Effect not :propose check ✓
[3] Scope exclusion (treatment-plan) ✓
[4] Scope exclusion (allergy-risk) ✓
[5] Flag-safety-concern allowed ✓
[6] Full governor decision (pass) ✓

=== Operation Tests ===
[1] Appointment proposal (happy path) ✓
[2] Unverified client rejection ✓
[3] Safety concern escalation ✓

=== Phase Tests ===
[1] Phase 0 (read-only) ✓
[2] Phase 1 (appointment + status) ✓
[3] Phase 3 (full auto-commit) ✓

All tests passed!
```

**Demo via nbb**: All 5 scenarios (appointment, status, supply, shift, safety) run to completion offline. Demo output confirmed.

**Repository**: Public, all files present via GitHub API.

### 6. Registry Update

**File**: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`

**Entry before**:
```clojure
{:id "960" :name "Other personal service activities"
 :repo nil :business-id nil :maturity :spec
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger]
 :optional-technologies []
 :operating-states [:intake :diagnose :quote :repair :return :audit]}
```

**Entry after**:
```clojure
{:id "960" :name "Other personal service activities"
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-960"
 :business-id "cloud-itonami-isic-960"
 :maturity :implemented
 :required-technologies [:identity :forms :dmn :bpmn :audit-ledger]  ; :robotics stripped
 :optional-technologies []
 :operating-states [:intake :diagnose :quote :repair :return :audit]}
```

**Registry validated**: Total industries remain 648 ✓

## Consequences

### Positive

- ISIC-95/96 personal-services Wave 4 cluster now complete (952 + 960)
- Coordination-only actor pattern (Governor + allowlist + hard checks) proven reusable across domains
- No clinical judgment authority — enforced at Governor level, not policy
- All tests passing, demo verified, registry promoted

### Risks & Mitigations

- **Regulation variance**: Personal-care service licensing/compliance varies by jurisdiction (hairdressing, funeral services, etc.). This actor handles **coordination only** — deployment requires local compliance review. Deployment is not automatic upon registry promotion.
- **Safety escalation dependency**: `:flag-safety-concern` always escalates to human review — actor never auto-commits safety decisions. Deployment must ensure human-review infrastructure exists.

### Next Steps (Non-blocking)

- Parallel Wave 4 batch continuation (isic-873 eldercare, isic-880 social services, etc.)
- Wave 3 quality audit branch consolidation (ADR-2607152500 call-out)

## References

- ADR-2607121000 (wave definition, value function, inverse topological sort)
- ADR-2607152500 (Wave 4 amendment, verified-redo authorization, small-batch pattern)
- ADR-2607152100 (isic-0510 hard coal, verified-redo module shape reference)
- ADR-2607152300 (isic-0520 lignite, 61% defect recovery pattern)
- ADR-2607154304 (isic-952 repair-shop coordination, sibling ISIC-95/96 cluster member)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor, audit ledger)
- Skill `new-project-scaffold` (repo lifecycle: ADR→scaffold→GitHub→registry)

---

**Draft & verification completed**: 2026-07-15
**Co-Author**: Claude Sonnet 5 (verified-redo agent)
