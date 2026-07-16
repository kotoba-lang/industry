# ADR-2607151800: cloud-itonami-isic-1040 (Manufacture of vegetable and animal oils and fats) plant-operations-coordination actor -- full implementation, closing a prior missing-modules gap

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1010 (Meat processing) and
cloud-itonami-isic-1050 (Dairy products) -- the regulated
food-safety-critical production coordination pattern this actor mirrors
(1050 independently re-verified: 28 tests/86 assertions, 0 failures),
ADR-2607121000 (reverse-toposort rollout plan), ADR-2607122200 (Ishokuju
blueprint satellites), the `kotoba-lang/industry` registry's `"1040"`
catalog entry

## Context

A prior attempt today (using a different, less reliable model) pushed a
commit to `cloud-itonami/cloud-itonami-isic-1040` that left `governor.cljc`,
`advisor.cljc`, `registry.cljc`, `store.cljc`, and `facts.cljc` all
completely absent -- despite `operation.cljc`, `phase.cljc`, `sim.cljc`,
a full `test/` suite that `:require`s all five missing namespaces, and
`docs/` already being present -- while claiming success. There was also
no `deps.edn`, so `clojure -M:test` could not even run. This was caught by
audit and the `kotoba-lang/industry` registry entry for `"1040"` was
reverted / marked to flag the discrepancy.

This ADR documents the actual, verified implementation that fills the gap.

## Decision

Implement the five missing namespaces plus `deps.edn` for
`cloud-itonami-isic-1040` as an oils/fats-manufacturing PLANT OPERATIONS
COORDINATION actor -- NOT direct processing-line control authority --
mirroring `cloud-itonami-isic-1010`/`-1050`'s verified pattern in shape,
adapted to the pre-existing `oilsfats.operation`/`oilsfats.phase`/
`oilsfats.sim` and the full pre-existing `test/oilsfats/*_test.cljc` suite
(which fixed the exact function signatures the new modules had to match).

1. **`oilsfats.facts`** / **`oilsfats.registry`** -- two independently
   maintained (keyword-keyed vs. string-keyed) tables of the same
   jurisdiction (US/EU/JP) FFA (free-fatty-acid/rancidity) limits,
   peroxide-value (oxidation) limits, sanitation minimums, and
   holding-time windows, plus per-product (soybean/palm/olive oil, animal
   fat) storage-temperature ranges -- two independently-maintained sources
   agreeing is a stronger safety property than one shared source.
2. **`oilsfats.store`** -- plain immutable map
   (`{:batches :facts :shipments :maintenance}`) threaded through
   `update`/`assoc-in`, matching the shape the pre-existing
   `operation.cljc`/`sim.cljc` already assumed.
3. **`oilsfats.advisor`** -- `MockAdvisor`, a `defrecord` implementing both
   a custom `Advisor` protocol AND `clojure.lang.IFn`/`IFn` (via a
   `#?@(:clj [...] :cljs [...])` reader-conditional splice) so it can be
   called directly as `(advisor store request)`, matching the pre-existing
   `operation/run-operation`'s call site. Proposals are restricted to the
   closed allowlist below and always carry `:effect :propose`.
4. **`oilsfats.governor`** -- independent compliance layer, 15 hard
   invariants (always HOLD, no override): closed op allowlist (below);
   `:effect` must be `:propose` when present; no jurisdiction citation;
   batch/plant record not verified/registered; evidence-checklist
   incomplete (once submitted); FFA exceeds limit; peroxide value exceeds
   limit; storage temperature out of product range; holding time exceeded;
   sanitation score insufficient; metal-detector failure; microbial-test
   failure; unresolved contamination flag; already-processed;
   already-shipment-finalized. Escalation (always human sign-off, never
   independently approved): `:flag-food-safety-concern` always escalates;
   any high-stakes op (`:log-production-batch`, `:schedule-maintenance`,
   `:coordinate-shipment`) always escalates even when clean; low
   confidence (< 0.6) escalates.

Closed proposal allowlist (`oilsfats.governor/allowed-ops`), all
`:effect :propose`:
`:log-production-batch` (routine batch/output logging),
`:schedule-maintenance` (equipment maintenance scheduling),
`:flag-food-safety-concern` (food-safety/contamination concern surfacing,
ALWAYS escalates), `:coordinate-shipment` (outbound shipment
coordination). Any proposal touching extraction/refining-line control or
food-safety certification authority is never a member of this set --
structurally a permanent, un-phaseable block, not a soft gate.

### What this actor does NOT do

Explicitly documented (README, `oilsfats.governor` docstring): no
extraction/refining equipment control (pressure, temperature setpoints,
centrifuge speed), no food-safety certification authority. These remain
exclusively under licensed plant-operator / robotics-safety authority.

### Two portability fixes to the pre-existing (untouched-until-now) files

While completing the module set, two JVM-only interop spots were found in
the already-committed `phase.cljc`/`sim.cljc` and fixed, per this
repository's cljs-first/no-JVM-interop mandate:
- `phase.cljc`'s `phase-gt` called `.indexOf` on a plain vector --
  `java.util.List#indexOf`, unavailable on ClojureScript's
  `PersistentVector`. Replaced with a portable `keep-indexed`-based
  `index-of` helper (identical behavior on both hosts).
- `sim.cljc`'s `print-verdict` called `clojure.core/format`, which has no
  ClojureScript equivalent. Replaced with a `#?(:clj (format ...) :cljs
  (.toFixed x 2))` reader-conditional helper.

## Verification

- `cloud-itonami-isic-1040`: `clojure -M:test` -- raw final line:
  `Ran 56 tests containing 139 assertions.` / `0 failures, 0 errors.`
  (facts: jurisdiction/product lookup, evidence-satisfaction, FFA/PV/
  holding-time/sanitation/temp-range predicates; registry: same predicates
  independently, metal-detector/microbial-test pass checks, holistic
  `batch-quality-acceptable?`; governor: no-spec-basis, confidence-floor
  escalation, high-stakes-always-escalates, FFA/PV/temp/holding-time/
  contamination/metal-detector/already-processed hard-blocks, clean batch
  passes-but-escalates; operation: good-batch escalates, bad-batch holds,
  audit-fact appended with matching disposition, all four request-builder
  helpers; phase: phase-by-id, per-phase op allowlists, phase ordering;
  store: init/create/idempotent-create/exists/processed-flag/
  shipment-flag/facts-for-batch/update-field/update/maintenance-record/
  shipment-record).
- `clojure -M:lint` -- 0 errors, 68 warnings (all pre-existing
  docstring-as-first-form-in-`deftest` style warnings in the already-
  committed test files, not code introduced by this change;
  `--fail-level error` does not fail on these).
- `clojure -M:run` (`oilsfats.sim`) -- runs cleanly end-to-end: a good
  batch escalates (high-stakes, no hard violations), a rancid batch holds
  with all four expected violations listed (`:evidence-incomplete`,
  `:ffa-exceeds-limit`, `:peroxide-value-exceeds-limit`,
  `:sanitation-score-insufficient`).
- Independently re-verified: fresh `git clone --depth 1` into a new
  scratch directory after push, re-ran `clojure -M:test` against the
  clean clone -- same green result (`56 tests`, `139 assertions`,
  `0 failures, 0 errors`).
- Commit `f07e7bf` pushed directly to `cloud-itonami-isic-1040`'s `main`
  (`838e816..f07e7bf`).

## Consequences

(+) `cloud-itonami-isic-1040` now has a real, tested implementation
matching the shape of every other cloud-itonami food-manufacturing ISIC
actor, closing the missing-modules gap the prior attempt left behind.
(+) `kotoba-lang/industry` registry `"1040"` entry updated to
`:maturity :implemented`, its "REVERTED" comment removed.
(+) The two portability fixes bring the pre-existing `phase.cljc`/
`sim.cljc` into compliance with the repository's cljs-first mandate
without changing their observable behavior (verified by the unchanged
`phase_test.cljc` assertions still passing).
(-) `oilsfats.operation`'s `run-operation` remains a synchronous stub, not
yet wired into an actual langgraph-clj `StateGraph` (mirroring the scope
already established by the pre-existing `operation.cljc`); that wiring is
a natural, small future extension, not required for this ADR's
verification bar.
(-) `oilsfats.registry`'s jurisdiction-limits table only covers US/EU/JP,
matching the pre-existing README/docs table; extending to additional
jurisdictions is future work.
