# ADR 2607142000: cloud-itonami-isic-8422 Defence Procurement Actor

**Date**: 2026-07-14  
**Status**: Accepted  
**Scope**: cloud-itonami / ISIC Rev.5 8422  

## Summary

We implement ISIC Rev.5 8422 (Defence Procurement and Logistics Administration) as an autonomous actor under the itonami pattern: a `langgraph` StateGraph with a Governor, Advisor, and Store, enforcing a **closed allowlist** of permitted operations and hard invariants that make weapons, personnel, and lethal proposals structurally impossible to construct.

## Context

Defence ministry procurement and logistics administration is a distinct business area from combat operations, weapons systems, or tactical command. The challenge is designing an actor that supports authentic administrative work while enforcing absolute scope boundaries:

- **Unintended scope creep**: without explicit allowlists, procurement actors can drift into weapons procurement, personnel deployment, or targeting (especially if LLM-advised).
- **Safe escalation**: some operations are legitimate but require human sign-off (budget commitments, procurement requests); others are permanently forbidden with no override path (weapons, personnel, classified operations).
- **Auditability**: every proposal (committed or held) must leave an append-only ledger entry so auditors can verify that no weapons/personnel/classified proposals reached human review.

## Decision

Implement `cloud-itonami-isic-8422` as a public AGPL-3.0-or-later repository with:

### 1. Closed Allowlist (Advisor Layer)

The advisor's proposal vocabulary is restricted to exactly four operations:
- `:register-vendor` — vendor/contractor verification
- `:draft-procurement-request` — procurement intake and drafting
- `:schedule-equipment-maintenance` — equipment maintenance scheduling
- `:coordinate-logistics` — non-combat logistics coordination

Any request implying weapons, personnel deployment, classified operations, or lethal decisions is rejected with `:op :unknown` and `:confidence 0.0`. This blocks it from both the advisor layer (structured impossibility) and the governor layer (hard hold on `:unknown` ops).

### 2. Hard Invariants (Governor Layer)

Three classes of violations always route to permanent `:hold`, with no approval path:

1. **Vendor provenance**: unregistered vendors are always held
2. **No direct actuation**: proposals claiming direct store writes are always held (the actor only produces `:propose` effects)
3. **Scope boundary**: proposals with `:op :unknown` (out-of-scope requests) are always held

Hard holds are non-overridable. This is not a "high-risk op requiring escalation" — it is a design invariant.

### 3. Escalation Invariants (Human Sign-Off)

Operations that are not hard violations but carry higher risk require explicit human approval:

1. **Procurement request drafting** — requires human review of scope, vendor, and deliverables
2. **Budget commitment above threshold** (1,000,000 configurable) — financial exposure threshold
3. **Low advisor confidence** (<0.6) — forces human judgment when the LLM is uncertain

Escalation routes to `:request-approval` (interrupt-before node). Resume is explicit: `(actor/approve! graph thread-id)` advances to `:commit`.

### 4. Scope Boundaries (Prominently Documented)

The README and SECURITY.md explicitly state what the actor **does NOT** do:

> - **Personnel deployment, command, or authority decisions** — no tactical decisions, unit assignments, or personnel movement
> - **Weapons systems, munitions, or targeting** — no weapons procurement, explosives, or combat systems; no engagement logic
> - **Classified or operational military activity** — no access to classified intelligence, operational plans, or real-time combat data
> - **Lethal autonomous decisions** — no authority to propose any decision whose effect is kinetic, destructive, or results in loss of life

These boundaries are enforced at the Governor layer as permanent blocks, not just gated by risk level.

## Implementation

- **Repository**: `cloud-itonami/cloud-itonami-isic-8422` (public, AGPL-3.0-or-later)
- **Architecture**: `src/defence/` — `store.cljc`, `advisor.cljc`, `governor.cljc`, `actor.cljc`
- **Tests**: `test/defence/` — full coverage of store, governor, and StateGraph
- **Documentation**: 
  - `docs/adr/0001-architecture.md` — detailed design rationale and closed allowlist reasoning
  - `docs/business-model.md` — business/customer context
  - `docs/operator-guide.md` — procurement officer workflow
  - `README.md` — scope boundaries explicitly stated upfront
  - `SECURITY.md` — hard invariants and audit trail documentation

## Testing

All tests pass:
- `test/defence/governor_test.clj` — 6 tests covering hard violations, escalation, and store operations
- `test/defence/actor_test.clj` — 6 tests covering clean commits, holds, escalation/approval, and scope boundaries

```bash
$ clojure -M:test
Ran 12 tests containing 33 assertions.
0 failures, 0 errors.
```

## Consequences

### Positive

- **Scope explicitness**: the allowed operations are named and documented. Any expansion requires deliberate design review.
- **LLM safety**: even if an LLM hallucinates an out-of-scope operation, the advisor layer rejects it with zero confidence, and the governor holds it.
- **Auditability**: all proposals (committed or held) leave an append-only ledger. Auditors can verify no weapons/personnel/classified proposals reached human review.
- **Clear responsibility**: escalated ops (human sign-off) are visibly distinct from automatic-commit ops (routine logistics).

### Negative

- **Strictness**: new operations require code review and tests. Operators cannot expand scope via config.
- **LLM maintenance**: the system prompt and advisor logic must stay in sync as models evolve.

## Registry Entry

In `kotoba-lang/industry`, ISIC 8422 entry is set to `:maturity :implemented` with:
```clojure
{:isic "8422"
 :name "Defence Procurement and Logistics Administration"
 :repo "cloud-itonami/cloud-itonami-isic-8422"
 :maturity :implemented
 :governor :defence-procurement-governor}
```

## References

- **Repository**: https://github.com/cloud-itonami/cloud-itonami-isic-8422
- **Architecture ADR**: `cloud-itonami-isic-8422/docs/adr/0001-architecture.md`
- **Reference implementations**: 
  - `cloud-itonami-isco-2411` (Accountants, similar pattern)
  - `cloud-itonami-isic-3510` (Grid Transmission, fuller ADR style)
- **itonami pattern**: ADR-2607011000, CLAUDE.md Actors section
- **Closed allowlist reasoning**: `cloud-itonami-isic-8422/docs/adr/0001-architecture.md` ("Closed Allowlist Rationale" section)

## Sign-Off

- Scope boundaries: **explicit and tested**
- Hard invariants: **enforced in Governor layer**
- Tests: **all green**
- Documentation: **scope boundaries prominently documented**
