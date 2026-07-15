# ADR-2607154301: cloud-itonami ISIC-941 Membership Association Actor

**Status**: Accepted  
**Date**: 2026-07-15  
**Deciders**: Jun Kawasaki  

## Context

ISIC 941 (Activities of business, employers and professional membership organizations) covers trade associations, chambers of commerce, and professional bodies. This is the **eighteenth Wave-4 service actor** in the cloud-itonami fleet (ADR-2607121000, amended ADR-2607152500), opening the **94x (membership organizations) cluster**.

**Scope**: Membership-association administrative coordination only — member enrollment logistics, event/meeting scheduling, dues-processing logistics, supply coordination, staff shift scheduling.

**Out of scope (hard-coded blocks)**: Membership-eligibility decisions, professional certification/credentialing, advocacy-policy content, dues-amount/fee-waiver decisions, disciplinary action.

Modeled on isic-932 (Amusement & Recreation, ADR-2607154200), with identical module structure: `.cljc` portable code, MemStore, deterministic advisor, three HARD governor checks, langgraph-clj StateGraph operation flow, 5 scenario simulation, 20-case test suite.

## Decision

Build and land **cloud-itonami-isic-941** as a public GitHub actor repo:

### Operations (Closed Allowlist)

- **`:schedule-member-event`** — Meeting/event scheduling logistics
- **`:coordinate-dues-processing-logistics`** — Administrative dues-tracking/reminder logistics (never fee-waiver)
- **`:coordinate-supply-request`** — Non-content consumables
- **`:schedule-staff-shift-proposal`** — Administrative shift PROPOSAL only (never binding)
- **`:flag-safety-concern`** — Facility/member-conduct concerns for HUMAN review (always escalates)

### Three HARD, Permanent, Un-Overridable Governor Checks

1. **Member/event-record unverified** — Target must exist in store AND be independently `:registered?`/`:verified?`, re-derived every time.

2. **Effect not `:propose`** — Rejected outright. All effects must be `:propose`.

3. **Scope exclusion** — Any proposal touching:
   - Membership-eligibility/expulsion
   - Professional certification/credentialing
   - Advocacy-position/policy-content
   - Dues-amount/fee-waiver decisions
   - Disciplinary action

   Uses EN+JA substring scan (`membership.?eligib`, `certification`, `credentialing`, `advocacy`, `dues.?waiv`, etc.), qualified so `:flag-safety-concern` isn't self-blocked.

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
- Member unverified check
- Effect not `:propose` check
- Scope exclusion: membership-eligibility
- Scope exclusion: certification
- Scope exclusion: dues-waiver
- Flag-safety-concern allowed (legitimate use)
- Full governor decision (pass)

**Operation** (5):
- Event scheduling (happy path)
- Unverified member rejection
- Safety concern escalation
- Dues logistics (happy path)
- Supply request (happy path)

**Phase** (3):
- Phase 0 (read-only)
- Phase 1 (event + dues)
- Phase 3 (full auto-commit)

### Repo Visibility

PUBLIC (GitHub `private: false`, `visibility: public`).

### Registry Integration

Update **kotoba-lang/industry registry.edn** entry 941:
- From: `:maturity :spec`, `:repo nil`, `:business-id nil`, `:required-technologies [:robotics ...]`
- To: `:maturity :implemented`, `:repo "https://github.com/cloud-itonami/cloud-itonami-isic-941"`, `:business-id "cloud-itonami-isic-941"`, `:required-technologies [:identity :forms :dmn :bpmn :audit-ledger]` (`:robotics` stripped)

Validated with nbb EDN parser before and after: **total industries = 648** (unchanged).

## Consequences

### Positive

- (+) ISIC-941 actor joins Wave-4 membership-organization tier with governance-enforced scope boundaries.
- (+) Three HARD governor checks prevent scope violations structurally (no human override path).
- (+) All operations auto-escalate safety/conduct concerns even if governance passes.
- (+) Comprehensive test suite (20 test cases) exercises all critical paths.
- (+) Public GitHub repo, AGPL-3.0 licensed, ready for external registration/integration.
- (+) Proposal-field preservation ensures scope-exclusion checks catch violations embedded in proposal data (fix for isic-920 dead-code bug).

### Limitations

- (−) Deterministic advisor is demo-only; production requires real LLM with prompt injection safeguards.
- (−) MemStore is in-memory; production requires persistent backing store (EDN file, database, ledger).
- (−) Staff-shift proposals are administrative PROPOSAL only; actual shift binding/enforcement requires separate governance gate.

## References

- ADR-2607121000: Cloud-itonami Wave-4 rollout plan (ISIC 90–98 tier)
- ADR-2607152500: Wave-4 amendment (ADR slot allocation)
- ADR-2607154200: isic-932 design reference (module shape pattern)
- CLAUDE.md: Actors pattern, build-actor skill, registry verification workflow
- GitHub: cloud-itonami/cloud-itonami-isic-932 (module shape reference)

## Artifacts

- **GitHub**: https://github.com/cloud-itonami/cloud-itonami-isic-941 (public repo, AGPL-3.0)
- **blueprint.edn**: Operations, governor checks, phases 0–3
- **src/membershipassocorg/{store,advisor,governor,operation,phase,sim}.cljc**: All `.cljc` modules
- **test/membershipassocorg/test.cljc**: 20 test cases
- **deps.edn**: Clojure 1.12.0, ClojureScript 1.10.914
- **docs**: LICENSE, README, CODE_OF_CONDUCT, CONTRIBUTING, GOVERNANCE, SECURITY
- **registry.edn**: Entry 941 updated (`:implemented`, `:repo`, `:business-id`, `:robotics` stripped)
