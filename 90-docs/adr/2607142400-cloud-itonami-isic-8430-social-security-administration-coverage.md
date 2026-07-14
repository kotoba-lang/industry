# ADR-2607142400: ISIC 8430 Social Security Administration Coverage

**Date**: 2026-07-14  
**Status**: DECIDED  
**Deciders**: Jun Kawasaki  
**Decision**: Adopt ISIC Rev.5 8430 (Compulsory social security activities) as an open occupation blueprint and cloud-itonami actor vertical.

## Context

Social security administration is a high-impact domain where automation must be exceptionally disciplined about safety and scope boundaries. The pattern established in ISIC 8422 (Defence Procurement Administration) demonstrates how to design an actor that is operationally useful while maintaining strict hard invariants on what it does NOT do.

ISIC 8430 encompasses the administrative operations of social security agencies: benefits intake, eligibility documentation, payment record logging, and appeal intake. The temptation is to build "benefits determination" into the actor. We deliberately do not.

## Decision

Scaffold `cloud-itonami-isic-8430` as a public AGPL-3.0 repository with the following characteristics:

### What the Actor Does (Closed Allowlist)

- `:intake-benefits-application` — receive and organize benefits applications, collect required supporting documents
- `:verify-eligibility-checklist` — walk through a documented, externally-defined eligibility checklist (statements of fact only, never the determination)
- `:log-payment-record` — log payments authorized by human staff
- `:intake-appeal` — receive and organize appeals and grievances (always escalates to human)

### What the Actor DOES NOT Do (Hard Boundaries, Permanently Out of Scope)

**These operations are structurally impossible.** The actor's vocabulary has no path to construct them, and the governor will ALWAYS hold any proposal that touches them (not subject to escalation, confidence level, or override):

- **Claim approval or denial** — exclusively human decisions
- **Benefit amount calculation or disbursement** — exclusively human decisions
- **Eligibility determination** — exclusively human decisions
- **Appeal decisions** — exclusively human decisions
- **Policy decisions** — exclusively human decisions

### Implementation

- **Advisor**: proposes only the four administrative operations from a closed allowlist. Cannot construct determination/disbursement operations.
- **Governor**: enforces hard invariants (unregistered claimant, non-:propose effect, any determination/disbursement proposal) by routing to :hold with no escalation path.
- **Store**: append-only ledger of all proposals, verdicts, decisions. Registered claimants. Committed records.
- **Actor**: langgraph StateGraph with human-in-the-loop interrupt at :request-approval for escalations (appeals, low confidence).

### Social Impact

- **Administrative efficiency**: caseworkers spend less time on paperwork, more time on judgment-call cases
- **Claimant transparency**: clear intake process, verifiable eligibility checklist
- **Operational accountability**: complete audit trail of every proposal and decision
- **Regulatory compliance**: records and appeals trail for oversight and review

## Rationale

1. **Safety discipline**: benefits delivery affects real livelihood. Every determination decision must be a human decision, personally accountable to the claimant.
2. **Legal authority**: only humans (typically legally designated benefits officers) have the authority to approve or deny benefits.
3. **No hidden bias**: benefits decisions must be explainable to the claimant; LLM-driven determinations fail that standard.
4. **Auditability**: appeal processes require a clear human decision-maker.

## Implementation Details

**Repository**: `https://github.com/cloud-itonami/cloud-itonami-isic-8430`

**Blueprint Entry** (`blueprint.edn`):
- `:itonami.blueprint/id`: "cloud-itonami-isic-8430"
- `:itonami.blueprint/isic-rev5`: "8430"
- `:itonami.blueprint/domain`: `:social-security/administration`
- `:itonami.blueprint/status`: `:public-oss`
- `:itonami.blueprint/governor`: `:social-security-governor`

**Maturity**: `:implemented` — full langgraph StateGraph with mock-advisor (deterministic) and governance rules enforced. LLM advisor variant is a future extension (swappable protocol).

**Core Files**:
- `src/social_security/store.cljc` — Store protocol + MemStore
- `src/social_security/advisor.cljc` — Advisor protocol + mock-advisor
- `src/social_security/governor.cljc` — SocialSecurityGovernor with hard/escalation rules
- `src/social_security/actor.cljc` — StateGraph wiring
- `test/social_security/*_test.clj` — 10 tests, 20 assertions, green

**Documentation**:
- `docs/adr/0001-architecture.md` — scope boundaries and rationale
- `docs/business-model.md` — operational model and social impact
- `docs/operator-guide.md` — running the system, escalation workflow, audit trail

## Consequences

- Cloud-itonami now covers 8430 (social security administration) in addition to existing verticals.
- Any future attempts to add claim approval/benefit determination will be rejected (hard invariant blocks it).
- Operators must maintain a clear human approval process for appeals and low-confidence intakes.
- The actor is simpler and safer than a system that attempts to determine eligibility (pure gains in simplicity; zero losses in functionality, since determination was never the goal).

## Registry Entry

Update `kotoba-lang/industry` registry entry for ISIC `8430`:
```edn
:isic-8430 {:maturity :implemented
            :repo "cloud-itonami/cloud-itonami-isic-8430"
            :domain :social-security/administration}
```

**Reference**: This ADR mirrors ADR-2607142000 (8422 Defence Procurement), replicating the actor pattern for a different domain while maintaining the same safety discipline and governance structure.
