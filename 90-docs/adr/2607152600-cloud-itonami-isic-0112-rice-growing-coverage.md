# ADR-2607152600: cloud-itonami-isic-0112 (growing of rice) rice-paddy-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage), ADR-2607152500
(cloud-itonami-isic-0111 cereal-growing, the module-structure template
this ADR mirrors -- the sibling ISIC class, "growing of cereals except
rice").

## Context

Rice growing (ISIC Rev. 4 0112: paddy rice -- japonica, indica,
glutinous, aromatic, upland; other cereals are a separate class, ISIC
0111, out of scope) spans: planting/yield/water-level record-keeping,
paddy-field-operation (planting/flooding-drainage/harvest) scheduling
coordination, crop pest/disease/blast-fungus/drought-stress concern
escalation, and seed/fertilizer/equipment procurement. **CRITICAL
exclusions**: direct field/irrigation-equipment operation (flooding/
drainage valves, pumps) and finalizing pesticide-application decisions
remain the exclusive authority of the farmer/agronomist -- this actor
only coordinates back-office record-keeping and logistics, never
field-domain actuation or agronomic decision-making.

`cloud-itonami/cloud-itonami-isic-0112` did not exist prior to this ADR
(`gh api repos/cloud-itonami/cloud-itonami-isic-0112` returned 404 before
scaffold) -- a fresh scaffold, not a repair of a prior stray artifact.

## Decision

Implement a complete rice-paddy-operations-coordination actor
(`cloud-itonami-isic-0112`), mirroring `cloud-itonami-isic-0111`'s module
shape module-for-module, with rice-paddy-specific additions:

1. **`riceops.governor`** (`PaddyOperationsGovernor`) -- independent
   constraint layer with HARD checks (always hold, no override):
   - `field-not-registered` -- request's field-id must resolve to a
     registered paddy field in the Store.
   - `no-execution` -- every proposal's `:effect` must be `:propose`;
     the governor never directly executes anything.
   - `equipment-or-pesticide-decision-blocked` --
     `:operate-field-equipment`, `:operate-irrigation-equipment` (NEW:
     flooding/drainage valves, pumps -- paddy-specific), and
     `:finalize-pesticide-application` are unconditionally, permanently
     blocked regardless of confidence or cites.
   - `op-not-allowed` -- closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `field-record-invalid` -- `:log-field-record` with a non-positive
     acreage is rejected.
   - `water-level-invalid` (NEW, paddy-specific) -- `:log-field-record`
     with a negative water-level is rejected (zero -- a drained/dry
     paddy, e.g. midseason-drainage -- is a valid observation).

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` -- ALWAYS escalates, any confidence
     (covers pest/disease/blast-fungus/drought-stress).
   - `:order-supplies` above its category cost threshold (default 500;
     `riceops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`riceops.facts`** -- reference data (pure, deterministic): supply
   categories with cost thresholds (seed, fertilizer, equipment
   including irrigation pumps), rice-variety classification (japonica,
   indica, glutinous, aromatic, upland -- excludes non-rice cereals), and
   a water-management-operations reference set (flooding, drainage,
   midseason-drainage, harvest-drain -- informational, not a validated
   enum).

3. **`riceops.registry`** -- independent, unconditional pure predicates:
   `cost-exceeds-threshold?`, `acreage-non-positive?`,
   `water-level-negative?` (NEW), `confidence-below-floor?`.

4. **`riceops.store`** -- `Store` protocol + in-memory `MemStore`:
   `registered-field` lookup, `add-field` for tests/simulation.

5. **`riceops.advisor`** -- `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below (log-field-record
   now also carries `:water-level`).

6. **`riceops.phase`** -- 0->3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during simulation);
   phase-1 forces always-escalate ops to escalate even when clean;
   phase-2/3 pass the Governor's disposition through unchanged.

7. **`riceops.operation`** -- composes advisor -> governor -> phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0111's own `cerealops.operation` stub status).

8. **`riceops.sim`** -- demo runner (`clojure -M:run`), with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-field-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** -- 35 tests / 107 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0111's own test coverage shape). 5 more tests /
    11 more assertions than 0111 due to the paddy-specific
    `water-level-negative?`/`water-level-invalid` checks, the
    `:operate-irrigation-equipment` hard-block test, and the
    `water-management-operations` reference-data test.

11. **Documentation** -- README.md/docs/business-model.md/
    docs/operator-guide.md describe the actual implemented API
    (`riceops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0111).

## Consequences

(+) Rice-growing (ISIC 0112) rice-paddy-operations-coordination is now
genuinely implemented and fully tested.

(+) Scope boundaries (direct field/irrigation-equipment operation and
finalizing pesticide-application decisions permanently excluded) are
hardcoded in governor checks (`equipment-or-pesticide-decision-blocked`)
and documented in README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern`, covering
blast-fungus specifically -- rice's signature fungal disease -- always
human) is a core design invariant, not an add-on.

(+) Paddy water-level is a first-class, independently validated field
(mirrors how 0111 validates acreage), reflecting that flooding/drainage
water-management is the operational core of rice growing that
distinguishes it from dryland cereal farming.

(+) Portable `.cljc` implementation with no JVM-only constructs
(`System/currentTimeMillis`, `Date`, etc. never used); `clojure -M:lint`
is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0111's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0112`: `clojure -M:test` -> "Ran 35 tests
  containing 107 assertions. 0 failures, 0 errors." `clojure -M:lint` ->
  0 errors, 0 warnings. `clojure -M:run` -> demo runs end-to-end, returns
  `:disposition :escalate` as expected (phase-0 forces human review of
  all commits).
- Commit `445bfe9e3cb499b035b6403fbe115b0127438bba` pushed to
  `cloud-itonami/cloud-itonami-isic-0112`'s `main` (the repo's only
  commit).
- Registry entry (`kotoba-lang/industry`) update: `"0112"` entry's
  `:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id` set to
  `cloud-itonami/cloud-itonami-isic-0112`, citing this ADR.
  `test/kotoba/industry_test.clj`'s pinned `:implemented` count bumped,
  recomputed live via `(industry/maturity-summary)` immediately before
  each commit (not assumed from a stale comment). First landing attempt
  (branch `isic-0112-implemented`, baseline count 199) 409'd because a
  concurrent sibling agent's `cloud-itonami-isic-0113` promotion landed
  first, advancing the true baseline to 200; re-fetched `origin/main`,
  rebuilt the same two edits on a fresh branch
  (`isic-0112-implemented-v2`) against the new baseline (200 -> 201), and
  landed via GitHub-API server-side merge, commit
  `568916edb336f1396b3b6e77fb94530fcc3387e3` (`kotoba-lang/industry`
  `main`). Both branches deleted after landing. Post-merge
  re-verification from a brand-new fresh clone (plus a fresh
  `../technology` sibling clone, the registry's `:local/root`
  dependency): `clojure -M:test` -> "Ran 15 tests containing 942
  assertions. 0 failures, 0 errors." `clojure -M:lint` -> 0 errors, 0
  warnings. `grep -c "â" resources/kotoba/industry/registry.edn` -> 0
  (no UTF-8 mojibake). Merge commit confirmed an ancestor of the fresh
  clone's `HEAD` via `git merge-base --is-ancestor`.
