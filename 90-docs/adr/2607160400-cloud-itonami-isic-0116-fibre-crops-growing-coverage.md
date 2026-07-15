# ADR-2607160400: cloud-itonami-isic-0116 (growing of fibre crops) field-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage), ADR-2607152500
(cloud-itonami-isic-0111 cereal-growing, the module-structure template
this ADR mirrors), ADR-2607154500 (cloud-itonami-isic-0114 sugar-cane
growing, source of the `quality-grade`-style domain-specific-field
independent-verification pattern this ADR adapts). Originally slotted at
ADR-2607160300, renumbered to 2607160400 after a concurrent session
claimed 2607160300 first for cloud-itonami-isic-0146 (poultry raising)
-- mirroring this fleet's own precedent for same-day slot collisions
(see ADR-2607152500's own renumbering note).

## Context

The industry registry's entry for ISIC Rev.4 **0116** (Growing of fibre
crops: cotton, jute, flax, hemp, sisal, and related bast/leaf fibre
crops) sat at `:maturity :spec`, pointed at the dead
`gftdcojp/cloud-itonami-A0116` placeholder URL, with no repo, no
business model, no actor. `gh api repos/cloud-itonami/cloud-itonami-isic-0116`
confirmed 404 before any work began -- this is a fresh scaffold, not a
promotion of a prior broken artifact.

Fibre-crop growing spans: planting/fibre-yield/quality-grade
record-keeping, field-operation (planting/defoliation/retting/harvest)
scheduling coordination, crop pest (e.g. boll weevil)/disease/
drought-stress concern escalation, and seed/fertilizer/equipment
procurement. **CRITICAL exclusions**: direct field-equipment operation
and finalizing pesticide-application decisions remain the exclusive
authority of the farmer/agronomist -- this actor only coordinates
back-office record-keeping and logistics, never field-domain actuation
or agronomic decision-making.

## Decision

Implement a complete field-operations-coordination actor
(`cloud-itonami-isic-0116`), mirroring `cloud-itonami-isic-0111`'s
(`cerealops.*`) module shape module-for-module, with one genuinely new
domain-specific independent-verification gate adapted from
`cloud-itonami-isic-0114`'s (`caneops.*`) `ratoon-cycle-invalid?`
pattern:

1. **`fibreops.governor`** (`FieldOperationsGovernor`) -- independent
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
   - `quality-grade-invalid` -- **new for this vertical**:
     `:log-field-record` with a `:quality-grade` code outside the
     actor's recognized closed vocabulary
     (`fibreops.facts/fibre-quality-grades`) is rejected. Fibre-quality
     grading spans commodity-specific systems (USDA cotton
     grade/staple-length, jute TD grades, flax scutched-line grades,
     hemp/sisal decortication grades); rather than modeling any one
     standard, the actor's closed vocabulary
     (`#{"premium" "grade-a" "grade-b" "grade-c" "below-grade"
     "ungraded"}`) is an honest generic grading outcome the actor's
     field records cite, independently verified for
     recognizability -- not a substitute for, or an agronomic judgment
     about, the underlying commodity-specific standard.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` -- ALWAYS escalates, any confidence.
   - `:order-supplies` above its category cost threshold (default 500;
     `fibreops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`fibreops.facts`** -- reference data (pure, deterministic): supply
   categories with cost thresholds (seed, fertilizer, equipment);
   fibre-crop classification (cotton, jute, flax, hemp, sisal); an
   informational (non-validated) `field-operation-types` reference set
   covering planting/defoliation/retting/harvest -- defoliation
   (pre-harvest, prepares cotton for mechanical picking) and retting
   (post-harvest bast/leaf fibre-stalk separation, jute/flax/hemp/sisal)
   are genuinely fibre-crop-specific field operations with no cereal- or
   cane-growing analogue; and the closed `fibre-quality-grades`
   vocabulary the new Governor gate independently verifies against.

3. **`fibreops.registry`** -- independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `acreage-non-positive?`,
   `quality-grade-unknown?` (new), `confidence-below-floor?`.

4. **`fibreops.store`** -- `Store` protocol + in-memory `MemStore`:
   `registered-field` lookup, `add-field` for tests/simulation.

5. **`fibreops.advisor`** -- `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below. The
   `:log-field-record` proposal's default `:quality-grade` is
   `"ungraded"` (itself a member of the recognized closed vocabulary),
   so a caller who doesn't supply a grade never trips the new HARD
   check by omission.

6. **`fibreops.phase`** -- 0->3 rollout phase gate: phase-0 forces
   every would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even
   when clean; phase-2/3 pass the Governor's disposition through
   unchanged.

7. **`fibreops.operation`** -- composes advisor -> governor -> phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching `cerealops.operation`'s own stub status).

8. **`fibreops.sim`** -- demo runner (`clojure -M:run`), with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-field-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** -- 35 tests / 120 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0111/0114's own test coverage shape). The five
    extra tests beyond 0111's 30 cover the new `quality-grade-unknown?`
    predicate, the new `quality-grade-invalid` Governor HARD violation,
    an OK-path test with a recognized grade, and the closed
    `fibre-quality-grades`/`field-operation-types` vocabulary lookups in
    `facts_test`.

11. **Documentation** -- README.md/docs/business-model.md/
    docs/operator-guide.md describe the actual implemented API
    (`fibreops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0111).

## Consequences

(+) Fibre-crop growing (ISIC 0116) field-operations-coordination is now
genuinely implemented and fully tested from a fresh scaffold.

(+) Scope boundaries (direct field-equipment operation and finalizing
pesticide-application decisions permanently excluded) are hardcoded in
governor checks (`equipment-or-pesticide-decision-blocked`) and
documented in README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern`, e.g. boll
weevil, always human) is a core design invariant, not an add-on.

(+) The new `quality-grade-invalid` gate is a genuine domain-specific
independent-verification check (not a copy-paste of 0111/0114's
acreage/ratoon-cycle checks), giving this vertical real differentiation
from its siblings while following the exact same governor-gate shape.

(+) Portable `.cljc` implementation with no JVM-only constructs
(`System/currentTimeMillis`, `Date`, etc. never used); `clojure -M:lint`
is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0111/0114's own stub status; production
integration pending.

(-) `fibre-quality-grades` is a generic closed vocabulary, not a
transcription of any one specific named commodity grading standard
(USDA cotton grade, jute TD grade, etc.) -- fully disclosed in
`fibreops.facts`'s own docstring.

## Verification

- `cloud-itonami-isic-0116`: `clojure -M:test` -> "Ran 35 tests
  containing 120 assertions. 0 failures, 0 errors." `clojure -M:lint` ->
  0 errors, 0 warnings. `clojure -M:run` -> demo runs end-to-end, returns
  `:disposition :escalate` as expected (phase-0 forces human review of
  all commits). Independently re-verified against a brand-new fresh
  clone after push: same "Ran 35 tests containing 120 assertions. 0
  failures, 0 errors."
- Commit `046639f5d4424b961d5af94d4342e37c0a025da1` pushed to
  `cloud-itonami/cloud-itonami-isic-0116`'s `main` (the repo's only
  commit, freshly created).
- Registry entry (`kotoba-lang/industry`) update: `"0116"` entry's
  `:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id` updated
  from the dead `gftdcojp/cloud-itonami-A0116` placeholder to the real
  `cloud-itonami/cloud-itonami-isic-0116` repo, citing this ADR.
  `test/kotoba/industry_test.clj`'s pinned `:implemented` count bumped,
  recomputed live via `(industry/maturity-summary)` immediately before
  each commit (not assumed from a stale comment) -- heavy concurrent
  fleet load required four attempts (three genuine 409 merge conflicts
  against concurrent sibling promotions -- `cloud-itonami-isic-0122`,
  `cloud-itonami-isic-1074`, and `cloud-itonami-isic-0146` all landed on
  `kotoba-lang/industry`'s `main` mid-flight -- each requiring a fresh
  branch off the newer `main` with the count re-derived live rather than
  incremented from the prior stale baseline) before the GitHub-API
  server-side merge succeeded: commit
  `7fec4eb92758db54ba73f83626683d12a80d8f3a` (`kotoba-lang/industry`
  `main`, `:implemented` `216 -> 217`). Stray intermediate branches
  (`isic-0116-implemented`, `-v2`, `-v3`, `-v4`) deleted after the
  landing branch's own merge. Post-merge re-verification from a
  brand-new fresh clone (plus a fresh `../technology` sibling clone, the
  registry's `:local/root` dependency): `clojure -M:test` -> "Ran 15
  tests containing 945 assertions. 0 failures, 0 errors."
  `grep -c "â" resources/kotoba/industry/registry.edn` -> `0` (no
  file-wide UTF-8 mojibake).
