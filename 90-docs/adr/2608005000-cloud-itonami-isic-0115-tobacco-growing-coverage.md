# ADR-2608005000: cloud-itonami-isic-0115 (growing of tobacco) tobacco-growing-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage), ADR-2607152500
(cloud-itonami-isic-0111 cereal-growing, the module-structure template
this ADR mirrors), ADR-2607160400 (cloud-itonami-isic-0116 fibre-crop
growing, the direct module-shape and required-technologies analog this
ADR mirrors module-for-module, `tobaccoops.*` in place of `fibreops.*`).

## Context

The industry registry's entry for ISIC Rev.4 **0115** (Growing of
tobacco) sat at `:maturity :spec`, pointed at the dead
`gftdcojp/cloud-itonami-A0115` placeholder URL, with no repo, no business
model, no actor. `gh api repos/gftdcojp/cloud-itonami-A0115` and
`gh api repos/cloud-itonami/cloud-itonami-isic-0115` both confirmed 404
before any work began -- this is a fresh scaffold, not a promotion of a
prior broken artifact. Identity (`{:id "0115" :name "Growing of
tobacco"}`) independently verified against a fresh clone of
`kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution.

Tobacco growing spans: planting/curing/grading batch and leaf-quality
record-keeping, field/curing-operation (planting/topping/harvesting/
curing/grading) scheduling coordination, crop pest (e.g. tobacco
hornworm)/disease (e.g. blue mold)/curing-defect (e.g. barn rot, house
burn) concern escalation, and fertilizer/pesticide/curing-fuel
procurement. **CRITICAL exclusions**: direct field-equipment operation,
finalizing a curing-barn temperature decision, and finalizing a
pesticide-application decision remain the exclusive authority of the
farmer/agronomist/curing-operator -- this actor only coordinates
back-office record-keeping and logistics, never field-domain actuation,
curing-process control, or agronomic decision-making.

## Decision

Implement a complete tobacco-growing-operations-coordination actor
(`cloud-itonami-isic-0115`), mirroring `cloud-itonami-isic-0116`'s
(`fibreops.*`) module shape module-for-module (`tobaccoops.*`), with one
genuinely new domain-specific hard-invariant expansion (a third permanent
blocked op) reflecting tobacco cultivation's own distinct curing-barn
temperature-decision authority:

1. **`tobaccoops.governor`** (`TobaccoOperationsGovernor`) -- independent
   constraint layer with HARD checks (always hold, no override):
   - `field-not-registered` -- request's field-id must resolve to a
     registered field in the Store.
   - `no-execution` -- every proposal's `:effect` must be `:propose`;
     the governor never directly executes anything.
   - `equipment-or-curing-decision-blocked` -- **THREE** permanent hard
     members (one more than 0116's two): `:operate-field-equipment`,
     `:finalize-curing-barn-temperature-decision`, and
     `:finalize-pesticide-application`, unconditionally and permanently
     blocked regardless of confidence or cites. The curing-barn
     temperature decision is a genuinely tobacco-specific exclusion with
     no fibre-crop analogue -- flue/fire/air/sun curing barn temperature
     and duration control is farmer/curing-operator authority, distinct
     from (and additional to) the pesticide-application-decision
     exclusion 0116 already has.
   - `op-not-allowed` -- closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `cultivation-record-invalid` -- `:log-cultivation-record` with a
     non-positive acreage is rejected.
   - `leaf-grade-invalid` -- `:log-cultivation-record` with a
     `:leaf-grade` code outside the actor's recognized closed vocabulary
     (`tobaccoops.facts/leaf-quality-grades`) is rejected. Leaf-quality
     grading spans commodity-specific systems (USDA flue-cured/burley
     grade schedules by stalk position/color/quality, oriental and
     dark-fired regional grading conventions); rather than modeling any
     one standard, the actor's closed vocabulary
     (`#{"premium" "grade-a" "grade-b" "grade-c" "below-grade"
     "ungraded"}`) mirrors 0116's `fibre-quality-grades` shape exactly --
     an honest generic grading outcome the actor's cultivation records
     cite, independently verified for recognizability, not a substitute
     for the underlying commodity-specific standard.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` -- ALWAYS escalates, any confidence
     (pest e.g. tobacco hornworm / disease e.g. blue mold /
     curing-defect e.g. barn rot, house burn).
   - `:order-supplies` above its category cost threshold (default 500;
     `tobaccoops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for curing-fuel).
   - low confidence (< 0.7).

2. **`tobaccoops.facts`** -- reference data (pure, deterministic): supply
   categories with cost thresholds (fertilizer 500, pesticide 500,
   curing-fuel 1000 -- curing-fuel replaces 0116's "equipment" as the
   high-cost category, reflecting propane/wood/coal input costs for
   flue/fire curing barns); tobacco-type classification (flue-cured,
   burley, oriental, dark-fired, cigar-wrapper); an informational
   (non-validated) `field-operation-types` reference set covering
   planting/topping/harvesting/curing/grading -- topping (removing the
   flower head to concentrate leaf growth) and the curing step
   (barn/flue/air/fire/sun, method-dependent) are genuinely
   tobacco-specific field/post-harvest operations with no direct
   fibre-crop analogue; and the closed `leaf-quality-grades` vocabulary
   the Governor gate independently verifies against.

3. **`tobaccoops.registry`** -- independent, unconditional pure
   predicates: `cost-exceeds-threshold?`, `acreage-non-positive?`,
   `leaf-grade-unknown?`, `confidence-below-floor?`.

4. **`tobaccoops.store`** -- `Store` protocol + in-memory `MemStore`:
   `registered-field` lookup, `add-field` for tests/simulation.

5. **`tobaccoops.advisor`** -- `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below. The
   `:log-cultivation-record` proposal's default `:leaf-grade` is
   `"ungraded"` (itself a member of the recognized closed vocabulary),
   so a caller who doesn't supply a grade never trips the HARD check by
   omission.

6. **`tobaccoops.phase`** -- 0->3 rollout phase gate: phase-0 forces
   every would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even
   when clean; phase-2/3 pass the Governor's disposition through
   unchanged.

7. **`tobaccoops.operation`** -- composes advisor -> governor -> phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0111/0116's own stub status).

8. **`tobaccoops.sim`** -- demo runner (`clojure -M:run`), with a working
   `-main`.

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-cultivation-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** -- 36 tests / 123 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/
    `sim_test`, matching 0111/0114/0116's own test coverage shape). One
    extra `governor_test` deftest beyond 0116's own 16 covers the third
    blocked op (`:finalize-curing-barn-temperature-decision`) as its own
    distinct hard-violation test, alongside the existing equipment- and
    pesticide-decision-blocked tests.

11. **Documentation** -- README.md/docs/business-model.md/
    docs/operator-guide.md describe the actual implemented API
    (`tobaccoops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata
    (`:governor :tobacco-operations-governor`, domain
    `:agriculture/tobacco-growing`); standard OSS files (CONTRIBUTING,
    GOVERNANCE, SECURITY, CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE
    copied verbatim from 0116).

## Consequences

(+) Tobacco growing (ISIC 0115) operations-coordination is now genuinely
implemented and fully tested from a fresh scaffold.

(+) Scope boundaries (direct field-equipment operation, finalizing a
curing-barn temperature decision, and finalizing a pesticide-application
decision, all permanently excluded) are hardcoded in governor checks
(`equipment-or-curing-decision-blocked`, a three-member closed set) and
documented in README, not just asserted in prose.

(+) Crop-health/curing-defect escalation (`:flag-crop-health-concern`,
e.g. tobacco hornworm/blue mold/barn rot, always human) is a core design
invariant, not an add-on.

(+) The curing-barn-temperature-decision block is a genuine
tobacco-specific hard-invariant addition (not a copy-paste of 0116's
two-member blocked-ops set), giving this vertical real differentiation
from its closest sibling while following the exact same governor-gate
shape.

(+) Portable `.cljc` implementation with no JVM-only constructs
(`System/currentTimeMillis`, `Date`, etc. never used); `clojure -M:lint`
is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0111/0116's own stub status; production
integration pending.

(-) `leaf-quality-grades` is a generic closed vocabulary, not a
transcription of any one specific named commodity grading standard
(USDA flue-cured/burley grade, etc.) -- fully disclosed in
`tobaccoops.facts`'s own docstring.

## Verification

- `cloud-itonami-isic-0115`: `clojure -M:test` -> "Ran 36 tests
  containing 123 assertions. 0 failures, 0 errors." `clojure -M:lint` ->
  0 errors, 0 warnings. `clojure -M:run` -> demo runs end-to-end,
  returns `:disposition :escalate` as expected (phase-0 forces human
  review of all commits).
- Commit `827beb59291d7b2795b508df40cb684d636598bb` pushed to
  `cloud-itonami/cloud-itonami-isic-0115`'s `main` (the repo's only
  commit, freshly created); independently confirmed as an ancestor of
  `origin/main` via `git merge-base --is-ancestor`.
- Registry entry (`kotoba-lang/industry`) update: `"0115"` entry's
  `:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id` updated
  from the dead `gftdcojp/cloud-itonami-A0115` placeholder to the real
  `cloud-itonami/cloud-itonami-isic-0115` repo, `:operating-states`
  aligned to the real `advise->govern->decide->commit|hold` flow, citing
  this ADR. Landed via GitHub Contents-API single-file PUT (sha-checked
  optimistic concurrency) after two branch-merge attempts 409'd under
  heavy concurrent fleet load (100+ agents landing promotions on this
  repo concurrently): commit `f6067701cadd10ce20940094c909ce27bbdb0337`
  (`kotoba-lang/industry` `main`), exact-block edit only, diff-verified
  single-block change (`:repo`/`:business-id`/`:maturity`/
  `:operating-states` only).
- `test/kotoba/industry_test.clj`'s `:implemented` count and a dedicated
  `cloud-itonami-isic-0115` maturity-tier testing block were added via a
  second Contents-API PUT, recomputed live via
  `(industry/maturity-summary)` each time, not assumed. **Self-correction
  note**: an earlier attempt in this same session used a stale local
  content snapshot for the sha-checked PUT (the sha check matched the
  live tip at request time, but the payload was built from an older
  fetch), which silently clobbered two unrelated concurrent sibling
  promotions' own testing-block additions (`cloud-itonami-isic-3900` and
  `cloud-itonami-isic-0721`) -- both were independently restored by
  other concurrent agents in the fleet before this ADR was finalized (one
  of whom also caught and fixed a quote-escaping syntax error introduced
  during a third party's own restore of the 3900 block). Lesson recorded
  here rather than silently omitted: sha-checked Contents-API PUTs must
  build their payload from a content fetch taken **immediately before**
  the PUT, never from an earlier cached snapshot, even when the sha
  parameter itself is freshly re-fetched -- the sha check alone does not
  protect against this.
- Post-merge re-verification from a brand-new fresh clone (plus a fresh
  `../technology` sibling clone, the registry's `:local/root`
  dependency): `clojure -M:test` -> "Ran 15 tests containing 1004
  assertions. 0 failures, 0 errors." `grep -c "â"
  resources/kotoba/industry/registry.edn` -> `0` (no file-wide UTF-8
  mojibake). `"0115"` entry re-confirmed present and correct
  (`:maturity :implemented`, `:repo
  https://github.com/cloud-itonami/cloud-itonami-isic-0115`) in this
  fresh clone.
