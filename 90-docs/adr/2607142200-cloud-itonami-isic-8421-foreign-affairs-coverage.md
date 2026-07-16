# ADR-2607142200: ISIC 8421 (Foreign Affairs and Consular Services) Coverage

Date: 2026-07-14
Status: Accepted

## Summary

Implement ISIC Rev.5 **8421** (Foreign Affairs and Consular Services Administration) as an itonami actor: a closed-allowlist robot advisor for visa application intake, consular service scheduling, diplomatic correspondence drafting, and travel advisory logging, gated by an independent Governor that enforces hard invariants (applicant provenance, visa decision prohibition, binding statement prohibition, policy prohibition) and escalation logic (visa intake, security concerns, low confidence).

## Context

Foreign ministries and consular services perform administrative work distinct from diplomatic negotiation or visa decisions. The challenge is automating visa application intake, consular scheduling, and correspondence drafting while **never** proposing visa grants/denials, binding diplomatic statements, or policy decisions. The actor supports the human decision-maker, never replaces them.

## Decision

Scaffold `cloud-itonami-isic-8421` (public, AGPL-3.0-or-later) as an itonami actor using the langgraph-clj StateGraph pattern:

### Closed Allowlist (Advisor Layer)
- `:intake-visa-application` — applicant document verification
- `:schedule-consular-service` — passport renewal, notarization scheduling
- `:draft-correspondence` — diplomatic correspondence draft (human signature required)
- `:log-travel-advisory` — travel advisory data logging

### Hard Invariants (Governor Layer)
1. **Applicant provenance**: applicant must be registered
2. **No direct actuation**: `:effect` must be `:propose` only
3. **Scope boundary**: proposals touching visa decisions, binding statements, or policy are structurally forbidden (`:op :unknown` always `:hold`s)

### Escalation Logic
- `:intake-visa-application` — human reviews intake, applicant documents, background, then decides grant/deny
- `:flag-security-concern` — human judgment on security issues
- Low confidence — forces human review

### Graph
```
:intake → :advise → :govern → :decide -+→ :commit           (ok? true)
                                         +→ :request-approval  (escalate? true, interrupt)
                                         +→ :hold              (hard? true)
```

## Consequences

### Positive
- **No scope drift**: only these 4 ops are in the advisor's vocabulary
- **LLM safety**: out-of-scope proposals (visa decisions, binding statements) yield `:confidence 0.0` and hold
- **Clear human authority**: visa decisions and policy remain exclusively human
- **Auditability**: every proposal leaves a ledger entry; hard holds confirm no forbidden ops reached approval

### Negative
- **Strictness**: scope boundaries require code review to expand
- **LLM discipline**: system prompt must carefully encode allowlist

## Implementation

- Repository: `cloud-itonami/cloud-itonami-isic-8421` (public)
- Stack: `.cljc` (portable Clojure), langgraph-clj, test-runner
- Tests: 11 tests, 35 assertions, all green (governor, actor, escalation, hard-hold scenarios)
- Maturity: `:implemented` (full StateGraph, reference implementation ready)

## References

- ADR-2607011000: itonami Actor Pattern
- ADR-2607142000: ISIC 8422 (Defence Procurement) — reference implementation
- CLAUDE.md: Actors section
- Repository: https://github.com/cloud-itonami/cloud-itonami-isic-8421
