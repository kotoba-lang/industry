# ADR-2607142600: cloud-itonami ISIC-08 0910 — Petroleum Services Contractor Coordination

Date: 2026-07-14
Status: Implemented

## Summary

Scaffold and launch `cloud-itonami-isic-0910`: an open occupation blueprint
implementing ISIC-08 0910 (Support activities for petroleum and natural gas
extraction) as a langgraph-clj Actor for petroleum services contractors' back-office
coordination — service order intake, crew dispatch scheduling, site logistics
coordination, and safety incident logging.

## Scope: Contractor, Not Operator

**This actor supports a petroleum SERVICES CONTRACTOR's field operations,
NOT the drilling OPERATOR's well-control authority.**

### In Scope (Contractor Coordination)
- Service order intake and verification (client/well-site validation)
- Crew dispatch scheduling to well-sites
- Site logistics coordination (transport, crew movement, supplies)
- Safety incident logging (always escalated for human review)

### Out of Scope (Operator-Exclusive, Hard-Blocked)
- Drilling decisions (wellpath, bit selection, mud weight)
- Well-control operations (pressure management, shut-in procedures)
- Completion decisions (perforation, cementing, artificial lift)
- Hazardous-material handling authorization
- Subsurface data interpretation

This boundary is enforced as a hard invariant in `petroleum-services.governor`:
any proposal flagged with operator-class operations (`:drill`, `:well-control`,
`:hazmat-handle`, etc.) is instantly blocked with no override path, routing
directly to `:hold` with audit trail.

## Architecture

### StateGraph Flow
```
:intake → :advise → :govern → :decide ─┬─→ :commit            (ok ∧ ¬escalate)
                                         ├─→ :request-approval  (escalate?)
                                         └─→ :hold              (hard?)
```

### Key Components

**Store** (`petroleum-services.store`):
- Contractor registry (`:client-id`, `:name`, `:safety-rating`)
- Well-site registry (`:site-id`, `:operator-id`, `:location`, `:risk-level`)
- Operation records (write-once, append-only)
- Audit ledger (immutable history of all proposals/verdicts/dispositions)

**Advisor** (`petroleum-services.advisor`):
- Protocol-based; swappable implementation
- `mock-advisor`: deterministic (default for dev/test/CI)
- `llm-advisor`: wraps real LLM; parse failures → confidence 0.0 (never fabricated)
- Produces `:propose`-effect proposals only; never writes to store

**Governor** (`petroleum-services.governor`):
- Pure function; `check(request, context, proposal, store) → verdict`
- **Hard invariants** (→ `:hold`, no override):
  1. Contractor registered
  2. Well-site registered (if dispatch/logistics op)
  3. Proposal `:effect` must be `:propose`
  4. No operator-class operations (`:drill`, `:well-control`, `:hazmat-handle`, etc.)
- **Escalation invariants** (→ `:request-approval`, human sign-off):
  1. `:log-safety-incident` (always escalates)
  2. High-risk site dispatch (`:risk-level :high`)
  3. Low advisor confidence (< 0.6)

**Actor** (`petroleum-services.actor`):
- `build-graph(store, advisor, checkpointer)`: Compiles the StateGraph
- `run-request!(graph, request, context, thread-id)`: Execute one operation
- `approve!(graph, thread-id)`: Human-in-the-loop resume on escalation

### Portable Code

All source is `.cljc` (portable Clojure). No JVM-only constructs without
compelling reason and clear documentation. Following `kotoba-lang/occupation`
runtime precedence: kototama wasm > clojurewasm > ClojureScript > nbb > jvm.

## Implementation

- **Repo**: `https://github.com/cloud-itonami/cloud-itonami-isic-0910`
- **License**: AGPL-3.0-or-later
- **Status**: `:maturity :implemented`
- **Tests**: 13 tests, 35 assertions, 0 failures

### Test Coverage
- Governor hard invariants (unregistered contractor, unregistered site, bad effect, operator-class ops)
- Governor escalation invariants (safety logging, high-risk site, low confidence)
- Actor flow (clean commit, hold on violations, interrupt+approve on escalation)
- Store append-only semantics

## Governance and Scope Protection

The repo includes:
- **docs/adr/0001-architecture.md**: Full design with scope-exclusion boundaries
- **GOVERNANCE.md**: Decision process; scope boundaries are non-negotiable
- **CONTRIBUTING.md**: Scope reminder; PRs that blur contractor/operator boundary rejected
- **SECURITY.md**: Vulnerability reporting; operator-class bypass is critical

Any proposal to add operator-class operations will not be merged without
explicit architectural review and scope expansion ADR.

## Impact

- **Contractor**: Independent operational records (not locked into SaaS)
- **Compliance**: Append-only audit ledger meets oil & gas regulatory expectations
- **Safety**: Safety incidents always escalate; cannot be silently held
- **Attack surface**: Operator-class operations unreachable in contractor software,
  reducing liability
- **Replicability**: Pattern mirrors existing ISCO actors (Accountants, HR,
  Social Services); can be forked for other ISIC-08 contractor classes

## Registrations

- **kotoba-lang/occupation**: Entry `"0910"` added with `:repo "cloud-itonami-isic-0910"`
  and `:maturity :implemented`
- **west.yml**: Entry registered if/when moved into `manifest/repos.edn` scope
  (currently independent open repo, not a west project)

## References

- ISIC-08: https://unstats.un.org/unsd/publication/seriesM/seriesm_4rev4e.pdf
- ADR-2607011000: cloud-itonami Actors pattern
- CLAUDE.md: Actors section, runtime precedence, scope discipline
- Comparable ISCO actor: `cloud-itonami-isco-2411` (Accountants)
