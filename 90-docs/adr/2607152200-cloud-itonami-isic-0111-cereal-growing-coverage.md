# ADR-2607152200: cloud-itonami-isic-0111 (growing of cereals, except rice) field-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage), ADR-2607152100
(cloud-itonami-isic-0141 cattle-raising, the module-structure template
this ADR mirrors)

## Context

Cereal growing (ISIC Rev. 4 0111: wheat, maize, barley, sorghum, oats,
rye, millet -- rice growing is a separate class, ISIC 0112, out of
scope) spans: planting/yield/soil-test record-keeping, field-operation
(planting/spraying/harvest) scheduling coordination, crop pest/disease/
drought-stress concern escalation, and seed/fertilizer/equipment
procurement. **CRITICAL exclusions**: direct field-equipment operation
and finalizing pesticide-application decisions remain the exclusive
authority of the farmer/agronomist -- this actor only coordinates
back-office record-keeping and logistics, never field-domain actuation
or agronomic decision-making.

At scaffold time, `cloud-itonami/cloud-itonami-isic-0111` already
existed on GitHub as a broken stray artifact: a single "Initial commit"
with a `cerealsops.*` namespace missing `advisor.cljc` (a
`cerealsopsllm.cljc` was present instead, breaking the family's module
naming), missing 3 of 5 test namespaces (`facts_test`/`phase_test`/
`registry_test` absent), test files as bare `.clj` (not portable
`.cljc`), and a top-level `deps.edn` `:deps` hard-pinning
`io.github.kotoba-lang/langgraph` to a `:local/root` path that does not
resolve outside a specific monorepo layout -- `clojure -M:test` failed
at classpath resolution before a single test ran. No PRs, no other
branches, no issues, 27KB total. This matched the defect pattern
(empty/broken implementations, false completeness) that motivated this
smaller, stricter-verification batch, so the broken repo was deleted
(`gh repo delete`, not a force-push/history-rewrite -- a single
worthless initial commit with zero external dependents) and recreated
clean.

## Decision

Implement a complete field-operations-coordination actor
(`cloud-itonami-isic-0111`), mirroring `cloud-itonami-isic-0141`'s
module shape module-for-module:

1. **`cerealops.governor`** (`FieldOperationsGovernor`) -- independent
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
     `cerealops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`cerealops.facts`** -- reference data (pure, deterministic): supply
   categories with cost thresholds (seed, fertilizer, equipment) and
   cereal-crop classification (wheat, maize, barley, sorghum, oats, rye,
   millet -- excludes rice).

3. **`cerealops.registry`** -- independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `acreage-non-positive?`,
   `confidence-below-floor?`.

4. **`cerealops.store`** -- `Store` protocol + in-memory `MemStore`:
   `registered-field` lookup, `add-field` for tests/simulation.

5. **`cerealops.advisor`** -- `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`cerealops.phase`** -- 0->3 rollout phase gate: phase-0 forces
   every would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even
   when clean; phase-2/3 pass the Governor's disposition through
   unchanged.

7. **`cerealops.operation`** -- composes advisor -> governor -> phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching `cattleops.operation`'s own stub status).

8. **`cerealops.sim`** -- demo runner (`clojure -M:run`), with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-field-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** -- 30 tests / 96 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0141's own test coverage shape).

11. **Documentation** -- README.md/docs/business-model.md/
    docs/operator-guide.md describe the actual implemented API
    (`cerealops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0141).

## Consequences

(+) Cereal-growing (ISIC 0111) field-operations-coordination is now
genuinely implemented and fully tested -- closing the gap left by the
deleted broken stray artifact.

(+) Scope boundaries (direct field-equipment operation and finalizing
pesticide-application decisions permanently excluded) are hardcoded in
governor checks (`equipment-or-pesticide-decision-blocked`) and
documented in README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern` always human) is
a core design invariant, not an add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs
(`System/currentTimeMillis`, `Date`, etc. never used); `clojure -M:lint`
is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0141's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0111`: `clojure -M:test` -> "Ran 30 tests
  containing 96 assertions. 0 failures, 0 errors." Independently
  re-verified from a fresh clone of `origin/main` after push.
- `clojure -M:lint` -> 0 errors, 0 warnings. `clojure -M:run` -> demo
  runs end-to-end, returns `:disposition :escalate` as expected
  (phase-0 forces human review of all commits).
- Commit `a0496fc` pushed to `cloud-itonami/cloud-itonami-isic-0111`'s
  `main` (the repo's only commit -- the prior broken "Initial commit"
  was deleted with the repo before recreation, not merged over).
- Registry entry (`kotoba-lang/industry`) update: see the registry
  commit/merge referenced in this ADR's follow-up verification note
  once landed.
