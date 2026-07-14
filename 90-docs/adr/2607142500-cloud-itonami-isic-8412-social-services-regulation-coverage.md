# ADR-2607142500: Cloud-Itonami ISIC 8412 (Social Services Regulatory Administration) Coverage Implementation

**Status**: Implemented `:maturity :implemented`

**Timestamp**: 2026-07-15 01:00

**Context**: Expand cloud-itonami actor coverage to ISIC Rev.5 8412 — regulation of health care, education, cultural services, and social service providers.

## Problem

Regulatory agencies (health departments, education boards, social service licensing offices) manage hundreds or thousands of providers: hospitals, schools, museums, care facilities. Current workflow:

- Manual spreadsheet-based provider tracking
- Closed regulatory SaaS (high cost, vendor lock-in, no audit transparency to regulated entities)
- Fragmented records across multiple systems
- No independent audit trail

ISIC 8412 is the regulatory **support** function — not the decision-maker itself, but the administrative backbone.

## Decision

Implement `cloud-itonami-isic-8412` as a langgraph-clj StateGraph actor, following the pattern established by ISIC 8422 (Defence Procurement):

1. **`Advisor` (proposal generation)**: Takes regulatory/compliance requests (inspection scheduling, compliance reporting, violation logging, licensing-application drafting), produces proposals. Swappable: `mock-advisor` (deterministic) or `llm-advisor` (LLM-backed).

2. **`Governor` (policy enforcement)**: Pure function, wired as separate `:govern` node. Enforces hard invariants:
   - Provider must be registered
   - Proposal `:effect` must always be `:propose` (never actuation)
   - Permanently forbidden: license issuance/suspension/revocation, binding regulatory determinations, access to protected personal data (HIPAA, FERPA)
   
   Escalation invariants (human interrupt-before):
   - Violation flagging (always escalated)
   - Licensing-application drafting (high stakes, always escalated)
   - Low confidence (< 0.6)

3. **`Store` protocol**: `MemStore` (in-memory, deterministic); Datomic/kotoba-server-backed swappable.

4. **`StateGraph` (langgraph-clj)**:
   ```text
   :intake -> :advise -> :govern -> :decide -+-> :commit           (:ok? true)
                                              +-> :request-approval  (:escalate? true, interrupt-before)
                                              +-> :hold              (:hard? true)
   ```
   Checkpointed for human-in-the-loop resume.

## Scope Boundaries — EXPLICITLY OUT OF SCOPE

**This actor is a SUPPORT tool only, not the regulator.**

### What it DOES

- Provider/facility registration and verification
- Inspection scheduling and coordination (does not perform inspection)
- Compliance-report intake and logging
- Violation logging and escalation
- Licensing-application drafting (on behalf of agency staff, not issuance)
- Audit trail and record-keeping

### What it DOES NOT (permanently forbidden, no path to approval)

- **License issuance, suspension, or revocation** — reserved exclusively to human regulators
- **Binding regulatory determinations** — cannot determine compliance status, impose sanctions, make enforceable decisions
- **Confidential personal data access** — no access to patient health records, student education records, service-user data (HIPAA, FERPA)
- **Facility inspections** — does not inspect; only schedules and coordinates inspections performed by human inspectors

These are not "escalation-eligible" operations. They are structurally impossible in the actor's vocabulary (closed allowlist enforced at advisor + governor layers). Any proposal touching these categories triggers permanent `:hold`, no human approval path.

## Reference Implementation

Repository: [`cloud-itonami/cloud-itonami-isic-8412`](https://github.com/cloud-itonami/cloud-itonami-isic-8412)

- Source: `src/regulation/{store,advisor,governor,actor}.cljc` (portable `.cljc`, no JVM-only constructs, per CLAUDE.md)
- Tests: `test/regulation/{governor_test,actor_test}.clj` — 12 tests, 41 assertions, all green
  - Hard violations: unregistered provider, non-propose effect, forbidden operations
  - Escalations: violation flags, licensing applications, low confidence
  - Ok flows: inspection scheduling, compliance report logging
- Architecture: `docs/0001-architecture.md`

Tests all pass:
```bash
$ clojure -M:test
Running tests in #{"test"}
...
Ran 12 tests containing 41 assertions.
0 failures, 0 errors.
```

## Integration

Registered in [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC registry):
- **Entry**: `"8412"`
- **Repo**: `cloud-itonami/cloud-itonami-isic-8412`
- **Maturity**: `:implemented`
- **Status**: Public OSS (AGPL-3.0-or-later)

## Design Principles (mirroring ISIC 8422)

1. **Closed allowlist**: Operations are `{:register-provider :schedule-inspection :log-compliance-report :draft-licensing-application :flag-violation}`. No license decisions, no binding determinations.

2. **Advisor ↔ Governor separation**: Advisor produces proposal, governor enforces policy. Advisor has no notion of provider provenance or risk; governor is the independent system.

3. **Append-only audit ledger**: Every proposal/verdict/disposition logged, regardless of outcome (commit or hold). No mutable records, no "forgotten" decisions.

4. **Human-in-the-loop**: Escalations interrupt the graph with `interrupt-before :request-approval`, checkpointed for resume. No silent auto-escalations.

5. **Swappable backend**: Store protocol allows in-memory (tests) or persistent (Datomic) without changing actor code.

## Robotics Premise

All cloud-itonami verticals assume a robot performs domain work. Here: a document-handling and verification robot performs provider verification, inspection coordination, compliance documentation under the actor/governor gating. The robot never decides license questions or makes binding determinations.

A live operator console (robotics safety UI) is rendered in `docs/samples/operator-console.html` (pure-data HTML output of `kotoba.robotics.ui`).

## Addenda

(None yet)

## References

- ISIC Rev.5 Section 8412: Regulation of health care, education, cultural, social service activities
- ADR-2607011000: cloud-itonami Actor pattern (langgraph-clj StateGraph + Governor + audit ledger)
- CLAUDE.md § Actors: itonami actor design conventions
- Reference implementation (mirror): `cloud-itonami-isic-8422` (Defence Procurement)
