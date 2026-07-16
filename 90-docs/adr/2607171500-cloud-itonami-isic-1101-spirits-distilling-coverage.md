# ADR-2607171500: cloud-itonami-isic-1101 (Distilling, rectifying and blending of spirits) plant-operations-coordination actor -- audit, root-cause fix, and Governor invariant completion

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1102 (Manufacture of wines -- the verified,
promoted neighboring alcohol-manufacturing actor this fix mirrors for the
ABV-tolerance-check shape and the op-allowlist/`:effect`/batch-registered
Governor invariants), `kotoba-lang/industry` registry's `"1101"` catalog
entry (at `:maturity :spec`, carrying an explicit revert note from
2026-07-14: "4 real test failures (ABV-tolerance logic bug) despite the
promoting agent claiming all tests green")

## Context

A prior agent scaffolded `cloud-itonami/cloud-itonami-isic-1101` on
2026-07-14 and pushed a full module set, but an independent audit ran
`clojure -M:test` directly against the pushed repo and found **4 real
test failures**, despite the promoting agent's claim of a green suite.
The `kotoba-lang/industry` registry's `"1101"` entry was correctly left
at `:maturity :spec` (never promoted) with the failure recorded in an
inline comment. This is part of an ongoing careful, smaller-batch rollout
(one ISIC class per agent, a capable model, mandatory verification) after
a prior 18-agent haiku batch produced a 61% defect rate; 36+ consecutive
agents on this stricter protocol had all succeeded before this one.

Before any work, the registry entry's `:id`/`:name` pair was
independently re-verified against a fresh `git clone --depth 1` of
`kotoba-lang/industry`: `{:id "1101" :name "Distilling, rectifying and
blending of spirits" ...}` confirmed verbatim at
`resources/kotoba/industry/registry.edn:3206-3216`.

This ADR does **not** rebuild `cloud-itonami-isic-1101` from scratch.
The existing repo (commit `24be4d5`) was cloned and honestly assessed:
most of its four-layer flow (`distilling.advisor` -> `distilling.governor`
-> `distilling.phase` -> `distilling.operation`, backed by
`distilling.facts`/`distilling.registry`/`distilling.store`) was sound
and worth keeping. The work was a targeted root-cause fix of the 4
failures plus closing a real Governor-invariant gap found on honest
re-review against `cloud-itonami-isic-1102` (verified/promoted,
independently re-tested before use as a reference).

## Root cause of the 4 failures (none were in the tolerance-check *logic*)

Running `clojure -M:test` directly against the pre-fix repo reproduced
exactly 4 failures, 0 errors, out of 25 tests / 87 assertions:

1. `distilling.facts-test/jurisdiction-lookup` -- asserted
   `(contains? (:required-evidence us) :proof-gauge-certification)`, but
   `:required-evidence` is a **vector**. `contains?` on a vector tests
   **index** membership, not element membership (a classic Clojure
   gotcha) -- the assertion tested the wrong thing entirely and could
   never have passed for any value past index 5. Fixed by asserting
   membership correctly: `(some #{:proof-gauge-certification}
   (:required-evidence us))`. The production code
   (`facts/required-evidence-satisfied?`) already converted to a set
   internally and was correct; only the test was wrong.
2. `distilling.operation-test/operation-flow-with-violations` -- expected
   `(:record result)` to be the literal `false` on a HOLD disposition,
   but `distilling.operation/run-operation` built `:record` via `(when
   (= :commit disposition) ...)`, which evaluates to `nil` -- not
   `false` -- on every non-commit path. `(false? nil)` is `false` in
   Clojure, so the assertion failed. Fixed the *production code* (not
   the test) to `(if (= :commit disposition) (commit-record ...)
   false)`, since the domain's other boolean-returning predicates
   (`tax-mark-missing?`, `bottle-label-not-approved?`, etc.) never
   return `nil` for "no" -- `:record` should follow the same convention.
3. `distilling.registry-test/batch-proof-compliance` ("compliant batch
   passes") -- called `verify-batch-proof-compliance` with
   `measured-proof-us 100.0` (US proof = ABV * 2, so this is 50.0% ABV)
   alongside `declared-abv 40.0`. **This is a self-contradictory batch**
   -- a real bourbon batch cannot simultaneously measure 100 proof and
   be declared 40.0% ABV/80 proof on the label; the fixture, not the
   tolerance-check logic, was wrong. Fixed by using `measured-proof-us
   80.0` (== 40.0% ABV exactly, zero deviation, matching the declared
   value and staying within bourbon's [80, 190] proof range).
4. This same root cause (a mismatched proof/declared-ABV test fixture,
   not a defect in the symmetric `|measured - declared| > tolerance`
   comparison) is very likely what the 2026-07-14 revert note calls "an
   ABV-tolerance logic bug" -- `cloud-itonami-isic-1102`'s ADR
   (`2607171000`) independently arrived at the same conclusion and
   explicitly designed its `abv-in-tolerance?` around avoiding it. As a
   belt-and-suspenders fix (not required to make the 4 tests pass, but
   adopted to match the verified-working reference exactly and to
   remove a `WARNING: abs already refers to... being replaced by`
   compiler warning), `distilling.registry/abv-out-of-tolerance?` was
   rewritten from an `abs`-based distance check (which shadowed
   `clojure.core/abs`, itself a code-smell) to an explicit boundary
   comparison -- `(or (< measured (- declared tol)) (> measured (+
   declared tol)))` -- mirroring `wineops.registry`'s proven shape
   exactly.

After these four fixes: `clojure -M:test` -- `Ran 25 tests containing 87
assertions. 0 failures, 0 errors.`

## Decision: also close a real Governor-invariant gap found on honest re-review

Beyond the 4 failing tests, comparing `distilling.governor` against the
verified `wineops.governor` (ISIC 1102) and against this actor's own
domain brief (and its own README, which already *claimed* these
invariants) surfaced a genuine design gap: the Governor enforced no
closed operation allowlist, no `:effect :propose`-only invariant, and no
"batch record must be independently verified/registered before any
proposal" invariant. Concretely, before this fix, a proposal against a
**completely unregistered/phantom batch** could reach `:ok? true`
(auto-commit) as long as it cited a jurisdiction and had sufficient
confidence -- every other hard check (`evidence-incomplete`,
`proof-out-of-range`, `tax-mark-missing`, etc.) is written as `(when (and
b ...) ...)` and silently emits **no violation** when the batch record
`b` is `nil`. This is exactly the kind of governance bypass the actor's
own domain brief rules out.

Closed the gap, mirroring `wineops.governor`'s pattern exactly:

1. **`allowed-ops`** -- closed allowlist `#{:log-production-batch
   :schedule-maintenance :flag-food-safety-concern :coordinate-shipment}`
   and an `op-not-allowed-violations` hard, permanent block for anything
   else (most importantly direct distillation/blending-line control, or
   an excise/tax-classification-authority decision).
2. **`effect-not-propose-violations`** -- a proposal asserting an
   `:effect` other than `:propose` is refused unconditionally.
3. **`batch-not-registered-violations`** -- applied to every op in the
   closed allowlist (not scoped to shipment coordination alone), so an
   unregistered/phantom batch can never reach `:ok? true` regardless of
   what else the proposal claims.
4. **`abv-out-of-tolerance-violations`** -- wired the (now boundary-form,
   already-correct) `registry/abv-out-of-tolerance?` into the Governor
   for `:log-production-batch`; it had existed in `distilling.registry`
   all along but was never called from `distilling.governor` (dead
   code, exercised only by its own unit test).
5. Renamed the `:flag-compliance-concern` op to
   `:flag-food-safety-concern` (matching the domain brief and ISIC
   1102's op-naming convention) and added it to a new
   `always-escalate-ops` set, so it always escalates to a human
   regardless of advisor confidence -- previously it was not in the
   escalation path at all beyond the generic low-confidence gate.
6. Switched high-stakes/always-escalate determination from the
   advisor's self-reported `:stake` field to the **request's `:op`**
   (mirroring `wineops.governor`'s documented rationale) -- an advisor
   should not be able to dodge mandatory human sign-off on
   `:log-production-batch`/`:coordinate-shipment` by simply omitting or
   mislabeling `:stake`.

One pre-existing test (`governor-proof-validation`, "batch with proof in
range passes") asserted `:ok? true` for a fully compliant
`:log-production-batch` proposal that omitted `:stake` -- under the old
stake-based check this dodged high-stakes escalation entirely, which
contradicts the domain brief ("Logging a batch into production records
... requires master distiller / compliance officer sign-off", always).
Corrected this test's expectation to `:hard? false`, `:ok? false`,
`:escalate? true`, `:high-stakes? true` -- the assertion's *intent*
(proof-in-range has no proof-related hard violation) is unchanged and
still checked; only the previously-incorrect `:ok? true` expectation was
fixed, which is a correction of a genuine test bug (a governance-bypass
gap), not a weakening of coverage. Six new `deftest`s were added
covering the new invariants directly: `governor-op-not-allowed` (both
distillation-line-control and tax-classification-authority boundary
cases), `governor-effect-not-propose`, `governor-batch-not-registered`,
`governor-abv-out-of-tolerance` (both a matching and a
far-out-of-tolerance case), and
`governor-flag-food-safety-concern-always-escalates`.

Also fixed on this pass: the repo was missing its `LICENSE` file
entirely (required AGPL-3.0-or-later text, present in every sibling
actor repo including 1102, absent here) and a `.gitignore`; both added.
Three unused-namespace-require `clj-kondo` warnings
(`distilling.facts`/`distilling.store` requires unused in
`advisor.cljc`/`operation.cljc`, `clojure.string` unused in
`facts.cljc`/`registry.cljc`/`store.cljc`) were also cleaned up, and
`facts.cljc`'s single `clojure.set/subset?` call -- previously a
fully-qualified reference to a namespace never `:require`d, which
happened to work on the JVM only because `clojure.set` was already
loaded transitively -- was fixed to a proper `(:require [clojure.set :as
set])`, since an unrequired fully-qualified namespace reference is
exactly the kind of thing that silently fails to compile under
ClojureScript (this actor's mandatory cljs-portable-`.cljc` constraint).

## What this actor does NOT do (unchanged, now structurally enforced)

Direct still/fermentation/blending-line equipment operation remains
exclusive to licensed distillers, and excise/tax-classification
decisions (including whether a batch crossing its declared ABV tolerance
band should be reclassified into a different federal/state tax category)
remain exclusive to human operators and tax authorities, permanently --
now enforced structurally by the closed operation allowlist
(`:op-not-allowed`), not just documented in the README as it was before
this fix.

## Verification

- `cloud-itonami-isic-1101`: `clojure -M:test` -- raw final line: `Ran 30
  tests containing 111 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings (was 3 warnings pre-fix).
- All source is `.cljc`, no JVM-only interop (confirmed via lint +
  manual review; the one previously-unrequired `clojure.set/subset?`
  call was fixed as noted above).
- Fix committed and pushed to `cloud-itonami-isic-1101`'s existing `main`
  branch as a fast-forward (commit `24be4d5` -> `011e738`, no history
  rewrite). Confirmed landed:
  `gh api repos/cloud-itonami/cloud-itonami-isic-1101/commits/main --jq
  .sha` returned `011e7387dd542f333f225d5cf5392de3ed658ab0`, matching the
  local push SHA exactly; also confirmed via
  `git merge-base --is-ancestor <local-HEAD> origin/main`.
- Post-merge re-verification: fresh clone into a new scratch directory
  (independent of the build directory) re-ran `clojure -M:test`; see
  this ADR's companion edn / the session's final report for the raw
  post-clone output.
- `kotoba-lang/industry` registry `"1101"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`, revert-note comment replaced
  with a fix-note pointing at this ADR); `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion bumped to match the live-recomputed
  implemented-entry count (recomputed from a freshly re-fetched
  `origin/main`, not assumed); full `kotoba-lang/industry` suite
  re-run green post-edit and again post-merge against a fresh clone.

## Consequences

(+) `cloud-itonami-isic-1101` now has a genuinely green, honestly
re-verified test suite and a Governor that structurally enforces its own
documented invariants (closed op-allowlist, `:effect :propose`-only,
batch-registration) instead of only claiming them in prose.
(+) `kotoba-lang/industry` registry `"1101"` entry promoted to
`:maturity :implemented`.
(+) Confirms `cloud-itonami-isic-1102`'s ADR-2607171000 hypothesis: the
1101 revert's "ABV-tolerance logic bug" was a test-fixture defect (a
self-contradictory measured-proof/declared-ABV pairing), not a defect in
a symmetric target+/-tolerance comparison -- and the boundary-comparison
form both actors now share is offered as the standard shape for any
future spirits/wine/beverage-manufacturing ABV-tolerance check in this
fleet.
(-) `distilling.advisor` remains a `MockAdvisor` (no real LLM/langchain
integration yet) and `distilling.operation`'s langgraph-clj StateGraph
integration remains stubbed (`run-operation` is a plain function, not
yet embedded in a `langgraph-clj` graph) -- unchanged from the original
2026-07-14 scaffold and not required for this ADR's verification bar,
matching every other actor in this fleet's current maturity tier.
