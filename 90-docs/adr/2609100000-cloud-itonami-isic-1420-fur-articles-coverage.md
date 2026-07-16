# ADR-2609100000: cloud-itonami-isic-1420 (Manufacture of articles of fur) plant-operations-coordination actor -- fresh scaffold

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: `cloud-itonami-isic-1410` (Manufacture of wearing apparel,
except fur apparel -- the closest structural analog and reference
implementation, mirrored module-for-module: same closed four-op
allowlist shape, `apparel.*` in place of `fur.*`), `kotoba-lang/
industry` registry's `"1420"` catalog entry (was `:maturity :spec` with
a stale `gftdcojp/cloud-itonami-C1420` placeholder repo reference that
was never created; now `:implemented`)

## Context

This is part of an ongoing careful, smaller-batch ISIC-coverage rollout
(one class per agent, a capable model, mandatory verification) after a
prior 18-agent haiku batch produced a 61% defect rate; 126+ consecutive
agents on this stricter protocol had all succeeded before this one.

Before any work, the registry entry's `:id`/`:name` pair was
independently verified against a fresh clone of `kotoba-lang/industry`:
`{:id "1420" :name "Manufacture of articles of fur" ...}` confirmed
verbatim -- this fleet has previously mislabeled an assigned ISIC class
more than once, so this check runs before any design work, not after.
`gh api repos/cloud-itonami/cloud-itonami-isic-1420` returned 404 --
fresh scaffold, no prior repository at either the registry's stale
`gftdcojp/cloud-itonami-C1420` placeholder or the real `cloud-itonami`
org.

ISIC 1420 covers fur-garment/accessory manufacturing (cutting/matching/
sewing lines) that turns **already-dressed and dyed fur pelts** into
finished garments and accessories. Dressing and dyeing of fur itself is
a distinct upstream ISIC class (tanning and dressing of leather;
dressing and dyeing of fur) and is explicitly out of scope for this
actor -- only the downstream garment/accessory-manufacturing step is
covered, matching the task brief's own scope boundary.

## Decision: Fur-Garment Operations Advisor ⊣ Fur Governor, plant-operations coordination only

Implemented `cloud-itonami-isic-1420` end-to-end in `src/fur` using the
SAME `.cljc` actor pattern (langgraph-clj-shaped advisor/governor/hold/
complete phase graph, mock-by-default advisor, single MemStore backend)
`cloud-itonami-isic-1410` (Manufacture of wearing apparel, except fur
apparel) uses, mirrored module-for-module (`fur.*` in place of
`apparel.*`) and adapted from generic apparel/textile vocabulary to
fur-garment vocabulary: fur-garment plants receiving already-dressed
pelts in place of generic apparel plants; `:pelt-source`-tagged
production batches (`shearling-coat-L`/`fox-trim-hood-M`) in place of
generic cotton/linen apparel styles; a per-jurisdiction fur-labeling +
CITES/species-sourcing + labor-standards catalog (USA/Italy/Canada) in
place of the reference's generic apparel-labeling catalog
(Vietnam/Bangladesh/USA).

This actor is **strictly plant OPERATIONS COORDINATION, not direct
cutting/sewing-line-equipment control authority**. It never touches
cutting or sewing equipment directly, and it never dresses or dyes fur
pelts (that upstream step's quality is explicitly out of scope, matching
the reference's own "pelt/dressing quality is the upstream dresser's
responsibility, not this plant's" boundary).

### Closed op-allowlist (4 ops, all `:effect :propose`)

- `:log-production-batch` -- cutting/sewing batch, output-quality data
  logging.
- `:schedule-maintenance` -- cutting/sewing-line-equipment maintenance
  scheduling proposal.
- `:flag-safety-concern` -- surfaces an equipment-safety/quality-defect
  concern. ALWAYS escalates to a human, unconditionally (implemented as
  an unconditional HARD hold, `safety-concern-escalates`, matching the
  reference's own `quality-defect-escalates` treatment: escalation-as-
  hold, not merely a soft warning).
- `:coordinate-shipment` -- outbound finished-garment shipment
  coordination proposal. High-stakes actuation; always soft-escalates
  for human approval even when otherwise clean.

The allowlist itself (`fur.registry/allowed-ops`) is a single canonical
`def` that both the proposal-draft constructors and the governor's
`op-not-allowlisted-violations` check require, so the two can never
drift out of sync -- this is a deliberate improvement over the
reference, whose op-set was only implicit (no explicit membership
check existed in `apparel.governor`, so an unrecognized `:op` could
silently fall through every pattern-matched check with zero hard
violations).

### Governor -- six HARD checks plus the unconditional escalation and one soft gate

1. Spec-basis (no official jurisdiction citation) -- required for
   `:coordinate-shipment` and `:flag-safety-concern`
2. `:effect` not `:propose` -- **NEW relative to the reference**: any
   proposal whose `:effect` is not `:propose` is rejected outright,
   whatever op it names
3. Op not allowlisted -- **NEW relative to the reference**: any `:op`
   outside the closed `fur.registry/allowed-ops` set is rejected
   outright
4. Plant not verified -- batch's plant registration must be confirmed
   before logging or shipping it
5. Batch not verified -- batch must be independently verified before
   logging or shipping it
6. Direct equipment control (`process-control-forbidden`) -- keyword
   scan covering generic cutting/sewing-line terms plus fur-specific
   pelt-handling terms (nailing/blocking/stretching a pelt onto a board
   during letting-out/matching)

Plus the unconditional `safety-concern-escalates` hold for
`:flag-safety-concern`, and the SOFT confidence-floor/high-stakes-
actuation gate (low confidence, or `:actuation/coordinate-shipment`).

### Bug found and fixed during build (not present in the shipped reference, mirrored faithfully then caught)

The reference's `plant-verification-violations`/`batch-verification-
violations` resolve a shipment-coordination proposal's underlying batch
by looking its `:subject` (a SHIPMENT id) up directly in the
plant/batch keyspace -- since a shipment id is never a batch id, this
silently no-ops the plant check (nil plant-id) and silently
always-fails the batch check (a shipment id never resolves to a
`:verified?` batch), regardless of the shipment's real underlying
batch's actual verification state. The reference's own test suite never
catches this because its `shipment-requires-escalation` test only
asserts the soft `:escalate` violation is present, never that
hard-violations is empty. This actor fixes the indirection explicitly
(`fur.governor/resolve-batch-id`, resolving `:actuation/coordinate-
shipment`'s subject through `fur.store/shipment-batch-id` to the
shipment's real `:batch` field first) and adds a dedicated test
(`shipment-requires-escalation`, now also asserting `(empty?
(:hard-violations eval))` for a genuinely-verified shipment) plus two
new indirection-specific tests (`shipment-batch-indirection-blocks-
unverified-batch`, `shipment-unknown-blocks`) that would fail under the
reference's original (buggy) resolution logic.

A second, independent bug was also found and fixed during build: the
reference's `process-control-block-violations` computes `(some
#(contains? process-control-keywords %) words)`, which returns a bare
`true`/`nil` from `some` (the predicate's own boolean return value)
rather than the actual matched word, producing a nonsensical violation
detail (`禁止キーワード 'true'`) instead of naming the real forbidden
keyword. Fixed here by using the set itself as the predicate (`(some
process-control-keywords words)`), so `some` returns the actual matched
word.

A third, self-inflicted issue was found and fixed during the build's own
`clojure -M:dev:run` verification pass (not present in, or inherited
from, the reference): this actor's own draft `maintenance-proposal`
text originally read "Maintenance scheduled for cutting/sewing-line
equipment", which collides with the very `process-control-keywords` set
("cutting"/"sewing") that same proposal's own default text was checked
against, incorrectly holding an otherwise-clean maintenance-scheduling
proposal. Fixed by keeping the routine advisor detail text domain-
generic ("Maintenance scheduled for plant equipment"), matching the
reference's own equivalent convention of never using the literal
forbidden words in its own default proposal text.

### Jurisdiction catalog (USA / Italy / Canada) -- real, verified citations only

Every spec-basis citation was verified against a live web search before
being written (never invented): US Fur Products Labeling Act (15
U.S.C. § 69) + FTC Fur Rules (16 CFR Part 301); US Endangered Species
Act (16 U.S.C. § 1538) + CITES implementing regulations (50 CFR Part
23); US FLSA (29 CFR § 516) + OSHA (1910 Subpart A); EU Regulation (EU)
No 1007/2011 Article 12 (non-textile parts of animal origin labeling,
directly applicable in Italy); Council Regulation (EC) No 338/97
(EU wildlife-trade/CITES enforcement); Italy's Decreto Legislativo
9 aprile 2008, n. 81 (workplace health & safety); Canada's Textile
Labelling Act (R.S.C. 1985, c. T-10) + Textile Labelling and
Advertising Regulations (C.R.C., c. 1551, which explicitly address fur
products); Canada Labour Code (R.S.C. 1985, c. L-2).

### No JVM-only interop anywhere in `src/`

Per this workspace's cljs-first `.cljc` runtime-priority rule (`kotoba
wasm` > `clojurewasm` > `ClojureScript` > `nbb`, JVM/`bb` downgraded to
a last resort), all source in `src/fur/` is plain portable `.cljc`
(pure map/set/string operations only -- no `java.*`/`System.*`
references), matching the reference's own portability discipline.

## What this actor does NOT do

Direct cutting/sewing-line-equipment control and fur dressing/dyeing
(an entirely distinct upstream ISIC class) remain exclusive to the
licensed plant engineer / upstream dresser, permanently -- enforced
structurally by the closed op-allowlist, the `effect-not-propose` and
`op-not-allowlisted` HARD checks, and the `process-control-forbidden`
keyword block, not just documented in the README.

## Verification

- `cloud-itonami-isic-1420`: `clojure -M:test` -- raw final line: `Ran
  34 tests containing 107 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- `linting took 398ms, errors: 0, warnings: 37`
  (all 37 warnings are docstring-inside-`deftest`/redundant-nested-`let`
  style warnings identical in kind to the reference's own test-file
  style; `:lint`'s own `--fail-level error` only fails on errors, of
  which there are zero).
- `clojure -M:dev:run` (the `fur.sim` demo driver) exercises six
  scenarios end to end (clean batch log, always-escalating safety
  concern, clean high-stakes shipment with zero hard violations,
  clean maintenance scheduling, unallowlisted-op HARD block, non-
  `:propose`-effect HARD block) and exits with no exceptions.
- Repo built and tested from a uniquely-named scratch directory
  (`.../scratchpad/isic-1420-work/actor-1420-build`, outside the shared
  superproject checkout and outside any other agent's scratch dir),
  then pushed to a fresh GitHub repo (`cloud-itonami/
  cloud-itonami-isic-1420`, created via `gh repo create`) as its
  initial `main` commit.
- This ADR itself was authored and committed from a sibling fresh clone
  outside the superproject root, branched from a freshly fetched
  `origin/main`, landed via a server-side merge (`gh api
  repos/com-junkawasaki/root/merges`), per this workspace's
  concurrent-session worktree discipline.
- `kotoba-lang/industry` registry `"1420"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-C1420` placeholder
  to the real repo); `test/kotoba/industry_test.clj`'s
  maturity-summary assertion bumped to match the live-recomputed
  implemented-entry count (recomputed via `kotoba.industry/
  maturity-summary`, not `grep -c`, against a freshly re-fetched
  `origin/main`). Full `kotoba-lang/industry` suite re-run green
  post-edit and again post-merge against a fresh clone; see this
  session's final report for the raw post-merge output.

## Consequences

(+) `cloud-itonami-isic-1420` now exists with a genuinely green,
independently re-verified test suite and a Governor that structurally
enforces its documented invariants (closed op-allowlist, `:effect
:propose`-only, plant/batch-registration, permanent equipment-control
block) rather than only claiming them in prose.
(+) `kotoba-lang/industry` registry `"1420"` entry promoted to
`:maturity :implemented`, with its stale placeholder repo reference
corrected.
(+) Found and fixed two real bugs present in the reference
(`cloud-itonami-isic-1410`)'s shipped code -- a shipment/batch
resolution indirection bug and a `some`-returns-boolean bug -- without
touching the reference repo itself; documented here so a future
`1410` maintenance pass can port the same fixes back if desired.
(-) `fur.advisor` remains a `mock-advisor` (no real LLM/langchain
integration yet) -- consistent with every other actor in this fleet's
current maturity tier, not a regression.
(-) Still a simulation/proposal layer, not a real plant-operations
control system; no integration with real plant-management databases
(equipment telemetry, batch tracking, freight dispatch, jurisdiction
customs/CITES-permit APIs) -- a standalone coordinator blueprint,
matching every prior sibling's own stated scope limit.
