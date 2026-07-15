# ADR-2607154500: cloud-itonami-isic-0114 (growing of sugar cane) sugar-cane-plantation-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage), ADR-2607152600
(cloud-itonami-isic-0112 rice-growing, the module-structure template this
ADR mirrors -- rice, ISIC 0112, is the closest prior sibling class with an
own crop-specific field-record check), ADR-2607152500 (cloud-itonami-
isic-0111 cereal-growing, the original template both 0112 and this ADR
descend from).

## Context

Sugar-cane growing (ISIC Rev. 4 0114) spans: planting/ratoon-cycle/yield/
brix-test record-keeping, cane-field-operation (planting/fertilizing/
pre-harvest-burn/harvest) scheduling coordination, crop pest (borer)/
disease/drought-stress concern escalation, and seed-cane/fertilizer/
equipment procurement. Unlike the annual field crops covered by
cloud-itonami-isic-0111 (cereals) and cloud-itonami-isic-0112 (rice),
sugar cane is a **perennial** crop: after the first harvest of a freshly
planted ("plant cane") stand, the same rootstock regrows and is harvested
again across successive ratoon cycles ("ratooning") before the field is
eventually replanted -- this actor's field records track a ratoon-cycle
count rather than the annual per-season data model of its 0111/0112
siblings. **CRITICAL exclusions**: direct field-equipment operation and
finalizing a pre-harvest-burn or pesticide-application decision remain the
exclusive authority of the grower/agronomist -- this actor only
coordinates back-office record-keeping and logistics, never field-domain
actuation or agronomic decision-making.

`cloud-itonami/cloud-itonami-isic-0114` did not exist prior to this ADR
(`gh api repos/cloud-itonami/cloud-itonami-isic-0114` returned a GraphQL
"could not resolve to a Repository" 404 before scaffold) -- a fresh
scaffold, not a repair of a prior stray artifact.

## Decision

Implement a complete sugar-cane-plantation-operations-coordination actor
(`cloud-itonami-isic-0114`), mirroring `cloud-itonami-isic-0112`'s module
shape module-for-module, with sugar-cane-specific (perennial/ratoon-cycle)
adaptations:

1. **`caneops.governor`** (`CaneOperationsGovernor`) -- independent
   constraint layer with HARD checks (always hold, no override):
   - `field-not-registered` -- request's field-id must resolve to a
     registered cane field in the Store.
   - `no-execution` -- every proposal's `:effect` must be `:propose`;
     the governor never directly executes anything.
   - `equipment-or-decision-blocked` -- `:operate-field-equipment`,
     `:finalize-burn-decision` (NEW: pre-harvest-burn decisions --
     sugar-cane-specific), and `:finalize-pesticide-application` are
     unconditionally, permanently blocked regardless of confidence or
     cites.
   - `op-not-allowed` -- closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `field-record-invalid` -- `:log-field-record` with a non-positive
     acreage is rejected.
   - `ratoon-cycle-invalid` (NEW, perennial-crop-specific, replaces
     0112's `water-level-invalid`) -- `:log-field-record` with a negative
     ratoon-cycle count is rejected (zero -- a freshly planted "plant
     cane" cycle, before any ratoon regrowth -- is a valid observation).

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` -- ALWAYS escalates, any confidence
     (covers pest (borer)/disease/drought-stress).
   - `:order-supplies` above its category cost threshold (default 500;
     `caneops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`caneops.facts`** -- reference data (pure, deterministic): supply
   categories with cost thresholds (seed-cane, fertilizer, equipment
   including harvest machinery/irrigation pumps), sugar-cane-variety
   classification (hybrid, noble, energy-cane, chewing-cane), and a
   field-operation-types reference set (planting, fertilizing,
   pre-harvest-burn, harvest, ratooning -- informational, not a validated
   enum, spanning both the initial plant-cane cycle and successive ratoon
   cycles).

3. **`caneops.registry`** -- independent, unconditional pure predicates:
   `cost-exceeds-threshold?`, `acreage-non-positive?`,
   `ratoon-cycle-invalid?` (NEW), `confidence-below-floor?`.

4. **`caneops.store`** -- `Store` protocol + in-memory `MemStore`:
   `registered-field` lookup, `add-field` for tests/simulation.

5. **`caneops.advisor`** -- `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below (log-field-record
   now also carries `:ratoon-cycle`, `:yield`, `:brix`).

6. **`caneops.phase`** -- 0->3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during simulation);
   phase-1 forces always-escalate ops to escalate even when clean;
   phase-2/3 pass the Governor's disposition through unchanged.

7. **`caneops.operation`** -- composes advisor -> governor -> phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0111/0112's own stub status).

8. **`caneops.sim`** -- demo runner (`clojure -M:run`), with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-field-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** -- 35 tests / 107 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0111/0112's own test coverage shape). Identical
    test/assertion count to 0112, replacing the paddy water-level checks
    1-for-1 with ratoon-cycle checks (both add exactly one crop-specific
    HARD-checked numeric field on top of 0111's acreage-only baseline).

11. **Documentation** -- README.md/docs/business-model.md/
    docs/operator-guide.md describe the actual implemented API
    (`caneops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions), plus a dedicated
    "Perennial crop, ratoon-cropping" README section explaining the
    plant-cane/ratoon-cycle distinction from the annual siblings.
    blueprint.edn metadata; standard OSS files (CONTRIBUTING, GOVERNANCE,
    SECURITY, CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim
    from 0112).

## Consequences

(+) Sugar-cane growing (ISIC 0114) sugar-cane-plantation-operations-
coordination is now genuinely implemented and fully tested.

(+) Scope boundaries (direct field-equipment operation and finalizing
pre-harvest-burn or pesticide-application decisions permanently excluded)
are hardcoded in governor checks (`equipment-or-decision-blocked`) and
documented in README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern`, covering borer
infestation specifically -- sugar cane's signature pest -- always human)
is a core design invariant, not an add-on.

(+) Ratoon-cycle is a first-class, independently validated field (mirrors
how 0111 validates acreage and 0112 validates water-level), reflecting
that ratoon-cropping (successive harvests from the same rootstock without
replanting) is the operational trait that distinguishes a perennial crop
like sugar cane from the annual dryland/paddy crops covered by its 0111/
0112 siblings.

(+) Portable `.cljc` implementation with no JVM-only constructs
(`System/currentTimeMillis`, `Date`, etc. never used); `clojure -M:lint`
is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0111/0112's own stub status; production
integration pending.

(-) Brix (sugar-content) is logged as advisor payload data
(`caneops.advisor`'s `:value` map) but is not independently governor-
validated in this pass (no plausible-range check analogous to
acreage/ratoon-cycle) -- a possible follow-up if brix-range validation
proves necessary in practice.

## Verification

- `cloud-itonami-isic-0114`: `clojure -M:test` -> "Ran 35 tests
  containing 107 assertions. 0 failures, 0 errors." `clojure -M:lint` ->
  0 errors, 0 warnings.
- Commit `2382d72e9b29e0416a3b56c4fb785d8ecaf38a89` pushed to
  `cloud-itonami/cloud-itonami-isic-0114`'s `main` (the repo's only
  commit); local HEAD confirmed identical to `origin/main` tip via
  `git ls-remote origin main` immediately after push.
- Registry entry (`kotoba-lang/industry`) update: `"0114"` entry's
  `:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id` set to
  `cloud-itonami/cloud-itonami-isic-0114`, citing this ADR.
  `test/kotoba/industry_test.clj`'s pinned `:implemented` count bumped
  205 -> 206, live-recomputed via `(industry/maturity-summary)` (test
  runner failure diff `expected: (= 205 (:implemented m)) actual: (not (=
  205 206))`, not assumed from a stale comment or prior ADR's number).
  Landed via GitHub-API server-side merge on the first attempt (no 409),
  commit `e50597b84e2fc1397297aa34fe69fc81734b0f30` (`kotoba-lang/industry`
  `main`); branch `isic-0114-implemented` deleted after landing.
  Post-merge re-verification from a brand-new fresh clone (plus a fresh
  `../technology` sibling clone, the registry's `:local/root`
  dependency): `git merge-base --is-ancestor` confirmed the merge commit
  is an ancestor of the fresh clone's `HEAD`; `clojure -M:test` -> "Ran 15
  tests containing 942 assertions. 0 failures, 0 errors."
  `clojure -M:lint` -> 0 errors, 0 warnings.
  `grep -c "â" resources/kotoba/industry/registry.edn` -> 0 (no UTF-8
  mojibake).
