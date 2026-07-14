# ADR-2607142700: cloud-itonami ISIC 1910 — Coke Oven Products Manufacturing Coordination Actor

**Status:** Implemented

**Date:** 2026-07-14

**Author:** Cloud Operations

## Summary

We implement a new cloud-itonami actor blueprint for ISIC Rev.5 1910 (Manufacture of coke oven products). This actor coordinates plant operations logistics and compliance workflow, NOT process control or furnace operation.

Public GitHub: https://github.com/cloud-itonami/cloud-itonami-isic-1910

## Context

Coke-oven manufacturing is a capital-intensive, safety-critical industrial process requiring:
- Raw coal supplier verification and batch tracking
- Production-run scheduling proposals (human engineers execute)
- Byproduct (tar, gas, chemicals) handling and shipment coordination
- Emissions monitoring and compliance reporting (threshold exceedances must always escalate)
- Strict separation: the LLM advisor proposes, the governor evaluates against hard safety gates, humans approve real-world actions

Existing template: ISIC 3520 (Manufacture of gas) provides the same pattern — regulated industrial production with hard safety gates and protected-recipient protections. Coke manufacturing mirrors this shape but applies to a different industrial domain (solid-fuel vs. gas distribution).

## Decision

1. **Scaffold cloud-itonami-isic-1910 as OSS (AGPL-3.0-or-later)**
   - Fork-able blueprint any qualified coke-plant operator can deploy
   - Portable `.cljc` source (JVM/ClojureScript/browser WASM)
   - langgraph-clj StateGraph for supervised state machine
   - Append-only audit ledger (Datomic or event store)

2. **Scope: Logistics and Compliance, NOT Process Control**
   - IN: Coal intake verification, production-run proposals, byproduct coordination, emissions compliance logging
   - OUT (permanently forbidden): Furnace charging/discharging, coking-process control, temperature/timing/pressure decisions, emissions-system hardware operation
   - Hard block in governor: any proposal mentioning process-control keywords (`furnace`, `charge`, `discharge`, `temperature-setpoint`, etc.) is immediately rejected

3. **Governor Contract with Hard and Soft Gates**
   - **Hard violations (no override):**
     - No spec-basis citation (never invent jurisdiction requirements)
     - Process-control keywords present (furnace operation is engineer exclusive)
     - Emissions threshold exceedance flagged (always escalate, never silent log)
     - Supplier/material not verified
   - **Soft violations (human can approve):**
     - Low confidence (< 0.6)
     - High-stakes actuation (production-run scheduling, emissions reporting)

4. **Jurisdiction-Specific Compliance (Honest Catalog)**
   - Japan: Gas Utility Regulation Act, Industrial Safety and Health Act, Air Pollution Control Law
   - USA: MSHA, Clean Air Act Title V, OSHA standards
   - UK: Environmental Permitting Regulations, Health and Safety at Work Act
   - Catalog is deliberately small (3 jurisdictions) to honestly represent coverage

5. **Registry Entry**
   - kotoba-lang/industry: ISIC `1910` → `:implemented` status
   - Pinned to cloud-itonami-isic-1910 `main` branch default

## Implementation

### Repository Structure

```
cloud-itonami-isic-1910/
├── src/coke/
│   ├── advisor.cljc        (LLM proposals)
│   ├── governor.cljc       (safety gates)
│   ├── facts.cljc          (jurisdiction catalog)
│   ├── phase.cljc          (state graph topology)
│   ├── registry.cljc       (proposal drafts)
│   ├── store.cljc          (in-memory state)
│   └── sim.cljc            (demo harness)
├── test/coke/
│   ├── governor_contract_test.clj
│   ├── facts_test.clj
│   ├── phase_test.clj
│   └── store_contract_test.clj
├── docs/
│   ├── adr/0001-architecture.md
│   ├── business-model.md
│   └── operator-guide.md
├── blueprint.edn
├── deps.edn
├── README.md
├── CONTRIBUTING.md
├── GOVERNANCE.md
├── SECURITY.md
└── CODE_OF_CONDUCT.md
```

### Test Coverage

```
Ran 25 tests containing 63 assertions.
0 failures, 0 errors.
Linting: 0 errors
```

**Key tests:**
- `spec-basis-hard-gate` — no proposals without official spec-basis citations
- `process-control-block` — furnace/charging/temperature keywords immediately rejected
- `emissions-threshold-exceedance-escalation` — threshold exceedance ALWAYS holds, never silent log
- `already-verified-blocks-intake` — supplier/material verification mandatory
- `actuation-requires-escalation` — production scheduling and emissions reporting require human sign-off

### Portability

- **Source:** All `.cljc` (Clojure + ClojureScript compatible)
- **Runtime:** langgraph-clj StateGraph (JVM, Chicory, browser WASM, Node.js)
- **Dependencies:** langgraph-clj and langchain-clj (both zero-dependency, portable)
- **No platform-specific code:** No JVM-only, no Node-only, no Go, no Rust

## Scope Boundaries

### What This Actor Does
- Verify coal suppliers and batch quality
- Propose production-run schedules (human engineers approve and execute)
- Coordinate byproduct shipments
- Log emissions monitoring data
- Escalate emissions threshold exceedances to human operators
- Provide audit ledger of all decisions with spec-basis citations

### What This Actor Does NOT Do
- Operate furnaces or discharge coke
- Control coking process (temperature, timing, pressure, composition)
- Operate emissions-system hardware (monitoring only; hardware operation is engineer exclusive)
- Make process-engineering decisions
- Silently log threshold-exceeded emissions reports

Any proposal mentioning process-control operations is a **hard block** (rejected even with human approval).

## Compliance

- **ISIC:** Rev.5 1910 (Manufacture of coke oven products)
- **License:** AGPL-3.0-or-later (public good, forks must contribute improvements)
- **Spec-basis Requirement:** Every jurisdiction requirement is backed by official citations (no invented standards)
- **Audit Trail:** All decisions recorded with timestamp, advisor confidence, governor violations, human approver

## Deployment

Operators can:
1. Fork https://github.com/cloud-itonami/cloud-itonami-isic-1910
2. Deploy on-premise or cloud (langgraph-clj runs on JVM or browser)
3. Integrate with their coal-supply system, emissions monitors, and scheduling tools
4. Run tests and lint: `clojure -M:test && clojure -M:lint`
5. Customize `facts.cljc` for their jurisdiction
6. Contribute improvements back (AGPL-3.0-or-later)

## Addenda

### Addendum 1: Comparison to ISIC 3520 (Manufacture of Gas)

Both follow the same cloud-itonami pattern:
- LLM advisor proposes → Governor evaluates → Human approves → Audit log records

**Differences:**
- 3520: Customer intake, meter provisioning/suspension, life-support protected-recipient gates
- 1910: Coal supplier intake, production-run scheduling, emissions threshold escalation

**Similarities:**
- Both have hard safety gates (no spec-basis, protected operations)
- Both escalate high-stakes actuation to human
- Both use jurisdiction-specific compliance catalogs
- Both forbid autonomous real-world actuation

### Addendum 2: Process-Control Boundary Enforcement

The hardest safety gate is process-control blocking. Implementation:
- Define keyword set: `furnace`, `charge`, `discharge`, `temperature`, `setpoint`, `parameters`, etc.
- Scan proposal detail text for these keywords (case-insensitive)
- If found: `:process-control-forbidden` hard violation (holds? = true)
- Tests verify: "Process-control proposal should hold" passes

This boundary is non-negotiable: an LLM cannot make process-engineering decisions, even with human approval, because the human is approving the *proposal*, not the *engineering decision*.

### Addendum 3: Emissions Threshold Exceedance Never Silent

If an emissions report shows any monitored value (SO2, NOx, particulates) exceeding its jurisdiction's threshold:
- Governor evaluation: `:emissions-threshold-exceedance` hard violation
- Proposal holds? = true (escalated to human)
- Human must investigate and respond
- Record is never silent-logged (impossible to log without escalation)

Tests verify: "Emissions report with exceedance should hold" passes.

### Addendum 4: Future Expansion (Out of Scope)

Potential future ISIC actors following the same pattern:
- ISIC 2410: Manufacture of steel pipes
- ISIC 3620: Water treatment and supply
- ISIC 3700: Sewerage and wastewater
- ISIC 3811: Collection and treatment of solid waste

Each would have domain-specific hard gates and compliance catalogs.

## Resources

- **Public repo:** https://github.com/cloud-itonami/cloud-itonami-isic-1910
- **Reference (3520):** https://github.com/cloud-itonami/cloud-itonami-isic-3520
- **Registry:** kotoba-lang/industry ISIC `1910` entry
- **Skill (actor pattern):** `/build-actor` in Claude Code
- **Langgraph-clj:** https://github.com/com-junkawasaki/langgraph-clj

## Decision

**APPROVED.** Implement cloud-itonami-isic-1910 as OSS blueprint with hard process-control and emissions-threshold-exceedance gates. Registry entry points to https://github.com/cloud-itonami/cloud-itonami-isic-1910 main branch.
