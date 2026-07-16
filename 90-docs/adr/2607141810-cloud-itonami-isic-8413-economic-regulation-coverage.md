# ADR 2607141810: ISIC 8413 Economic Regulation and Business Compliance Administration Blueprint

**Status**: ACCEPTED

**Timestamp**: 2026-07-14 18:10

## Summary

Scaffold and register the **ISIC Rev.5 8413** (Economic Regulation and Business Compliance Administration) itonami Blueprint actor as `cloud-itonami-isic-8413`. This actor provides autonomous advisory support for regulatory agencies managing business registration, permit intake, economic statistics collection, and complaint escalation — while maintaining strict architectural boundaries: the actor proposes, humans decide, and all outcomes are logged to an immutable audit ledger owned by the agency.

## Problem

Economic regulatory agencies (trade licensing, business registration, export compliance, consumer protection) currently rely on closed SaaS systems to manage permits, registrations, complaints, and compliance records. This creates:

- **Vendor lock-in**: agencies cannot easily migrate data or switch providers.
- **Audit trail opacity**: complex business logic is hidden in proprietary code.
- **Decision authority confusion**: SaaS systems often blur the line between advisory and binding decisions, creating ambiguity about who holds final authority (the agency or the vendor).

## Solution

Implement a forkable, open-source itonami Blueprint actor (AGPL-3.0):

### Repository

- **Name**: `cloud-itonami-isic-8413`
- **URL**: https://github.com/cloud-itonami/cloud-itonami-isic-8413
- **Entry point**: ADR-2607011000 (itonami Actor pattern), CLAUDE.md (Actors section)
- **Reference**: cloud-itonami-isic-8422 (Defence Procurement — same architectural pattern)

### Architecture

**Four permitted operations** (closed allowlist, enforced at Advisor vocabulary and Governor logic layers):

1. `:verify-business-registration` — intake and verify business registration records against a known registry.
2. `:intake-permit-application` — accept permit applications, verify completeness and applicant identity.
3. `:log-economic-statistic` — accept and log economic data (employment, revenue, trade volume).
4. `:flag-complaint` — accept and escalate compliance complaints.

**Permanent hard boundaries** (never overridable, no escalation path):

- `:no-vendor`: Business must be registered.
- `:no-actuation`: Proposal must be advisory-only (`:effect :propose`).
- `:scope-boundary`: Proposal must be in permitted allowlist; permanently forbidden:
  - Binding permit grant or denial (human only).
  - Setting binding tariffs, rates, or fees (human only).
  - Issuing binding regulatory rulings or directives (human only).
  - Economic sanctions or enforcement actions (human only).
  - Direct business registration record manipulation (intake/verify only, human approval required).

**Escalation invariants** (always human sign-off):

- All permit application intakes (`:intake-permit-application`).
- All complaint flags (`:flag-complaint`).
- Low advisor confidence (< 0.6).

### Technology Stack

- **Advisor**: Clojure protocol + mock (deterministic) + LLM wrapper.
- **Governor**: Pure function (policy layer, immutable verdicts).
- **Store**: Protocol-based (swappable: MemStore, PostgreSQL, Datomic).
- **Actor Graph**: langgraph-clj StateGraph with checkpointing (interrupt/resume for human approval).
- **Audit Ledger**: Append-only log, immutable once written, owned by the agency.

### Reference Implementation

- `src/regulation/actor.cljc` — StateGraph wiring.
- `src/regulation/governor.cljc` — Hard/escalation invariants (pure function).
- `src/regulation/advisor.cljc` — Closed-allowlist proposal generation.
- `src/regulation/store.cljc` — Ledger and record persistence (protocol + MemStore).
- Tests: 11 tests, fully green (governor invariants, actor graph wiring, escalation logic).

### Governance

- **Blueprint spec**: `blueprint.edn` (itonami metadata).
- **Scope enforcement**: Closed allowlist at three layers (Advisor vocabulary, Governor logic, audit trail).
- **Audit trail**: Complete, immutable, queryable by (timestamp, business-id, disposition).
- **Human-in-the-loop**: All escalations (permit intake, complaints, low-confidence) interrupt the actor graph and require explicit human approval via checkpointing.

### Operator Guide

Full documentation for deployment:

- `docs/operator-guide.md` — installation, configuration, Store integration, LLM advisor setup, monitoring.
- `docs/business-model.md` — revenue model, use cases (trade licensing, environmental compliance, export control).
- `docs/adr/0001-architecture.md` — technical architecture and invariants.

## Deployment Scenarios

### Trade Licensing Agency

1. Fork the blueprint and customize the Advisor to recognize trade license applications.
2. Integrate the Store with the national business registry (read-only).
3. Deploy on agency infrastructure.
4. Staff review permit applications (all escalated) via robotics safety console.
5. All intake, verification, and approval decisions are logged to an immutable ledger owned by the agency.

### Environmental Compliance Monitoring

1. Customize the Advisor to parse industrial emissions reports.
2. Integrate with the industrial registry and permit database.
3. Deploy on a government server (no vendor lock-in).
4. Automatically flag businesses with overdue compliance reports (Advisor escalates).
5. Human inspectors review and approve/escalate investigations.
6. Complete audit trail is owned by the ministry.

## Registry Coverage

ISIC 8413 is now **`:maturity :implemented`** in [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry):

```clojure
"8413" {:repo "cloud-itonami-isic-8413"
        :maturity :implemented
        :capabilities [:robotics :identity :forms :dmn :bpmn :audit-ledger]
        :example-use-case "Economic regulation and business compliance administration"}
```

## Consequences

### Positive

- **Regulatory transparency**: Agencies own their own audit trails.
- **No vendor lock-in**: Fork, customize, self-host, or use any LLM provider (no integration lock).
- **Scope clarity**: Hard architectural boundaries prevent the actor from ever making binding decisions.
- **Audit compliance**: Complete ledger of all proposals, verdicts, and outcomes is immutable and queryable.
- **Community reuse**: Baseline actor for all economic/business regulatory domains (trade, export, consumer protection, environmental).

### Constraints

- The actor is **advisory only** — it cannot grant, deny, or revoke permits; cannot set binding rates; cannot enforce penalties. All these remain human domain.
- First implementation uses in-memory MemStore; production deployments will integrate with existing regulatory databases (schema adaptation required).
- LLM Advisor is optional; mock advisor is sufficient for deterministic, rule-based workflows.

## References

- **itonami Blueprint model**: ADR-2607011000, CLAUDE.md (Actors section)
- **Reference implementation**: cloud-itonami-isic-8422 (Defence Procurement, identical architecture)
- **Repository**: https://github.com/cloud-itonami/cloud-itonami-isic-8413
- **Registry**: https://github.com/kotoba-lang/industry
