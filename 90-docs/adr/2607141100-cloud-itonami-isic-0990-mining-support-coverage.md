# ADR-2607141100: Cloud-itonami ISIC-08 0990 Mining Services Contractor Coordination Actor

**Date**: 2026-07-14 11:00 UTC  
**Status**: Accepted  
**Author**: Jun Kawasaki, Claude Sonnet 5

## Summary

Open Occupation Blueprint for **ISIC-08 0990**: Support activities for other mining and quarrying.

Scaffolded a fully-implemented cloud-itonami actor (reference implementation,
`:maturity :implemented` in registry) for mining services contractor coordination.
The actor gates crew dispatch, service order intake, site logistics, and safety
logging through an independent Governor that enforces a hard boundary: mine operators
retain exclusive authority over blasting, extraction, and mine-safety decisions;
contractors operate their back-office coordination and field logistics under human-in-the-loop
escalation for safety-sensitive operations.

## Context

ISIC-08 0990 classifies support activities for mining and quarrying — drilling
contractors, equipment rental, logistics crews, drilling fluid services,
NOT the mine operator's extraction/blasting authority. A mining services company
needs:

1. **Service order intake** — verify client/site records before scheduling
2. **Crew dispatch** — schedule teams to mine-sites under logistics constraints
3. **Site logistics coordination** — transport, supply procurement, crew movement
4. **Safety incident logging** — escalate to human review, never resolve autonomously

The actor must enforce a hard organizational boundary: no contractor software
makes blasting, extraction, mine-safety authority, or equipment operation decisions.
Violations are instant hard blocks with no override path.

## Decision

Implemented a full itonami Actor following ADR-2607011000 / CLAUDE.md's Actors
section:

- **Actor**: `cloud-itonami-isic-0990` repo, `mining-services.actor` namespace
- **Graph**: langgraph-clj `StateGraph` with five nodes: `:intake` → `:advise`
  → `:govern` → `:decide` → `{:commit | :request-approval | :hold}`
- **Governor**: `mining-services.governor/check` (pure function, no side effects),
  wired as `:govern` node. Enforces hard and escalation invariants.
- **Advisor**: `mining-services.advisor` protocol; `mock-advisor` (deterministic,
  default) proposes ops; `llm-advisor` wraps a real ChatModel with
  parse-failure-to-zero-confidence safety.
- **Store**: `mining-services.store` protocol; `MemStore` (deterministic,
  zero-dep, default in tests/CI); Datomic/kotoba-server-backed impl can be
  swapped without touching actor/governor graph.

### Scope Boundaries

**Contractor-side ✓ (in scope)**:
- Service order intake and client/site verification
- Crew dispatch scheduling
- Site logistics coordination (transport, crew movement, supplies)
- Safety incident logging (always escalates to human)

**Operator-side ✗ (hard-blocked, never override)**:
- Blasting decisions (powder type, load size, timing, sequence)
- Extraction sequencing (excavator control, ore/waste classification)
- Mine-safety authority (ventilation adequacy, gas monitoring, hazard classification)
- Equipment operation (hauler control, drill control, hoist sequencing)
- Hazmat handling authorization
- Subsurface geology interpretation

Any proposal with `:op` ∈ `{:blast, :extract, :mine-safety-auth, :excavate-sequence,
:ore-grade-assess, :ventilation-auth, :hazmat-handle, :equipment-operation}`
is flagged `:operator-class-blocked` and routed to `:hold` with no escalation override.

### Proposal Ops (all `:effect :propose` only)

- `:intake-service-order` — verify client/site provenance; confidence depends on
  order completeness
- `:schedule-crew-dispatch` — propose crew assignment to site; confidence low
  if skills don't match site demands
- `:coordinate-site-logistics` — propose supply/transport coordination; escalates
  if high-risk site
- `:log-safety-incident` — always escalates to human review (governor invariant);
  never silently resolved

### Governor Invariants

**Hard** (`:hard? true` → `:hold`, irreversible):
1. Contractor provenance — `:contractor-id` must be registered.
2. Site provenance — any op touching a site must reference a registered mine-site.
3. No actuation — `:effect` must be `:propose`; no raw store writes.
4. **No operator-class ops** — instant block, no override, core domain boundary.

**Escalation** (`:escalate? true` → `:request-approval`, human sign-off):
1. `:log-safety-incident` — ALL safety logging, always human approval.
2. High-risk site — dispatch to `:risk-level :high` site, human approval.
3. Low confidence — advisor confidence < 0.6, human approval.
   (LLM parse failures = 0.0 confidence, forces escalation.)

### Tests (All Green)

- `mining_services.actor-test/commits-a-clean-low-risk-request`
- `mining_services.actor-test/holds-on-unregistered-contractor-without-committing`
- `mining_services.actor-test/holds-on-blasting-operation-hard-block`
- `mining_services.actor-test/interrupts-then-commits-on-safety-incident-approval`
- `mining_services.governor-test/accepts-clean-low-confidence-proposal`
- `mining_services.governor-test/hard-blocks-unregistered-contractor`
- `mining_services.governor-test/hard-blocks-blasting-operation`
- `mining_services.governor-test/escalates-safety-incident`
- `mining_services.governor-test/escalates-high-risk-site-dispatch`
- `mining_services.governor-test/escalates-low-confidence-proposal`

Ran 10 tests containing 34 assertions. **0 failures, 0 errors.**

## Consequences

- **Safety**: Hard blocks enforce the contractor-operator boundary. A mining
  services company cannot accidentally (via misconfiguration, LLM prompt
  injection, or malicious actor modification) dispatch blasting or extraction
  decisions. These are operator-exclusive and permanently blocked at the
  governor level.

- **Auditability**: Every proposal, verdict, and disposition (commit, escalate,
  hold) is appended to the ledger, regardless of outcome. No silent rejections
  or lost decisions.

- **Robotics-ready**: The actor is designed for a robot performing physical
  coordination work (crew dispatch, logistics) while the governor gates safety
  and authority boundaries. The robot can operate at high throughput without
  the risk of inadvertently stepping into operator-exclusive decisions.

- **Extensibility**: Store and Advisor are protocols. New implementations (e.g.,
  Datomic-backed store, real LLM advisor, external robotics APIs) can be
  integrated without rewriting the actor, governor, or graph wiring.

- **Forkability**: This is a reference implementation under AGPL-3.0-or-later.
  Mining companies can fork, rebrand, extend, and run as an independent
  OSS business without renting a closed SaaS.

## References

- **ADR-2607011000**: itonami actor pattern (StateGraph + separate Governor +
  human-in-the-loop interrupt/resume via checkpointing)
- **ADR-2607062330 / ADR-2607062400**: kotoba wasm runtime and WASM component
  contracts
- **ADR-2607100030**: `.cljc` portable runtime priority (kotoba wasm > clojurewasm
  > ClojureScript > nbb > JVM/bb)
- **CLAUDE.md § Actors**: langgraph-clj StateGraph pattern, Governor node
  semantics, append-only audit ledger discipline
- **kotoba-lang/occupation** registry: ISIC-08 0990 capability resolution (registry
  entry to follow in separate PR)

## Related

- `cloud-itonami-isic-0910`: Petroleum services contractor coordination (reference implementation for 0990 pattern mirror)
- `cloud-itonami-isic-*`: Other occupation actors in the cloud-itonami portfolio

## Repo

- **Public GitHub**: `https://github.com/cloud-itonami/cloud-itonami-isic-0990`
- **Repo ID in registry**: `"cloud-itonami-isic-0990"`
- **Maturity**: `:implemented` (full reference implementation, tests green, pushed)
- **License**: AGPL-3.0-or-later

## Appendix: Blasting / Extraction Scope Exclusion (Critical)

This ADR and the actor implementation **must never** be interpreted as authorizing
any software (this actor, an LLM, a robot) to make or propose:

- **Blasting sequencing**: powder type, load size, timing, delay patterns, or
  blast design. This is operator-exclusive domain knowledge.
- **Extraction decisions**: which ore/waste to move, in what sequence, under
  what equipment. Operator decision.
- **Mine-safety authority**: ventilation adequacy, gas level monitoring,
  hazard assessment, personnel safety protocols. Operator and mine-safety
  personnel authority.
- **Equipment operation**: hauler dispatch, drill positioning, hoist control,
  or any real-time equipment coordination. Operator/supervisor authority.

If a proposal or request enters the actor with any of these `:op` values
(or any similar operator-domain operation), the Governor **immediately
routes to `:hold`** with no escalation override and logs the violation.
This is intentional, permanent, and load-bearing for mining-sector safety
and regulatory compliance.

Mining-services contractor coordination is a **support role**: your company
provides labor, equipment rental, logistics, and ancillary services. The
mine operator alone makes extraction decisions. This boundary is
non-negotiable and this implementation enforces it at the language/graph level.
