# ADR-2607152800: cloud-itonami-isic-1030 (Processing and preserving of fruit and vegetables) plant-operations-coordination actor -- fixes leftover meat-processing template content and closes three governance gaps

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1050 (Dairy processing -- the MockAdvisor
+ `operation.cljc` StateGraph-stub + 0->3 phase-gate module shape this
actor's file set mirrors), cloud-itonami-isic-1071 (Bakery products --
source of the closed op-allowlist / `:effect :propose`-only / batch-must-
be-registered Governor invariant pattern this actor adopts), ADR-2607011000
(ISIC section coverage), the `kotoba-lang/industry` registry's `"1030"`
catalog entry

## Context

A prior attempt on `cloud-itonami-isic-1030` honestly self-reported that
its `kotoba-lang/industry` registry-promotion commit never actually
landed, leaving the `"1030"` entry correctly at `:spec`. That report did
not claim the actor code itself was broken. Independent verification
(mandatory per this task's protocol) found that it was:

- `deps.edn` and every `src`/`test` namespace docstring in the pushed
  repo were still describing a **meat-processing** actor -- comments like
  "This meat-processing vertical is SELF-CONTAINED", `MeatProcessingAdvisor`,
  `meatprocessing.governor`, and a `:run` alias pointing at the
  nonexistent namespace `meatprocessing.sim` (the real namespace is
  `fruitprocessing.sim`, which additionally had no `-main` at all, so the
  alias would have failed a second way even fixed).
- `fruitprocessing.facts` defined **meat** product types
  (`fresh-beef`/`fresh-pork`/`fresh-poultry`/`processed-sausage`, 生牛肉/
  生豚肉/生家禽/ソーセージ) and jurisdictions citing FSIS (the US meat/
  poultry regulator) with a `:holding-time-max-hours` key -- not the
  `:storage-time-max-days` key `fruitprocessing.governor`'s
  `storage-time-exceeded-violations` actually reads off a jurisdiction,
  which threw a `NullPointerException` inside `registry/storage-time-
  exceeded?`'s `(> actual-days-stored max-days-allowed)` the moment a real
  `:log-production-batch` proposal exercised that check.
- `fruitprocessing.governor-test`'s own fixtures (`"fresh-tomatoes"`,
  `"fresh-apples"`, `"fresh-lettuce"`) didn't exist in that meat-only
  `product-types` map, so `facts/product-type-by-id` silently returned
  `nil` and several "violation" assertions passed only because the
  temperature check short-circuited to no-op, not because the logic was
  correct.
- Running `clojure -M:test` against the as-pushed repo (before any fix)
  produced **`Ran 28 tests containing 106 assertions.` / `4 failures, 2
  errors.`** -- confirming the code was genuinely broken, independent of
  the registry-promotion issue the prior report flagged.
- Separately, `fruitprocessing.governor` was missing three of the
  domain design's explicit HARD invariants (closed op-allowlist,
  `:effect :propose`-only enforcement, plant/batch-must-be-registered)
  and read high-stakes/escalation status off the advisor's self-reported
  `(:stake proposal)` rather than `(:op request)` -- meaning a clean,
  confident `:flag-food-safety-concern` proposal would have auto-
  committed instead of always escalating to a human, the opposite of
  the required behavior.
- A `.cpcache/` build-cache directory had been committed (no `.gitignore`
  existed).

This ADR documents the rebuild that fixes both classes of defect.

## Decision

Rebuild `cloud-itonami-isic-1030` in place as a fruit & vegetable
processing PLANT-OPERATIONS COORDINATION actor (not processing-line
control authority), keeping the existing module set and file layout
(`facts`/`registry`/`store`/`advisor`/`governor`/`operation`/`phase`/
`sim`, mirroring `cloud-itonami-isic-1050`'s MockAdvisor + StateGraph-stub
+ 0->3 rollout-phase shape) but replacing all leftover meat-processing
domain content and closing the governance gap:

1. **`fruitprocessing.facts`** (rewritten) -- finished-product categories
   for canned (tomato, green beans), frozen (peas, corn), and dried
   (apricot) product lines with post-processing storage-temperature
   windows; US/JP/EU jurisdictions with `:storage-time-max-days` and a
   harvest-lot/temperature/storage-time/sanitation/residue-screening/
   traceability evidence checklist (EU additionally requires a low-acid-
   canning scheduled-process record, the botulism/*C. botulinum*-risk
   control point for 21 CFR 113-equivalent low-acid canned goods).
2. **`fruitprocessing.store`** (rewritten) -- same `defprotocol
   Store`/`defrecord MemStore` shape, redocumented and refielded for
   produce: `:harvest-lot-verified?`, `:storage-time-days`,
   `:residue-screening`, `:spoilage-flag-raised?`/`:spoilage-flag-
   resolved?`; `assay-of` renamed `batch-quality-of` (matching
   `cloud-itonami-isic-1050`'s naming for the equivalent method).
3. **`fruitprocessing.advisor`** (rewritten) -- `MockAdvisor` proposals
   for all four allowed ops with FDA/EPA-appropriate citations (FDA Food
   Safety Modernization Act, FDA Sanitary Transportation Rule, 21 CFR 113
   Scheduled Process) in place of the meat-regulator (FSIS) citations;
   the `:flag-food-safety-concern` fixture is a low-acid-canning
   scheduled-process deviation ("botulism risk cannot be ruled out
   without process-authority review") matching the domain design's
   named example. Dropped an unused `fruitprocessing.facts` require.
4. **`fruitprocessing.governor`** (extended, not rewritten) -- kept the
   already-correct produce-specific hard checks (storage-temperature
   window, storage-time, sanitation score, residue screening, spoilage
   flag, already-processed/already-shipment-finalized) and added the
   three invariants the domain design requires but the pushed code
   lacked: `op-not-allowed-violations` (closed allowlist:
   `:log-production-batch`/`:schedule-maintenance`/`:flag-food-safety-
   concern`/`:coordinate-shipment` only -- anything touching blanching/
   canning/retort-scheduling or food-safety certification is refused
   unconditionally), `effect-not-propose-violations` (`:effect` must be
   `:propose` or absent), and `batch-not-registered-violations` (a
   plant/batch record must already exist in the store before any of the
   three batch-scoped ops can be proposed against it). Switched
   `check`'s stake/escalation determination from `(:stake proposal)` to
   `(:op request)` and added `always-escalate-ops` (`high-stakes` plus
   `:flag-food-safety-concern`), matching the more defensible pattern
   `cloud-itonami-isic-1071`'s Governor already uses -- this is what
   makes `:flag-food-safety-concern` always escalate regardless of
   confidence, per the domain design.
5. **`fruitprocessing.operation`/`fruitprocessing.phase`/
   `fruitprocessing.sim`** (docstrings fixed, logic unchanged except
   `sim.cljc` gained an actual `-main`) -- removed all "meat-processing"/
   "MeatProcessingAdvisor" language; `sim.cljc` previously built an actor
   via `operation/build` but never invoked it and had no `-main`, so the
   `:run` alias `clojure -M:dev:run` could not have worked even with the
   namespace typo fixed. It now runs a real two-step demo (log batch,
   then coordinate shipment) through `operation/run-operation` and prints
   the disposition/audit/record, matching `cloud-itonami-isic-1050`'s
   `sim.cljc` pattern.
6. **`deps.edn`** -- fixed the stale meat-processing comment block and
   the broken `:run {:main-opts ["-m" "meatprocessing.sim"]}` (now
   `fruitprocessing.sim`).
7. **`.gitignore`** (new) -- standard Clojure ignores; untracked the
   committed `.cpcache/` directory.
8. **`test/fruitprocessing/operation_test.cljc`** (new, previously
   absent) -- covers phase-1 high-stakes escalation, governor hard-hold,
   an unregistered-batch hard-hold, `:flag-food-safety-concern` always
   escalating even when every check is clean, and routine
   `:schedule-maintenance` committing directly when clean/confident.
   `facts_test.cljc`/`governor_test.cljc`/`store_test.cljc` rewritten for
   the new domain fixtures and the three new Governor checks;
   `registry_test.cljc`/`phase_test.cljc` needed no changes (both were
   already domain-agnostic/pure and unaffected).

### What this actor does NOT do

Blanching, canning/retort scheduling, freezing, and drying equipment
operation, and food-safety certification authority, remain exclusive to
licensed plant operators and process authorities, permanently, with no
actor or human-approval override path -- enforced structurally by the
closed operation allowlist (`op-not-allowed-violations`), not just
documented.

## Verification

- `cloud-itonami-isic-1030`, baseline (before any fix), from a fresh
  clone: `clojure -M:test` -- raw final line: `Ran 28 tests containing
  106 assertions.` / `4 failures, 2 errors.`
- After the rebuild: `clojure -M:test` -- raw final line: `Ran 39 tests
  containing 160 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors (5 pre-existing/reference-matching style
  warnings: an unused `ok?` destructure in `phase/verdict->disposition`
  that `cloud-itonami-isic-1050`'s own `phase.cljc` has identically, and
  unused `are` refers in two test namespaces that never used it).
- `grep` confirmed no remaining meat-processing domain leakage in
  `src`/`test`/`deps.edn` except one intentional sibling-actor mention
  ("dairy/bakery/meat processing" in `governor.cljc`'s family-pattern
  comment).
- No JVM-only interop anywhere in `src`/`test` (`grep` for `System/`/
  `java.`/`Exception`/`clojure.lang`/`#?(:clj` returned nothing) -- this
  actor needed no host-clock reader-conditional at all, unlike
  `cloud-itonami-isic-1071`'s `now-epoch-ms`.
- Commit `4fc7a08` pushed directly to `cloud-itonami-isic-1030`'s `main`
  (`7d0e41f..4fc7a08`), confirmed landed via `git merge-base --is-
  ancestor` against `origin/main` and cross-checked against the GitHub
  API's `commits/main` SHA.
- Independent re-verification (fresh clone into a new scratch directory,
  post-merge) is recorded in the `kotoba-lang/industry` registry-
  promotion follow-up ADR entry / commit for `"1030"`.

## Consequences

(+) `cloud-itonami-isic-1030` now has real, tested fruit & vegetable
processing domain content and a Governor that enforces all four of the
domain design's explicit HARD invariants, closing both the content-
accuracy gap (meat-processing leftovers) and the governance gap (missing
allowlist/effect/registration checks, stake read from the wrong place)
that independent verification found beyond what the prior attempt's
registry-only self-report covered.
(+) `kotoba-lang/industry` registry `"1030"` entry promoted from `:spec`
to `:implemented` in a follow-up commit, referencing this ADR.
(-) `fruitprocessing.advisor` remains a mock/testing advisor (no real
langchain/LLM backend); `fruitprocessing.operation/build` remains a
stub function standing in for a real langgraph-clj StateGraph. Both are
documented as such and match every other cloud-itonami ISIC actor at
this maturity stage -- wiring a real LLM backend and full graph
integration is a natural, contained future extension, not required for
this ADR's verification bar.
