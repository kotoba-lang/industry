# ADR-2607172000: cloud-itonami-isic-0123 (growing of citrus fruits) citrus-orchard-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607122200 (ISIC Wave 3 food/agriculture coverage),
ADR-2607011000 (actor pattern & ISIC section coverage),
ADR-2607152500 (cloud-itonami-isic-0122, module-structure template)

## Context

This is part of an ongoing careful, smaller-batch rollout (capable model +
mandatory verification per agent) after a prior 18-agent haiku batch had a
61% defect rate (empty implementations, missing modules, false "all
green" claims). 36+ consecutive agents on this stricter protocol have
succeeded before this one. Built by reading `cloud-itonami-isic-0122`
(growing of tropical and subtropical fruits, 30 tests / 93 assertions) in
full as the module-structure template, with `clojure -M:test`
independently re-run from fresh clones both before and after push.

Before any implementation work, the `kotoba-lang/industry` registry entry
was independently re-verified (fresh clone, not trusted from the task
assignment): `{:id "0123" :name "Growing of citrus fruits" ...}` is
genuinely what is registered — no ID/name mismatch (several prior agents
in this fleet had mislabeled their assigned ISIC class, e.g. 0892
assumed=salt actually=peat, 0144 assumed=swine actually=sheep-goats). A
fresh `gh api` check also confirmed no prior repository existed at either
`cloud-itonami/cloud-itonami-isic-0123` or the stale placeholder
`gftdcojp/cloud-itonami-A0123` referenced by the old registry entry (404
confirmed).

Citrus-fruit-growing operations (ISIC Rev. 4 0123, covering orange,
lemon, lime, grapefruit orchards, among others) span: orchard/block
record logging (planting, harvest yield, brix testing), field-operation
(pruning/spraying/irrigation/harvest) scheduling, crop health/pest (e.g.
citrus greening/HLB, canker)/frost-damage concern escalation, and
seedling/fertilizer/equipment procurement. **CRITICAL exclusions**: direct
field-equipment operation and finalizing a spray-application decision
remain the exclusive authority of the grower/agronomist — this actor only
coordinates back-office record-keeping and logistics, never field
actuation or agronomic decision authority.

## Decision

Implement a complete citrus-orchard-operations-coordination actor
(`cloud-itonami-isic-0123`), mirroring `cloud-itonami-isic-0122`'s module
shape module-for-module (namespace `citrusops.*`):

1. **`citrusops.governor`** (`CitrusOperationsGovernor`) — independent
   constraint layer with HARD checks (always hold, no override):
   - `orchard-not-registered` — request's orchard-id must resolve to a
     registered orchard/block in the Store.
   - `no-execution` — every proposal's `:effect` must be `:propose`; the
     governor never directly executes anything.
   - `field-equipment-or-spray-blocked` — `:operate-field-equipment` and
     `:finalize-spray-application` are unconditionally, permanently
     blocked regardless of confidence or cites.
   - `op-not-allowed` — closed proposal-op allowlist enforced
     independently of the advisor's claim.
   - `orchard-count-invalid` — `:log-orchard-record` with a non-positive
     logged quantity (trees/plants counted / harvest weight / yield
     estimate / brix reading) is rejected.

   ESCALATION invariants (always human sign-off):
   - `:flag-crop-health-concern` — ALWAYS escalates, any confidence
     (e.g. citrus greening/HLB, canker, frost damage).
   - `:order-supplies` above its category cost threshold (default 500;
     `citrusops.facts/supply-categories` gives category-specific
     thresholds, e.g. 1000 for equipment).
   - low confidence (< 0.7).

2. **`citrusops.facts`** — reference data (pure, deterministic): supply
   categories with cost thresholds (seedling, fertilizer, equipment) and
   fruit-class classification (orange, lemon, lime, grapefruit).

3. **`citrusops.registry`** — independent, unconditional pure predicates:
   `cost-exceeds-threshold?`, `orchard-count-non-positive?`,
   `confidence-below-floor?`.

4. **`citrusops.store`** — `Store` protocol + in-memory `MemStore`:
   `registered-orchard` lookup, `add-orchard` for tests/simulation.

5. **`citrusops.advisor`** — `Advisor` protocol + `MockAdvisor`, the
   sealed LLM/decision node proposing all four ops below.

6. **`citrusops.phase`** — 0→3 rollout phase gate: phase-0 forces every
   would-be commit to escalate (no autonomous commits during
   simulation); phase-1 forces always-escalate ops to escalate even when
   clean; phase-2/3 pass the Governor's disposition through unchanged.

7. **`citrusops.operation`** — composes advisor → governor → phase-gate
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, matching 0122's own stub status).

8. **`citrusops.sim`** — demo runner (`clojure -M:run`) with a working
   `-main`; verified end-to-end (`:disposition :escalate`, phase-0 forces
   human review as expected).

9. **Operations supported** (closed allowlist, all `:effect :propose`):
   `:log-orchard-record`, `:schedule-field-operation`,
   `:flag-crop-health-concern`, `:order-supplies`.

10. **Tests** — 30 tests / 92 assertions green (facts, governor, phase,
    registry, store suites; no `advisor_test`/`operation_test`/`sim_test`,
    matching 0122's own test coverage shape). Assertion count is 1 lower
    than 0122's 93 because `citrusops.facts` covers 4 citrus classes
    (orange/lemon/lime/grapefruit) instead of 0122's 5 fruit classes
    (mango/banana/papaya/avocado/pineapple).

11. **Documentation** — README.md, docs/business-model.md,
    docs/operator-guide.md describe the actual implemented API
    (`citrusops.operation`'s real `run-operation`/`build` functions and
    `:commit`/`:escalate`/`:hold` dispositions). blueprint.edn metadata;
    standard OSS files (CONTRIBUTING, GOVERNANCE, SECURITY,
    CODE_OF_CONDUCT, AGPL-3.0-or-later LICENSE copied verbatim from
    0122).

## Consequences

(+) Citrus-fruit-growing (ISIC 0123) orchard-operations-coordination is
now genuinely implemented and fully tested.

(+) Scope boundaries (direct field-equipment operation, finalizing a
spray-application decision permanently excluded) are hardcoded in
governor checks (`field-equipment-or-spray-blocked`) and documented in
README, not just asserted in prose.

(+) Crop-health escalation (`:flag-crop-health-concern`, e.g. citrus
greening/HLB, always human) is a core design invariant, not an add-on.

(+) Portable `.cljc` implementation with no JVM-only constructs;
`clojure -M:lint` is 0 errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
use in-memory `MemStore`.

(-) langgraph-clj StateGraph wiring (real `interrupt-before` +
checkpoint-based human-in-the-loop resume for escalated operations) is
deferred scaffolding, matching 0122's own stub status; production
integration pending.

## Verification

- `cloud-itonami-isic-0123`: `clojure -M:test` → "Ran 30 tests
  containing 92 assertions. 0 failures, 0 errors." `clojure -M:lint` →
  0 errors, 0 warnings. `clojure -M:run` → demo runs end-to-end, returns
  `:disposition :escalate` as expected (phase-0 forces human review of
  all commits).
- Commit `97e0ee42dead228e752ff45c840748451dd0861c` pushed to
  `cloud-itonami/cloud-itonami-isic-0123`'s `main` (fresh repo,
  root-commit). Independently confirmed via `gh api
  repos/cloud-itonami/cloud-itonami-isic-0123/commits/main` matching the
  local commit SHA.
- Registry entry (`kotoba-lang/industry`) updated in place: `"0123"`
  entry's `:maturity` `:spec` → `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-A0123` placeholder to
  `cloud-itonami/cloud-itonami-isic-0123`, and this ADR referenced.
  `industry_test.clj`'s `maturity-summary` assertion bumped 227 → 228
  (the true count recomputed live, twice, from fresh clones after two
  genuine merge conflicts caused by concurrent agents landing other ISIC
  promotions [0142 raising-of-horses, then a further landing] against the
  same file tail — resolved by re-fetching `origin/main` and
  re-deriving the count from the live registry each retry, not assuming
  a fixed number). `grep -c "â" resources/kotoba/industry/registry.edn`
  → 0 (no file-wide UTF-8 mojibake). Landed via GitHub API server-side
  merge commit `d1c058df1837136fd53b62138f4e088ac534c6e6` onto `main`
  (succeeded on the third landing attempt after two real merge conflicts
  from concurrent-fleet activity, not lock-based false 409s; each retry
  re-cloned `origin/main` fresh, reapplied the same textual edit, and
  re-ran the full suite before pushing again). Post-merge, `clojure
  -M:test` from a **completely fresh clone** of `kotoba-lang/industry`'s
  `main` (with `kotoba-lang/technology` re-cloned as its `../technology`
  sibling, per `industry`'s own `deps.edn` local/root dependency) →
  "Ran 15 tests containing 948 assertions. 0 failures, 0 errors."
  `gh api repos/kotoba-lang/industry/compare/d1c058df1837136fd53b62138f4e088ac534c6e6...main`
  → `{"status":"identical","ahead_by":0,"behind_by":0}`, confirming the
  merge commit is exactly `main`'s tip.
