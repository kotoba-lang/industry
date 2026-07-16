# ADR 2607142200: cloud-itonami ISIC 8423 Public Order and Safety Administrative Coverage

**Date**: 2026-07-14  
**Status**: Proposed  
**Org**: cloud-itonami  
**Scope**: ISIC Rev.5 8423 (Public Order and Safety Activities)

## Summary

Scaffold and land reference implementation of the ISIC 8423 actor (Public Order and Safety Administrative Operations) as a pure-administrative governance system with explicit operational scope exclusions. This is the second actor in the cloud-itonami ISIC coverage series (following 8422 Defence Procurement).

## Background

ISIC 8423 comprises police, fire, and emergency response agencies. The cloud-itonami platform designs each ISIC class as an autonomous actor behind an independent Governor. For public safety, the challenge is encoding **absolute boundaries** between administrative operations (incident intake, records requests, resource scheduling, anomaly detection) and operational/enforcement functions (arrest, investigation, use-of-force, evidence handling, tactical dispatch) such that the latter are **structurally impossible** to propose.

## Architecture & Scope Boundaries

### What the 8423 Actor DOES

- **Administrative intake**: clerical incident report logging (no investigation direction)
- **Records request processing**: public/administrative record access
- **Resource scheduling**: non-tactical facility and equipment scheduling
- **Anomalous pattern flagging**: statistical pattern identification, always escalates to human analyst
- **Audit trail**: append-only ledger of all proposals/verdicts/dispositions

### What the 8423 Actor DOES NOT (Hard Boundaries, Permanently Out of Scope)

These operations are **structurally forbidden** — not gated by approval, not negotiable, not in the actor's vocabulary:

- **Arrest decisions or detention authority**
- **Use-of-force authorization or direction**
- **Investigation direction or oversight**
- **Evidence handling or chain-of-custody**
- **Tactical dispatch or operational command**
- **Intelligence or classified operations**
- **Lethal or kinetic decisions**

Hard invariants in the Governor enforce three unbreakable gates:
1. Requester/case must be registered
2. Proposal `:effect` must be `:propose` (no direct store writes)
3. Operation must be in the closed allowlist (`:log-incident-report`, `:process-records-request`, `:schedule-resource`, `:flag-anomalous-pattern`)

Any proposal violating these routes to `:hold` (no escalation path, no human override, no exception).

## Implementation

**Repository**: [`cloud-itonami/cloud-itonami-isic-8423`](https://github.com/cloud-itonami/cloud-itonami-isic-8423)

**Status**: `:maturity :implemented` (reference implementation complete, tests green)

**Components**:
- `src/safety/actor.cljc` — langgraph StateGraph (:intake → :advise → :govern → :decide → :commit/:hold/:request-approval)
- `src/safety/advisor.cljc` — closed-allowlist proposal generation (mock + LLM variants)
- `src/safety/governor.cljc` — hard invariants + escalation gates
- `src/safety/store.cljc` — append-only records + audit ledger
- `test/safety/` — actor + governor test suites (all green: 12 tests, 33 assertions, 0 failures)

**Key files**:
- `docs/adr/0001-architecture.md` — detailed scope-boundary design + ADR
- `README.md` — scope exclusion documentation (even more emphatic than 8422)
- `blueprint.edn` — ISIC metadata

## Test Coverage

```bash
$ clojure -M:test
Ran 12 tests containing 33 assertions.
0 failures, 0 errors.
```

Tests cover:
- Clean incident logging (auto-commit)
- Unregistered requester rejection
- Records request escalation (human sign-off)
- Anomalous pattern flagging escalation
- Scope boundary violations (hard holds)
- Low-confidence escalation
- Append-only ledger integrity

## Decision

✅ Land the 8423 actor as a public GitHub repository (AGPL-3.0-or-later), register in `kotoba-lang/industry` ISIC 8423 entry as `:maturity :implemented`, and add to cloud-itonami actor roster.

## Consequences

### Positive

- **Structural safety**: arrest, investigation, and enforcement are not negotiable; they are impossible to construct.
- **Auditability**: every proposal and disposition is logged. Auditors can confirm that no operational/enforcement proposals reached the governance layer.
- **Transparency**: public, OSS, zero vendor lock-in.
- **Precedent**: second concrete ISIC actor (following 8422), establishes the pattern for rapid expansion to other sectors.

### Negative

- **Strictness**: any new administrative operation requires design review and code change (by design, not a bug).
- **LLM discipline**: system prompt and advisor logic must carefully encode allowlist and exclusions. Updates require parallel instruction tuning.

## References

- ADR-2607011000: Itonami Actor Pattern (langgraph-clj StateGraph)
- ADR-2607142000: cloud-itonami ISIC 8422 (Defence Procurement) — prior art, same pattern
- [`cloud-itonami-isic-8422`](https://github.com/cloud-itonami/cloud-itonami-isic-8422) — template/reference
- [`cloud-itonami-isic-8423`](https://github.com/cloud-itonami/cloud-itonami-isic-8423) — this implementation
- CLAUDE.md: Actors section, Actor pattern standing authorization
