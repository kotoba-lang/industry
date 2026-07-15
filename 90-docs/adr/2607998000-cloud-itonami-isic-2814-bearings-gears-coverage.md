# ADR-2607998000: cloud-itonami-isic-2814 (Manufacture of bearings, gears, gearing and driving elements) plant-operations-coordination actor -- fresh scaffold

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: `cloud-itonami-isic-2818` (Manufacture of power-driven hand
tools -- the closest structural analog: also a back-office plant-
operations coordination actor for a fixed manufacturing plant with
precision-tested, discrete-unit finished-goods output and a real
physical/consumer safety dimension, mirrored module-for-module here and
adapted from motor-assembly/housing-molding/hipot-test vocabulary to
precision-machining/heat-treatment/grinding/dimensional-tolerance-test
vocabulary), `cloud-itonami-isic-2710` (Manufacture of electric motors,
generators, transformers and electricity distribution and control
apparatus -- 2818's own closest analog, same two-entity verified/
registered gate shape), `kotoba-lang/industry` registry's `"2814"`
catalog entry (was `:maturity :spec` with a stale
`gftdcojp/cloud-itonami-C2814` placeholder repo reference that was never
created; now `:implemented`)

## Context

This is part of an ongoing careful, smaller-batch ISIC-coverage rollout
(one class per agent, a capable model, mandatory verification) after a
prior 18-agent haiku batch produced a 61% defect rate; 100+ consecutive
agents on this stricter protocol had all succeeded before this one.

Before any work, the registry entry's `:id`/`:name` pair was
independently re-verified against a fresh `git clone` of
`kotoba-lang/industry`: `{:id "2814" :name "Manufacture of bearings,
gears, gearing and driving elements" ...}` confirmed verbatim at
`resources/kotoba/industry/registry.edn` around line 4949 -- this fleet
has previously mislabeled an assigned ISIC class more than once, so this
check runs before any design work, not after. `gh api
repos/cloud-itonami/cloud-itonami-isic-2814` returned 404 -- fresh
scaffold, no prior repository at either the registry's stale
`gftdcojp/cloud-itonami-C2814` placeholder or the real `cloud-itonami`
org.

## Decision: BearGearAdvisor ⊣ Bearings, Gears and Driving Elements Plant Operations Governor, plant-operations coordination only

Implemented `cloud-itonami-isic-2814` end-to-end in `src/beargearmfg`
using the SAME `.cljc` actor pattern (langgraph-clj StateGraph,
mock-by-default advisor, single MemStore backend, 0→3 phase rollout)
`cloud-itonami-isic-2818` (Manufacture of power-driven hand tools) uses,
mirrored module-for-module (`beargearmfg.*` in place of
`powertoolmfg.*`) and adapted from power-driven-hand-tool vocabulary to
bearing/gear/driving-element vocabulary: `:precision-machining-line` and
`:grinding-line` equipment kinds in place of `:motor-assembly-line` and
`:housing-molding-press`; `:ball-bearing`/`:roller-bearing`/`:spur-
gear`/`:helical-gear`/`:worm-gear` product types in place of
`:drill`/`:circular-saw`/`:jigsaw`/`:sander`/`:angle-grinder`; a
`:tolerance-test-um` dimensional-tolerance plausibility check (0-300 um,
informed by ISO 492 bearing tolerance classes and AGMA/ISO 1328 gear
quality grades) in place of `:hipot-test-kv` (0-15 kV, IEC 60745
double-insulation withstand test).

This actor is **strictly plant-operations coordination, not direct
machining/grinding-line-equipment control authority**. It never
touches the precision-machining/heat-treatment/grinding equipment
directly, and it is never a bearing/gear tolerance-class certification
authority (e.g. ISO 492 bearing tolerance class or AGMA/ISO 1328 gear
quality mark) -- both are permanent, un-overridable HARD governor
blocks (`beargearmfg.governor/equipment-actuate-blocked-violations`
and `certification-authority-blocked-violations`), not policy that
could be relaxed by a future rollout phase.

### Closed op-allowlist (4 ops, all `:effect :propose`)

- `:log-production-batch` -- machining/heat-treatment/grinding batch,
  output-quality/tolerance-test data logging. Administrative, not an
  operational decision; the ONLY op eligible to auto-commit, and only
  at phase 3 when governor-clean.
- `:schedule-maintenance` -- machining/grinding-equipment maintenance
  scheduling proposal. Never in any phase's `:auto` set, matching
  2818's own `:schedule-maintenance` posture -- always human approval,
  even when clean.
- `:flag-safety-concern` -- surfaces a materials-safety/equipment-
  safety concern. ALWAYS escalates to a human, unconditionally, at
  every phase.
- `:coordinate-shipment` -- outbound product shipment coordination
  proposal. Never in any phase's `:auto` set; always escalates for
  human approval even when clean and within quantity.

### Governor -- twelve concrete checks elaborating four HARD invariants, all un-overridable by human approval

1. Request-level propose-only (`:effect` must be `:propose`)
2. Closed op allowlist (the four ops above only)
3. Closed proposal-effect allowlist (`:batch/upsert`/`:maintenance/
   schedule`/`:safety-concern/flag`/`:shipment/propose` only -- no
   direct machining/grinding-line-equipment control effect)
4. Equipment-actuate blocked (`:actuate-equipment? true` on a
   `:schedule-maintenance` proposal, PERMANENT)
5. Certification-authority blocked (`:issue-certification? true` on
   ANY proposal, PERMANENT -- this actor never self-issues a bearing/
   gear tolerance-class conformance mark)
6. Equipment not independently verified/registered before maintenance
   scheduling
7. Already-scheduled double-schedule guard (dedicated `:scheduled?`
   fact)
8. Batch not independently verified/registered before shipment
   coordination
9. Shipment quantity independently recomputed against the batch's own
   logged production quantity
10. Invalid product-type (closed 5-value set)
11. Invalid `:tolerance-test-um` (0-300 um plausibility bound)
12. Invalid `:defect-rate-percent` (0-100% plausibility bound)

Plus the SOFT confidence-floor/high-stakes escalation gate (low
confidence, or `:stake :coordination/safety-concern` -- ALWAYS set for
`:flag-safety-concern`).

### Domain adaptation: dimensional-tolerance test in place of hipot voltage test

2818's routine QC field is `:hipot-test-kv` (double-insulation
withstand-test voltage, 0-15 kV, IEC 60745). This vertical's routine QC
field is instead `:tolerance-test-um` (dimensional-tolerance
measurement in micrometers), since precision bearings and gears are
QC'd on dimensional runout/profile accuracy rather than electrical
withstand voltage. The 0-300 um plausibility ceiling is informed by ISO
492 bearing tolerance classes (P0 through P2, single-digit-to-tens-of-
micrometer runout) and AGMA/ISO 1328 gear quality grades (base-
pitch/profile error that can run into the low hundreds of micrometers
for coarser grades) -- generously bounded well above any real routine
measurement, the same "ceiling above practice, not at it" discipline
2818's own 15 kV hipot ceiling and every prior sibling's plausibility
bound establish.

### Two equipment kinds, mirroring 2818's two-kind pattern

`:precision-machining-line` (verified+registered, schedulable) and
`:grinding-line` (unverified/unregistered, blocks scheduling) --
mirroring 2818's `:motor-assembly-line`/`:housing-molding-press` pair
exactly. Heat-treatment appears in this actor's own scope description
(`precision-machining/heat-treatment/grinding lines`) but, like 2818's
own `:test-bench` role, is not modeled as a third separate schedulable
equipment entity -- the task brief names `:schedule-maintenance` as
"machining/grinding-equipment maintenance scheduling" specifically (not
heat-treatment equipment), and the HARD equipment-control-block
language is scoped to "machining/grinding-line-equipment control" the
same way.

### No JVM-only interop anywhere in `src/`

Per this workspace's cljs-first `.cljc` runtime-priority rule (`kotoba
wasm` > `clojurewasm` > `ClojureScript` > `nbb`, JVM/`bb` downgraded to a
last resort), all source in `src/beargearmfg/` is `.cljc` with the
`#?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])` and
`#?(:clj Exception :cljs :default)` portable idioms only -- no
`java.*`/`System.*` references, and the actor graph is invoked
exclusively via `langgraph.graph/run*` (not `.invoke`, which is not
cljs-portable), matching 2818's own portability discipline exactly.

## What this actor does NOT do

Direct precision-machining/grinding-line-equipment control and
bearing/gear tolerance-class certification issuance remain exclusive to
the licensed plant supervisor / accredited certification body,
permanently -- enforced structurally by the closed op-allowlist, the
permanent equipment-actuate block, and the permanent certification-
authority block, not just documented in the README.

## Verification

- `cloud-itonami-isic-2814`: `clojure -M:test` -- raw final line: `Ran
  77 tests containing 210 assertions.` / `0 failures, 0 errors.`
  Re-run green a second time against a fresh `git clone` after pushing
  to `main` (see below).
- `clojure -M:lint` -- `linting took 587ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` (the `beargearmfg.sim` demo driver) exercises
  the full coordination episode (log-production-batch auto-commit,
  schedule-maintenance/flag-safety-concern/coordinate-shipment
  escalate-then-approve) plus every HARD-hold scenario
  (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, already-scheduled, invalid-product-type, invalid-tolerance-
  test-um, invalid-defect-rate, certification-authority-blocked) and
  exits 0 with no exceptions.
- All source is `.cljc`; no JVM-only interop (confirmed by manual
  review of every `#?(:clj ...)` branch -- none reference
  `java.*`/`System.*`).
- `grep -rc "â"` across all source/doc files in the new repo returned
  no matches (no mojibake).
- Repo scaffolded and tested from a uniquely-named scratch directory
  (`/private/tmp/.../scratchpad/2814-work/cloud-itonami/
  cloud-itonami-isic-2814`, outside the shared superproject checkout),
  then pushed to a fresh GitHub repo (`cloud-itonami/
  cloud-itonami-isic-2814`, created via `gh repo create`) as its
  initial `main` commit. Confirmed landed: `gh api
  repos/cloud-itonami/cloud-itonami-isic-2814/commits/main --jq .sha`
  returned `4a0334d202fbf78b77010bd5f445afa315d5e17b`.
- This ADR itself was authored and committed from a sibling `git
  worktree` outside the superproject root, branched from a freshly
  fetched `origin/main`, landed via a server-side merge (`gh api
  repos/com-junkawasaki/root/merges`), per this workspace's
  concurrent-session worktree discipline.
- `kotoba-lang/industry` registry `"2814"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-C2814` placeholder
  to the real repo, comment points at this ADR); `test/kotoba/
  industry_test.clj`'s maturity-summary assertion bumped to match the
  live-recomputed implemented-entry count (recomputed from a freshly
  re-fetched `origin/main`, not assumed). Full `kotoba-lang/industry`
  suite re-run green post-edit and again post-merge against a fresh
  clone; see this session's final report for the raw post-merge
  output.

## Consequences

(+) `cloud-itonami-isic-2814` now exists with a genuinely green,
independently re-verified test suite and a Governor that structurally
enforces its documented invariants (closed op-allowlist, `:effect
:propose`-only, equipment/batch-registration, permanent equipment-
actuate and certification-authority blocks) rather than only claiming
them in prose.
(+) `kotoba-lang/industry` registry `"2814"` entry promoted to
`:maturity :implemented`, with its stale placeholder repo reference
corrected.
(+) Demonstrates faithful module-for-module adaptation of a verified
sibling (2818) to a distinct precision-manufacturing domain (dimensional
tolerance rather than electrical withstand voltage as the routine QC
gate), without inventing scope beyond the task brief's explicit
four-op/HARD-invariant/ESCALATE shape.
(-) `beargearmfg.advisor` remains a `mock-advisor` (no real
LLM/langchain integration yet) -- consistent with every other actor in
this fleet's current maturity tier, not a regression.
(-) Still a simulation/proposal layer, not a real plant-operations
control system; no integration with real plant-management databases
(equipment telemetry, batch tracking, freight dispatch, certification-
body APIs) -- a standalone coordinator blueprint, matching every prior
sibling's own stated scope limit.
