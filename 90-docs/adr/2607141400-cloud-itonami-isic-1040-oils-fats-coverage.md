# ADR-2607141400: cloud-itonami-isic-1040 (Oils/Fats Manufacturing) – Full Blueprint & Reference Implementation

**Date**: 2026-07-14  
**Status**: Accepted  
**Scope**: cloud-itonami fleet, Wave 3 food production vertical (ISIC 1040)

## Context

ISIC 1040 (Manufacture of vegetable and animal oils and fats) is a food-manufacturing
vertical requiring strict quality and food-safety governance. Upstream: ADR-2607121000
(reverse-toposort rollout plan) placed ISIC 10-12 (food division) in Wave 3
(production/robotics). Downstream: ADR-2607122200 (Ishokuju blueprint satellites)
designated 1040 as one of the 衣食住 trio's 食 (food) verticals.

Prior state: 1040 was listed in the industry registry as `:maturity :spec` with a dead
repository link (`gftdcojp/cloud-itonami-C1040`). It had no working implementation.

## Decision

Promote ISIC 1040 from `:spec` (blueprint-only) to `:implemented` (full reference
implementation live). The production-ready actor—**OilsFatsOps-LLM ⊣ Oils/Fats
Manufacturing Governor**—is now publicly available at
`https://github.com/cloud-itonami/cloud-itonami-isic-1040`.

### What is implemented

**Architecture**: Advisor + Independent Governor (same pattern as 1010/1050/3600/3830).

- **Advisor layer**: LLM (mock in repo, production: langchain + Claude backend) proposes
  batch operations (log-production-batch, schedule-maintenance, coordinate-shipment,
  flag-food-safety-concern).

- **Governor layer**: Unconditional, auditable food-safety checks:
  - FFA (free fatty acid, rancidity indicator) within jurisdiction limits (0.3–0.6%)
  - Peroxide value (oxidation indicator) within limits (5.0–10.0 mEq/kg)
  - Storage temperature within product spec (–5°C to 35°C depending on oil type)
  - Holding time not exceeded (60–90 days depending on jurisdiction)
  - Sanitation score ≥ 85–90 (jurisdiction-specific)
  - Metal detector pass + microbial test pass
  - Contamination flag resolved (hard block, never overridable)
  - Evidence checklist complete per jurisdiction

- **Regulatory coverage**: US (USDA-FSIS, 21 CFR 184), EU (EFSA, Directive 91/321/EEC),
  Japan (MHLW, 食品衛生法). Quality limits baked into `registry.cljc` (auditable,
  defensible under inspection).

- **State machine**: operation.cljc (stateless business logic) + store.cljc (append-only
  audit ledger). No side effects, ready for langgraph StateGraph integration.

- **Test coverage**: 56 tests covering Governor hard-blocks, registry checks, store ops,
  facts/jurisdictions, operation flow. All `.cljc` (JVM + ClojureScript portable).

### Scope boundaries

**In scope** (this actor handles):
- Batch intake, assay scheduling, production logging
- Maintenance scheduling (preventative)
- Shipment coordination (cold-chain logistics)
- Contamination flagging (always escalates to human)

**Out of scope** (remains exclusive to operators + robotics):
- Equipment control (extraction/refining pressure, temp, centrifuge speed)
- Food-safety certification (regulatory authority only)
- Quality assay performance (lab/testing infrastructure)

### Rollout phases

```
Phase 0 (now): Blueprint + mock advisor + test suite ✓
Phase 1: langgraph StateGraph integration (planned)
Phase 2: All proposals escalate to human (intake-propose phase)
Phase 3: Independent governance (production phase, robotics gate)
Phase 4: Optimization (predictive quality trends)
```

## Consequences

- Industry registry entry 1040 now points to production-ready repo (`cloud-itonami-isic-1040`)
- `:maturity :implemented` signals Wave 3 readiness (pending robotics premise gate)
- Operating states updated: `:spec`-oriented → operational
  (intake, assay, schedule, coordinate, package, audit)
- Optional technologies added: `:telemetry :optimization`

## References

- **ADR-2607121000**: Reverse-toposort rollout plan (Wave 3: food production/robotics)
- **ADR-2607122200**: Ishokuju blueprint satellites (衣食住 food vertical)
- **ADR-2607011000**: Robotics premise (fleet infrastructure gate)
- **Repository**: https://github.com/cloud-itonami/cloud-itonami-isic-1040
- **ISIC Rev. 5 Class 1040**: https://en.wikipedia.org/wiki/ISIC
- **21 CFR 184**: FDA GRAS oils & fats standards
- **Commission Directive 91/321/EEC**: EU oils/fats food rules
- **食品衛生法 (FSL)**: Japanese food sanitation law

---

**Approved by**: Jun Kawasaki (cloud-itonami lead)  
**Date**: 2026-07-14
