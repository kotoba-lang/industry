# ADR-2607154304: cloud-itonami ISIC-952 Repair Shop Administrative Coordination Actor

**Status**: Accepted  
**Date**: 2026-07-15  
**Deciders**: Jun Kawasaki  

## Context

ISIC 952 (Repair of personal and household goods) covers repair shops — shoe/leather repair, watch/jewelry repair, furniture repair, appliance repair shops. This is the **twenty-first Wave-4 service actor** in the cloud-itonami fleet (ADR-2607121000, amended ADR-2607152500), completing the **95x cluster** (personal/household goods repair).

**Scope**: Repair-shop administrative coordination only — intake scheduling, repair-status logistics tracking, parts/supply coordination, staff shift scheduling.

**Out of scope (hard-coded blocks)**: Diagnostic/repair-technique decisions, warranty/liability determinations, pricing/quote-approval decisions, safety-authority overrides.

Modeled on isic-949 (ADR-2607154303), with identical module structure: `.cljc` portable code, MemStore, deterministic advisor, three HARD governor checks, langgraph-clj StateGraph operation flow, 5 scenario simulation, 20-case test suite. Governor scope exclusion extended to explicitly block diagnostic/repair-technique and pricing decisions (unique to repair shops).

## Decision

Build and land **cloud-itonami-isic-952** as a public GitHub actor repo:

### Operations (Closed Allowlist)

- **`:schedule-repair-intake`** — Drop-off/pickup appointment scheduling
- **`:coordinate-repair-status-update`** — Administrative logistics (item at stage X)
- **`:coordinate-supply-request`** — Non-repair-part consumables
- **`:schedule-staff-shift-proposal`** — Administrative shift PROPOSAL only (never binding)
- **`:flag-safety-concern`** — Facility/equipment safety concerns for HUMAN review (always escalates)

### Three HARD, Permanent, Un-Overridable Governor Checks

1. **Item/customer unverified** — Target must exist in store AND be independently `:registered?`/`:verified?`, re-derived every time.

2. **Effect not `:propose`** — Rejected outright. All effects must be `:propose`.

3. **Scope exclusion** — Any proposal touching:
   - Diagnostic/repair-technique decisions
   - Warranty/liability determinations
   - Pricing/quote-approval decisions
   - Safety-authority overrides

   Uses EN+JA substring scan (patterns: `(?i)diagnostic`, `(?i)repair.?technique`, `(?i)warranty`, `(?i)liability`, `(?i)price.?approval`, `(?i)quote.?approval`, etc.), qualified so `:flag-safety-concern` isn't self-blocked.

### Modules (All `.cljc` — Portable)

- **store.cljc** — MemStore protocol; customer/item/staff/supplies directory; demo data (3 customers, 5 items, 2 staff, 3 supplies, append-only ledger)
- **advisor.cljc** — Proposal enrichment (deterministic demo); preserves original fields and adds `:advisor-reasoning` + `:confidence`
- **governor.cljc** — Three HARD checks; no overrides; conditional item-verification (only if item-id present)
- **operation.cljc** — StateGraph-equivalent flow: intake → advise → govern → decide → commit | hold | escalate
- **phase.cljc** — Rollout phases 0–3 (which ops auto-commit, which escalate)
- **sim.cljc** — 5 demo scenarios (happy path, hard checks, escalation)
- **test.cljc** — 20 comprehensive test cases (5 store + 7 governor + 5 operation + 3 phase)

### Test Coverage (20 Cases, All Passing)

**Store** (5):
- Customer lookup
- All-customers count
- Repair-item lookup
- All-items count
- Ledger append

**Governor** (7):
- Item unverified check
- Effect not `:propose` check
- Scope exclusion: diagnostic
- Scope exclusion: warranty
- Scope exclusion: pricing
- Flag-safety-concern allowed (legitimate use)
- Happy-path approve

**Operation** (5):
- Intake happy path
- Unverified item rejection
- Safety escalation
- Status update happy path
- Supply request happy path

**Phase** (3):
- Phase 0 (read-only)
- Phase 1 (safe ops)
- Phase 3 (auto-commit)

### Repo Visibility

PUBLIC (GitHub `private: false`, `visibility: public`).

### Registry Integration

Update **kotoba-lang/industry registry.edn** entry 952:
- From: `:maturity :spec`, `:repo nil`, `:business-id nil`, `:required-technologies [:robotics ...]`
- To: `:maturity :implemented`, `:repo "https://github.com/cloud-itonami/cloud-itonami-isic-952"`, `:business-id "cloud-itonami-isic-952"`, `:required-technologies [:identity :forms :dmn :bpmn :audit-ledger]` (`:robotics` stripped)

Validated with nbb EDN parser before and after: **total industries = 648** (unchanged).

## Consequences

### Positive

- (+) ISIC-952 actor joins Wave-4 repair-shop tier with governance-enforced scope boundaries.
- (+) Three HARD governor checks prevent scope violations structurally (no human override path).
- (+) All operations auto-escalate safety concerns even if governance passes.
- (+) Comprehensive test suite (20 test cases) exercises all critical paths.
- (+) Public GitHub repo, AGPL-3.0 licensed, ready for external registration/integration.
- (+) Governor scope exclusion explicitly blocks diagnostic/repair-technique and pricing decisions (distinct from civic orgs).

### Limitations

- (−) Deterministic advisor is demo-only; production requires real LLM with prompt injection safeguards.
- (−) MemStore is in-memory; production requires persistent backing store (EDN file, database, ledger).
- (−) Staff-shift proposals are administrative PROPOSAL only; actual shift binding/enforcement requires separate governance gate.

## References

- ADR-2607121000: Cloud-itonami Wave-4 rollout plan (ISIC 90–98 tier)
- ADR-2607152500: Wave-4 amendment (ADR slot allocation)
- ADR-2607154303: isic-949 design reference (module shape pattern)
- CLAUDE.md: Actors pattern, build-actor skill, registry verification workflow
- GitHub: cloud-itonami/cloud-itonami-isic-952 (public repo)

## Artifacts

- **GitHub**: https://github.com/cloud-itonami/cloud-itonami-isic-952 (public repo, AGPL-3.0)
- **blueprint.edn**: Operations, governor hard checks, phases 0–3
- **src/repairshop/{store,advisor,governor,operation,phase,sim}.cljc**: All `.cljc` modules
- **test/repairshop/test.cljc**: 20 test cases
- **deps.edn**: Clojure 1.12.0, ClojureScript 1.10.914
- **docs**: LICENSE, README, CODE_OF_CONDUCT, CONTRIBUTING, GOVERNANCE, SECURITY
- **registry.edn**: Entry 952 updated (`:implemented`, `:repo`, `:business-id`, `:robotics` stripped)
