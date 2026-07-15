# ADR-2607154302: cloud-itonami ISIC-942 Trade Union Actor

**Status**: Accepted  
**Date**: 2026-07-15  
**Deciders**: Jun Kawasaki  

## Context

ISIC 942 (Activities of trade unions) covers union-local administrative coordination. This is the **nineteenth Wave-4 service actor** in the cloud-itonami fleet (ADR-2607121000, amended ADR-2607152500), second in the **94x (membership organizations) cluster** after isic-941.

**Scope**: Union-local administrative coordination only — member enrollment logistics, event/meeting scheduling, dues-processing logistics, supply coordination, staff shift scheduling.

**Out of scope (hard-coded blocks)**: Collective-bargaining positions, grievance-adjudication decisions, strike-authorization decisions, union-leadership/officer decisions, disciplinary action.

Modeled on isic-941 (Membership Association, ADR-2607154301), with identical module structure: `.cljc` portable code, MemStore, deterministic advisor, three HARD governor checks, langgraph-clj StateGraph operation flow, 5 scenario simulation, 20-case test suite.

## Decision

Build and land **cloud-itonami-isic-942** as a public GitHub actor repo:

### Operations (Closed Allowlist)

- **`:schedule-member-meeting`** — Meeting/event scheduling logistics
- **`:coordinate-dues-processing-logistics`** — Administrative dues-tracking/reminder logistics (never fee-waiver)
- **`:coordinate-supply-request`** — Non-content consumables
- **`:schedule-staff-shift-proposal`** — Administrative shift PROPOSAL only (never binding)
- **`:flag-safety-concern`** — Facility/member-conduct concerns for HUMAN review (always escalates)

### Three HARD, Permanent, Un-Overridable Governor Checks

1. **Member/event-record unverified** — Target must exist in store AND be independently `:registered?`/`:verified?`, re-derived every time.

2. **Effect not `:propose`** — Rejected outright. All effects must be `:propose`.

3. **Scope exclusion** — Any proposal touching:
   - Collective-bargaining positions/negotiations
   - Grievance-adjudication/dispute-resolution
   - Strike-authorization/labor action
   - Union-leadership/officer decisions
   - Disciplinary action/expulsion

   Uses EN+JA substring scan (`collective.?bargain`, `grievance`, `strike`, `union.?officer`, `disciplinary`, etc.), qualified so `:flag-safety-concern` isn't self-blocked.

### Modules (All `.cljc` — Portable)

- **store.cljc** — MemStore protocol; member/event directory; demo data (3 members, 2 events, 3 accounts, append-only ledger)
- **advisor.cljc** — Proposal enrichment (deterministic demo); preserves original proposal fields and adds `:advisor-reasoning` + `:confidence`
- **governor.cljc** — Three HARD checks; no overrides; conditional member-verification (only if member-id present)
- **operation.cljc** — StateGraph-equivalent flow: intake → advise → govern → decide → commit | hold | escalate
- **phase.cljc** — Rollout phases 0–3 (which ops auto-commit, which escalate)
- **sim.cljc** — 5 demo scenarios (happy path, hard checks, escalation)
- **test.cljc** — 20 comprehensive test cases (5 store + 7 governor + 5 operation + 3 phase)

### Test Coverage (20 Cases, All Passing)

**Store** (5):
- Member lookup
- All members
- Event lookup
- Account lookup
- Ledger append

**Governor** (7):
- Member verified check
- Member unverified check
- Effect not `:propose` check
- Scope exclusion: collective bargaining
- Scope exclusion: grievance
- Scope exclusion: strike
- Safety concern allowed (legitimate use)

**Operation** (5):
- Event scheduling (happy path)
- Unverified member rejection
- Safety concern escalation
- Dues logistics (happy path)
- Supply request (happy path)

**Phase** (3):
- Phase consistency 1
- Phase consistency 2
- Phase consistency 3

### Repo Visibility

PUBLIC (GitHub `private: false`, `visibility: public`).

### Registry Integration

Update **kotoba-lang/industry registry.edn** entry 942:
- From: `:maturity :spec`, `:repo nil`, `:business-id nil`, `:required-technologies [:robotics ...]`
- To: `:maturity :implemented`, `:repo "https://github.com/cloud-itonami/cloud-itonami-isic-942"`, `:business-id "cloud-itonami-isic-942"`, `:required-technologies [:identity :forms :dmn :bpmn :audit-ledger]` (`:robotics` stripped)

Validated with nbb EDN parser before and after: **total industries = 648** (unchanged).

## Consequences

### Positive

- (+) ISIC-942 actor joins Wave-4 membership-organization tier with governance-enforced scope boundaries.
- (+) Three HARD governor checks prevent scope violations structurally (no human override path).
- (+) All operations auto-escalate safety/conduct concerns even if governance passes.
- (+) Comprehensive test suite (20 test cases) exercises all critical paths.
- (+) Public GitHub repo, AGPL-3.0 licensed, ready for external registration/integration.
- (+) Proposal-field preservation ensures scope-exclusion checks catch violations embedded in proposal data (fix for isic-920 dead-code bug class).

### Limitations

- (−) Deterministic advisor is demo-only; production requires real LLM with prompt injection safeguards.
- (−) MemStore is in-memory; production requires persistent backing store (EDN file, database, ledger).
- (−) Staff-shift proposals are administrative PROPOSAL only; actual shift binding/enforcement requires separate governance gate.

## References

- ADR-2607121000: Cloud-itonami Wave-4 rollout plan (ISIC 90–98 tier)
- ADR-2607152500: Wave-4 amendment (ADR slot allocation)
- ADR-2607154301: isic-941 design reference (module shape pattern)
- CLAUDE.md: Actors pattern, build-actor skill, registry verification workflow
- GitHub: cloud-itonami/cloud-itonami-isic-942 (public repo, AGPL-3.0)

## Artifacts

- **GitHub**: https://github.com/cloud-itonami/cloud-itonami-isic-942 (public repo, AGPL-3.0)
- **blueprint.edn**: Operations, governor checks, phases 0–3
- **src/tradeunionorg/{store,advisor,governor,operation,phase,sim}.cljc**: All `.cljc` modules
- **test/tradeunionorg/test.cljc**: 20 test cases
- **deps.edn**: Clojure 1.12.0, ClojureScript 1.10.914
- **docs**: LICENSE, README, CODE_OF_CONDUCT, CONTRIBUTING, GOVERNANCE, SECURITY
- **registry.edn**: Entry 942 updated (`:implemented`, `:repo`, `:business-id`, `:robotics` stripped)
