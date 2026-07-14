# ADR-2607153000: cloud-itonami-isic-0113 (growing of vegetables and melons, roots and tubers) field-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage), ADR-2607152500
(cloud-itonami-isic-0111 cereal growing, the module-structure template
this ADR mirrors), ADR-2607152200 (cloud-itonami-isic-0121 grape
growing, a sibling ISIC-01xx crop-growing actor built the same way).

## Context

Vegetable/melon/root/tuber growing (ISIC Rev. 4 0113: tomato, cucumber,
cabbage, onion, carrot, potato, sweet potato, watermelon and other
fresh/chilled/frozen vegetable, melon, root and tuber crops -- cereal
growing is a separate class, ISIC 0111, rice growing is ISIC 0112, sugar
cane is ISIC 0114, and grapes are ISIC 0121, all out of scope) spans:
planting/harvest-yield/soil-test record-keeping, field-operation
(planting/spraying/irrigation/harvest) scheduling coordination, crop
pest/disease/frost-damage concern escalation, and seed/fertilizer/
equipment procurement. **CRITICAL exclusions**: direct field-equipment
operation and finalizing pesticide-application decisions remain the
exclusive authority of the farmer/agronomist -- this actor only
coordinates back-office record-keeping and logistics, never
field-domain actuation or agronomic decision-making.

At scaffold time, `cloud-itonami/cloud-itonami-isic-0113` did not exist
on GitHub (verified via `gh repo view`, 404) -- this is a fresh build,
not a repair of a prior broken artifact (unlike ISIC 0111's own history
in ADR-2607152500).

## Decision

Implement a complete field-operations-coordination actor
(`cloud-itonami-isic-0113`), mirroring `cloud-itonami-isic-0111`'s
module shape module-for-module (namespace `vegops.*` in place of
`cerealops.*`):

1. **`vegops.governor`** (`FieldOperationsGovernor`) -- independent
   constraint layer with HARD checks (always hold, no override):
   - `field-not-registered` -- request's field-id must resolve to a
     registered field in the Store.
   - `no-execution` -- every proposal's `:effect` must be `:propose`;
     the governor never directly executes anything.
   - `equipment-or-pesticide-decision-blocked` --
     `:operate-field-equipment` and `:finalize-pesticide-application`
     are unconditionally, permanently blocked regardless of confidence
     or cites.
   - `op-not-allowed` -- closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `field-record-invalid` -- `:log-field-record` with a non-positive
     acreage is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` -- ALWAYS escalates, any confidence.
   - `:order-supplies` above its category cost threshold (default 500;
     `vegops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`vegops.facts`** -- reference data (pure, deterministic): supply
   categories with cost thresholds (seed, fertilizer, equipment) and
   vegetable/root/tuber crop classification (tomato, cucumber, cabbage,
   onion, carrot, potato, sweet potato, watermelon -- excludes cereals,
   rice, sugar cane, grapes).

3. **`vegops.registry`** -- independent, unconditional pure predicates:
   `cost-exceeds-threshold?`, `acreage-non-positive?`,
   `confidence-below-floor?`.

4. **`vegops.store`** -- `Store` protocol + in-memory `MemStore`:
   `registered-field` lookup, `add-field` for tests/simulation.

5. **`vegops.advisor`** -- `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`vegops.phase`** -- 0->3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even
   when clean; phase-2/3 pass the Governor's disposition through
   unchanged.

7. **`vegops.operation`** -- composes advisor -> governor -> phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0111/0141's own stub status).

8. **`vegops.sim`** -- demo runner (`clojure -M:run`), with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-field-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** -- 30 tests / 99 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0111/0141's own test coverage shape; 3 extra
    assertions vs. 0111's 96 come from `facts_test`'s explicit
    out-of-scope checks for cereals/rice/grapes).

11. **Documentation** -- README.md/docs/business-model.md/
    docs/operator-guide.md describe the actual implemented API
    (`vegops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0111).

## Consequences

(+) Vegetable/melon/root/tuber growing (ISIC 0113) field-operations-
coordination is now genuinely implemented and fully tested.

(+) Scope boundaries (direct field-equipment operation and finalizing
pesticide-application decisions permanently excluded) are hardcoded in
governor checks (`equipment-or-pesticide-decision-blocked`) and
documented in README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern` always human,
generalized to pest/disease/frost-damage for this crop family) is a core
design invariant, not an add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs
(`System/currentTimeMillis`, `Date`, etc. never used); `clojure -M:lint`
is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0111/0141's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0113`: `clojure -M:test` -> "Ran 30 tests
  containing 99 assertions. 0 failures, 0 errors." `clojure -M:lint` ->
  0 errors, 0 warnings. Independently re-verified from a fresh clone of
  `origin/main` after push.
- Commit `9c29931` pushed to `cloud-itonami/cloud-itonami-isic-0113`'s
  `main` (the repo's only commit; the repo did not exist before this
  build).
- Registry entry (`kotoba-lang/industry`) update: `"0113"` entry's
  `:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-A0113` placeholder to
  `https://github.com/cloud-itonami/cloud-itonami-isic-0113` /
  `cloud-itonami-isic-0113`, with an implementation-complete comment
  citing this ADR. `test/kotoba/industry_test.clj`'s pinned
  `:implemented` count recomputed live -- not assumed from the prior
  comment trail, which claimed 199 at the top of the chain but a
  pre-edit `git show HEAD` count landed at 198 -- so the ground truth
  was taken from the `clojure.test` failure diff itself
  (`expected: (= 199 (:implemented m)) actual: (not (= 199 200))`) and
  bumped 199 -> 200. Landed via GitHub-API server-side merge on the
  first attempt (no 409 retries needed), commit
  `e01c27ec0a2fb4212157773013a65f943831494c`
  (`kotoba-lang/industry` `main`). Post-merge re-verification from a
  brand-new fresh clone (plus a fresh `../technology` sibling clone,
  the registry's `:local/root` dependency): `clojure -M:test` -> "Ran
  15 tests containing 942 assertions. 0 failures, 0 errors."
  `clojure -M:lint` -> 0 errors, 0 warnings.
  `grep -c "â" resources/kotoba/industry/registry.edn` -> 0 (no
  file-wide UTF-8 mojibake).
