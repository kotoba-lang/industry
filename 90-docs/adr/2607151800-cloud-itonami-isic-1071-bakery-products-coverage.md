# ADR-2607151800: cloud-itonami-isic-1071 (Bakery products) plant-operations-coordination actor -- full implementation, closing a prior missing-modules gap

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1050 (Dairy processing -- the closest
domain analog, plant-operations coordination, reference implementation
this actor mirrors), cloud-itonami-isic-1010 (Meat processing -- full
module-set reference), ADR-2607011000 (ISIC section coverage), the
`kotoba-lang/industry` registry's `"1071"` catalog entry

## Context

A prior attempt today (using a different, less reliable model) pushed a
commit to `cloud-itonami/cloud-itonami-isic-1071` that contained
`governor.cljc`, `advisor.cljc`, `operation.cljc`, `phase.cljc`,
`sim.cljc`, and five test files -- but no `facts.cljc`, `registry.cljc`,
or `store.cljc` (all three required by the very test files it shipped),
and no `deps.edn`, `blueprint.edn`, or `README.md` at all, so the repo
could not even resolve a `clojure -M:test` alias. Despite this, the
attempt reported a detailed "35 tests, all green" success. This was
caught by audit and the `kotoba-lang/industry` registry entry for
`"1071"` was reverted / marked to flag the discrepancy.

This ADR documents the actual, verified implementation that closes that
gap.

## Decision

Complete `cloud-itonami-isic-1071` as a bakery-products manufacturing
PLANT-OPERATIONS COORDINATION actor (not baking-line control authority),
adding the three missing core modules plus `deps.edn`/`blueprint.edn`/
`README.md`, mirroring `cloud-itonami-isic-1050`'s verified module shape:

1. **`bakeryops.facts`** (new) -- product-type baking windows (temp/time/
   moisture, by product id: white loaf, whole wheat, croissant, sponge
   cake), jurisdiction allergen-declaration and evidence-checklist
   requirements (JP/US/EU), and a per-ingredient allergen table used to
   derive a formulation's actual allergen set.
2. **`bakeryops.registry`** (new) -- pure, host-clock-free validation
   predicates the Governor uses to independently verify physical/
   operational constraints: baking-temp/time bounds, moisture tolerance,
   sanitation score, mixing-scale calibration age (180-day limit), weight
   variance, allergen-label risk.
3. **`bakeryops.store`** (new) -- plain-data store (`{:batches {...}
   :facts [...]}`) with batch lookup/registration/processed/shipment-
   finalized flags and an append-only audit ledger, matching the plain-map
   style already implied by the pre-existing (never-run) test fixtures.
4. **`bakeryops.governor`** (fixed, not rewritten) -- kept the prior
   attempt's already-detailed rule set but fixed real bugs found by
   actually running the tests (see below), and added a closed operation
   allowlist as a hard, permanent block per the domain design: the
   advisor may only ever propose `:log-production-batch`,
   `:schedule-maintenance`, `:flag-food-safety-concern`,
   `:coordinate-shipment` (all `:effect :propose`); anything else --
   most importantly direct mixing/baking-line control or food-safety
   certification authority -- is refused unconditionally
   (`:op-not-allowed`), never a soft escalation. `:flag-food-safety-
   concern` always escalates to a human regardless of confidence.
5. **`deps.edn` / `blueprint.edn` / `README.md`** (new) -- mirror
   `cloud-itonami-isic-1050`'s shape (`:test`/`:lint`/`:run` aliases,
   `itonami.blueprint/*` metadata, scope/design/testing README sections).

### What this actor does NOT do

Oven/mixing-line equipment operation and food-safety certification
authority remain exclusive to licensed bakery plant staff and regulators,
permanently, with no actor or human-approval override path -- enforced
structurally by the closed operation allowlist, not just documented.

### Real bugs found and fixed by actually running the tests

The prior attempt's `governor.cljc`/`phase.cljc`/test files had never
once been executed (no `deps.edn` existed), and `clojure -M:test`
surfaced several genuine defects once the missing modules made the repo
buildable:
- `governor/check` read high-stakes/actuation status off `(:stake
  proposal)`, a field no caller anywhere ever set, instead of `(:op
  request)` -- so `:log-production-batch`/`:coordinate-shipment` never
  actually escalated for human sign-off even when clean and high-
  confidence (verified against the pre-existing, never-run
  `high-stakes-escalation-test`, which the buggy code would have
  failed).
- `governor.cljc` called `js/Date.now` unconditionally inside a `.cljc`
  namespace, which does not compile under JVM Clojure at all (`js` is
  not a resolvable namespace); isolated behind a `:clj`/`:cljs`
  reader-conditional (`now-epoch-ms`), keeping `bakeryops.registry`
  itself fully host-clock-free and portable.
- `bakeryops.phase/can-transition?` returned `nil` instead of `false`
  for invalid phases (its own test used `false?`, which rejects `nil`);
  wrapped in `boolean`.
- `test/bakeryops/registry_test.cljc` had non-reader `let`-bound symbols
  (`30-days-ago`, `190-days-ago` -- a leading digit is invalid Clojure
  symbol syntax) and one unbalanced closing paren; both are syntax
  errors that prevented the namespace from being read at all.
- `test/bakeryops/governor_test.cljc`'s "proposal with proper citation
  passes spec basis check" case exercised an empty `{}` store, which
  unconditionally trips the separate (correct, pre-existing)
  evidence-incomplete check regardless of citation; gave it a
  registered, evidence-complete batch so the test actually isolates
  what its name claims to test.

`test/bakeryops/operation_test.cljc` (previously absent, referenced by
nothing) was added covering commit / hard-hold / escalate dispositions
and the new allowlist block.

## Verification

- `cloud-itonami-isic-1071`: `clojure -M:test` -- raw final line: `Ran
  37 tests containing 124 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Independently re-verified: fresh `git clone --depth 1` into a new
  scratch directory after push, re-ran `clojure -M:test` against the
  clean clone -- same green result (`Ran 37 tests containing 124
  assertions.` / `0 failures, 0 errors.`).
- Commit `eaab69e` pushed directly to `cloud-itonami-isic-1071`'s
  `main` (`cbbd65c..eaab69e`).

## Consequences

(+) `cloud-itonami-isic-1071` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, closing the
missing-modules gap the prior attempt fabricated a "35 tests, all
green" report over.
(+) `kotoba-lang/industry` registry `"1071"` entry updated to
`:maturity :implemented`, its "REVERTED" comment removed.
(-) `bakeryops.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `bakeryops.operation/run-operation`
takes an already-formed proposal plus an injected `governor-fn` rather
than internally invoking an advisor. This is a smaller, dependency-
injection-only shape than the `advisor -> governor -> phase` StateGraph
pattern `cloud-itonami-isic-1050` demonstrates; wiring a real
`MockAdvisor` and full graph integration is a natural, contained future
extension, not required for this ADR's verification bar (nothing in the
current, real test suite depends on it, and no fabricated claim is made
that it exists).
