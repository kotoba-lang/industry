# ADR-2607141830: cloud-itonami-isic-1071 (bakery products) plant-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage)

## Context

ADR-2607122200 established ISIC Wave 3 (production/food-manufacturing) coverage
with bakery products (1071) as a non-robotics, plant-operations-coordination
actor. Prior reference `gftdcojp/cloud-itonami-C1071` is dead and unhosted.
This ADR codifies the fresh scaffold & implementation.

Bakery manufacturing operations span: ingredient receipt & testing, formulation
(mixing), baking (time-temperature profiles), product inspection, allergen
labeling, shipment coordination. **CRITICAL exclusions**: direct oven control
(baking-line operation remains exclusive to plant staff), mixing-machine
sequencing, and ingredient procurement decisions remain the plant operator's
exclusive human authority — this actor only coordinates back-office batch
tracking, equipment maintenance scheduling, food-safety escalations, and
shipment logistics.

## Decision

Scaffold a complete plant-operations-coordination actor
(`cloud-itonami-isic-1071`) for bakery products manufacturing:

1. **`bakeryops.governor`** — independent constraint layer with HARD checks:
   - spec-basis: proposal must cite a jurisdiction's regulatory requirements.
   - evidence-complete: batch's documentation checklist must include formulation
     record, baking log, temperature log, moisture test, allergen declaration,
     weight check.
   - baking-temp-in-range: batch's actual baking temperature must fall within
     the product's safe baking window (e.g., 190–210°C for white loaves).
   - baking-time-within-limit: batch's actual baking time must not exceed
     product's maximum (pathogen/starch-degradation risk).
   - moisture-in-tolerance: final product moisture must fall within target ±
     tolerance (e.g., 38% ± 2% for bread).
   - sanitation-score-sufficient: plant's equipment/area sanitation score must
     meet minimum (75 for bakery).
   - scale-calibration-current: mixing scale's calibration must not be overdue
     (recalibration required every 180 days; formulation accuracy at risk).
   - weight-variance-acceptable: finished-product weight variance must not
     exceed tolerance (50g; scale-drift risk).
   - allergen-label-complete: batch's allergen declaration must cover all
     ingredients in the formulation used.
   - safety-flag-resolved: any open food-safety concern (suspected allergen
     cross-contact, contamination) must be resolved before batch logging.
   - not-already-processed: same batch cannot be logged twice.
   - shipment-not-finalized: same batch's shipment cannot be finalized twice.

   ESCALATION invariants (always human sign-off):
   - `:flag-food-safety-concern` — surface allergen/contamination concern.
     Always escalates; requires immediate human review.
   - `:log-production-batch`, `:coordinate-shipment` — real-world actuation.
     Always escalates even when hard checks pass.
   - low confidence (< 0.6).

2. **`bakeryops.facts`** — reference data (pure, deterministic):
   - Product types (white loaf, whole wheat, croissants, butter cookies, etc.)
     with baking-temp ranges, time windows, moisture targets, common allergens.
   - Jurisdictions (JP prefectural, US FDA, EU EFSA, etc.) with allergen
     declarations, record-retention periods, labeling requirements.
   - Ingredient allergen map: flour/wheat, eggs, milk, nuts, sesame, soy, etc.
     with primary allergen and cross-contact risk flags.

3. **`bakeryops.registry`** — safety predicates (independent, unconditional):
   - `baking-temp-out-of-range?`, `baking-time-exceeded?`,
     `moisture-out-of-target?`, `sanitation-score-insufficient?`,
     `scale-calibration-overdue?`, `weight-variance-excessive?`,
     `allergen-label-risk?`

4. **`bakeryops.store`** — state contract:
   - Batch queries: `production-batch`, `batch-already-processed?`,
     `batch-shipment-finalized?`
   - Batch mutations: `log-batch`, `finalize-shipment`
   - Audit ledger: `append-fact`, `audit-trail`

5. **`bakeryops.phase`** — state machine:
   - Phase sequence: `intake → design → produce → inspect → package → audit → archived`
   - Valid transitions: forward-only (no backtracking)

6. **Operations supported**:
   - `:log-production-batch` — batch logging with baking parameters, evidence,
     allergen declaration.
   - `:schedule-maintenance` — equipment maintenance proposal.
   - `:flag-food-safety-concern` — escalate allergen/contamination concern.
   - `:coordinate-shipment` — outbound product shipment coordination.

7. **Tests** — 35 tests green (facts, registry, governor, store, phase suites):
   - Product type & jurisdiction lookups
   - Ingredient allergen traceability
   - Formulation allergen collection
   - Baking parameter safety predicates (temp, time, moisture)
   - Sanitation & scale-calibration checks
   - Weight variance validation
   - Allergen label completeness
   - Evidence checklist validation
   - Governor hard violations (all 10 rules tested)
   - Low-confidence escalation
   - High-stakes escalation (log-production-batch, coordinate-shipment)
   - Already-processed & already-finalized guards
   - Batch state mutations
   - Audit trail integrity
   - Phase transition logic

8. **Documentation** — README.md with CRITICAL scope exclusions prominently
   stated; blueprint.edn metadata; standard OSS files (CONTRIBUTING, GOVERNANCE,
   SECURITY, CODE_OF_CONDUCT). AGPL-3.0-or-later open-source release.

## Consequences

(+) Bakery products (ISIC 1071) plant-operations-coordination is now
implemented and fully tested. First non-robotics food-manufacturing actor in
Wave 3 coverage, demonstrating the pattern's applicability to low-automation
production environments.

(+) Scope boundaries (direct oven control, mixing-machine sequencing,
ingredient procurement decisions permanently excluded) are hardcoded in
governor checks and documented in README.

(+) Allergen traceability and food-safety escalation (`:flag-food-safety-concern`
always human) are core design invariants, not add-ons.

(+) Portable `.cljc` implementation with no JVM-only constructs; langgraph
integration ready for state-machine deployment.

(-) Real persistent store (DuckDB, Datomic, etc.) is a follow-up; tests use
in-memory.

(-) Plant operator sign-off (langgraph StateGraph interrupt) is scaffolding;
production integration with workflow platform pending.

## Verification

- `cloud-itonami-isic-1071`: 35 tests (facts, registry, governor, store,
  phase), all green, no external deps beyond langgraph (portable .cljc).
- Commit `cbbd65c` pushed to `cloud-itonami/cloud-itonami-isic-1071`'s `main`.
- Registry entry (kotoba-lang/industry) updated: `:repo` → live GitHub URL,
  `:maturity` → `:implemented`.
