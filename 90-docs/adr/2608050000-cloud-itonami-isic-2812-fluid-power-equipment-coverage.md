# ADR-2608050000: cloud-itonami-isic-2812 (Manufacture of fluid power equipment) plant-operations-coordination actor -- fresh scaffold

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: `cloud-itonami-isic-2814` (Manufacture of bearings, gears,
gearing and driving elements -- the closest structural analog: also a
back-office plant-operations coordination actor for a fixed
manufacturing plant with QC-tested, discrete-unit finished-goods
output and a real physical/consumer safety dimension, mirrored
module-for-module here and adapted from precision-machining/heat-
treatment/grinding/dimensional-tolerance-test vocabulary to
machining/assembly/pressure-test-bench/pressure-test vocabulary),
`cloud-itonami-isic-2818` (Manufacture of power-driven hand tools --
2814's own closest analog, same two-entity verified/registered gate
shape), `kotoba-lang/industry` registry's `"2812"` catalog entry (was
`:maturity :spec` with a stale `gftdcojp/cloud-itonami-C2812`
placeholder repo reference that was never created; now `:implemented`)

## Context

This is part of an ongoing careful, smaller-batch ISIC-coverage rollout
(one class per agent, a capable model, mandatory verification) after a
prior 18-agent haiku batch produced a 61% defect rate; 114+ consecutive
agents on this stricter protocol had all succeeded before this one.

Before any work, the registry entry's `:id`/`:name` pair was
independently re-verified against a fresh `git` blob fetch of
`kotoba-lang/industry` via the GitHub git-data API (not
`raw.githubusercontent.com`, which can be stale): `{:id "2812" :name
"Manufacture of fluid power equipment" ...}` confirmed verbatim at
`resources/kotoba/industry/registry.edn` -- this fleet has previously
mislabeled an assigned ISIC class more than once, so this check runs
before any design work, not after. `gh api
repos/cloud-itonami/cloud-itonami-isic-2812` returned 404 -- fresh
scaffold, no prior repository at either the registry's stale
`gftdcojp/cloud-itonami-C2812` placeholder or the real `cloud-itonami`
org.

## Decision: FluidPowerAdvisor ⊣ Fluid Power Equipment Plant Operations Governor, plant-operations coordination only

Implemented `cloud-itonami-isic-2812` end-to-end in `src/fluidpowermfg`
using the SAME `.cljc` actor pattern (langgraph-clj StateGraph,
mock-by-default advisor, single MemStore backend, 0->3 phase rollout)
`cloud-itonami-isic-2814` (Manufacture of bearings, gears, gearing and
driving elements) uses, mirrored module-for-module (`fluidpowermfg.*`
in place of `beargearmfg.*`) and adapted from bearing/gear vocabulary
to hydraulic/pneumatic fluid-power-equipment vocabulary:
`:machining-line` and `:pressure-test-bench` equipment kinds in place
of `:precision-machining-line` and `:grinding-line`;
`:hydraulic-pump`/`:hydraulic-cylinder`/`:hydraulic-valve`/`:hydraulic-
motor`/`:pneumatic-cylinder`/`:pneumatic-valve` product types in place
of `:ball-bearing`/`:roller-bearing`/`:spur-gear`/`:helical-gear`/
`:worm-gear`; a `:pressure-test-bar` proof-pressure plausibility check
(0-2000 bar, informed by real hydraulic/pneumatic fluid-power test
practice) in place of `:tolerance-test-um` (0-300 um, ISO 492/AGMA/ISO
1328 dimensional-tolerance test).

This actor is **strictly plant-operations coordination, not direct
machining/assembly-line-equipment control authority**. It never
touches the machining/assembly/pressure-test-bench equipment directly,
and it is never a pressure-safety certification authority (e.g. ASME
BPVC / PED 2014/68/EU pressure-equipment conformity mark or ISO
4413/ISO 4414 fluid-power-system safety compliance mark) -- both are
permanent, un-overridable HARD governor blocks
(`fluidpowermfg.governor/equipment-actuate-blocked-violations` and
`certification-authority-blocked-violations`), not policy that could
be relaxed by a future rollout phase.

### Closed op-allowlist (4 ops, all `:effect :propose`)

- `:log-production-batch` -- machining/assembly/pressure-test batch,
  output-quality data logging. Administrative, not an operational
  decision; the ONLY op eligible to auto-commit, and only at phase 3
  when governor-clean.
- `:schedule-maintenance` -- machining/assembly/test-bench-equipment
  maintenance scheduling proposal. Never in any phase's `:auto` set,
  matching 2814's own `:schedule-maintenance` posture -- always human
  approval, even when clean.
- `:flag-safety-concern` -- surfaces a pressure-rating/hydraulic-
  fluid-safety concern. ALWAYS escalates to a human, unconditionally,
  at every phase.
- `:coordinate-shipment` -- outbound product shipment coordination
  proposal. Never in any phase's `:auto` set; always escalates for
  human approval even when clean and within quantity.

### Governor -- twelve concrete checks elaborating four HARD invariants, all un-overridable by human approval

1. Request-level propose-only (`:effect` must be `:propose`)
2. Closed op allowlist (the four ops above only)
3. Closed proposal-effect allowlist (`:batch/upsert`/`:maintenance/
   schedule`/`:safety-concern/flag`/`:shipment/propose` only -- no
   direct machining/assembly-line-equipment control effect)
4. Equipment-actuate blocked (`:actuate-equipment? true` on a
   `:schedule-maintenance` proposal, PERMANENT)
5. Certification-authority blocked (`:issue-certification? true` on
   ANY proposal, PERMANENT -- this actor never self-issues a
   pressure-equipment conformity or fluid-power-system safety
   compliance mark)
6. Equipment not independently verified/registered before maintenance
   scheduling
7. Already-scheduled double-schedule guard (dedicated `:scheduled?`
   fact)
8. Batch not independently verified/registered before shipment
   coordination
9. Shipment quantity independently recomputed against the batch's own
   logged production quantity
10. Invalid product-type (closed 6-value set)
11. Invalid `:pressure-test-bar` (0-2000 bar plausibility bound)
12. Invalid `:defect-rate-percent` (0-100% plausibility bound)

Plus the SOFT confidence-floor/high-stakes escalation gate (low
confidence, or `:stake :coordination/safety-concern` -- ALWAYS set for
`:flag-safety-concern`).

### Domain adaptation: proof-pressure test in place of dimensional-tolerance test

2814's routine QC field is `:tolerance-test-um` (dimensional-tolerance
measurement, 0-300 um). This vertical's routine QC field is instead
`:pressure-test-bar` (hydraulic/pneumatic proof-pressure test, in
bar), since hydraulic/pneumatic fluid power equipment is QC'd on
proof/burst-pressure withstand rather than dimensional runout. The
0-2000 bar plausibility ceiling is informed by real fluid-power test
practice: rated system pressures for high-pressure hydraulic equipment
commonly run up to roughly 700 bar, and proof/pressure tests are
typically performed at 1.5x-2x the rated working pressure (ISO 4413
hydraulic / ISO 4414 pneumatic fluid-power-system practice) --
generously bounded well above any real routine measurement, the same
"ceiling above practice, not at it" discipline 2814's own 300 um
tolerance ceiling and every prior sibling's plausibility bound
establish.

### Two equipment kinds, mirroring 2814's two-kind pattern

`:machining-line` (verified+registered, schedulable) and
`:pressure-test-bench` (unverified/unregistered, blocks scheduling) --
mirroring 2814's `:precision-machining-line`/`:grinding-line` pair
exactly. Assembly appears in this actor's own scope description
(`machining/assembly/pressure-test-bench lines`) but, like 2814's own
heat-treatment role, is not modeled as a third separate schedulable
equipment entity -- the task brief names `:schedule-maintenance` as
"machining/assembly/test-bench-equipment maintenance scheduling"
generically (not a distinct assembly-line entity), and the HARD
equipment-control-block language is scoped to "machining/assembly-
line-equipment control" the same way.

### No JVM-only interop anywhere in `src/`

Per this workspace's cljs-first `.cljc` runtime-priority rule (`kotoba
wasm` > `clojurewasm` > `ClojureScript` > `nbb`, JVM/`bb` downgraded to
a last resort), all source in `src/fluidpowermfg/` is `.cljc` with the
`#?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])` and
`#?(:clj Exception :cljs :default)` portable idioms only -- no
`java.*`/`System.*` references, and the actor graph is invoked
exclusively via `langgraph.graph/run*` (not `.invoke`, which is not
cljs-portable), matching 2814's own portability discipline exactly.

## What this actor does NOT do

Direct machining/assembly-line-equipment control and pressure-
equipment conformity/fluid-power-system safety compliance issuance
remain exclusive to the licensed plant supervisor / accredited
certification body, permanently -- enforced structurally by the
closed op-allowlist, the permanent equipment-actuate block, and the
permanent certification-authority block, not just documented in the
README.

## Verification

- `cloud-itonami-isic-2812`: `clojure -M:test` -- raw final line: `Ran
  77 tests containing 211 assertions.` / `0 failures, 0 errors.`
  Re-run green a second time against a fresh `git clone` after pushing
  to `main` (identical output).
- `clojure -M:lint` -- `linting took 721ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` (the `fluidpowermfg.sim` demo driver) exercises
  the full coordination episode (log-production-batch auto-commit,
  schedule-maintenance/flag-safety-concern/coordinate-shipment
  escalate-then-approve) plus every HARD-hold scenario
  (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, already-scheduled, invalid-product-type, invalid-pressure-
  test-bar, invalid-defect-rate, certification-authority-blocked) and
  exits 0 with no exceptions.
- All source is `.cljc`; no JVM-only interop (confirmed by manual
  review of every `#?(:clj ...)` branch -- none reference
  `java.*`/`System.*`).
- `grep -rc "â"` across all source/doc files in the new repo returned
  no matches (no mojibake).
- Repo scaffolded and tested from a uniquely-named scratch directory
  (`/private/tmp/.../scratchpad/isic-2812-work/orgs/cloud-itonami/
  cloud-itonami-isic-2812`, outside the shared superproject checkout),
  then pushed to a fresh GitHub repo (`cloud-itonami/
  cloud-itonami-isic-2812`, created via `gh repo create`) as its
  initial `main` commit. Confirmed landed via `git merge-base
  --is-ancestor <local-sha> origin/main` and `gh api
  repos/cloud-itonami/cloud-itonami-isic-2812/git/refs/heads/main`.
- This ADR itself was authored and committed from a sibling `git
  worktree` outside the superproject root, branched from a freshly
  fetched `origin/main`, landed via a server-side merge (`gh api
  repos/com-junkawasaki/root/merges`), per this workspace's
  concurrent-session worktree discipline.
- `kotoba-lang/industry` registry `"2812"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-C2812` placeholder
  to the real repo); `test/kotoba/industry_test.clj`'s
  maturity-summary assertion bumped to match the live-recomputed
  implemented-entry count (recomputed from a freshly re-fetched
  `origin/main`, not assumed). Full `kotoba-lang/industry` suite
  re-run green post-edit and again post-merge against a fresh clone;
  see this session's final report for the raw post-merge output.
- The `:itonami.blueprint/governor` keyword,
  `:fluid-power-plant-operations-governor`, was grep-verified UNIQUE
  fleet-wide (`gh search code "fluid-power-plant-operations-governor"
  --owner cloud-itonami`, zero hits before this repo was created).

## Consequences

(+) `cloud-itonami-isic-2812` now exists with a genuinely green,
independently re-verified test suite and a Governor that structurally
enforces its documented invariants (closed op-allowlist, `:effect
:propose`-only, equipment/batch-registration, permanent equipment-
actuate and certification-authority blocks) rather than only claiming
them in prose.
(+) `kotoba-lang/industry` registry `"2812"` entry promoted to
`:maturity :implemented`, with its stale placeholder repo reference
corrected.
(+) Demonstrates faithful module-for-module adaptation of a verified
sibling (2814) to a distinct precision-manufacturing domain
(proof-pressure withstand rather than dimensional tolerance as the
routine QC gate), without inventing scope beyond the task brief's
explicit four-op/HARD-invariant/ESCALATE shape.
(-) `fluidpowermfg.advisor` remains a `mock-advisor` (no real
LLM/langchain integration yet) -- consistent with every other actor in
this fleet's current maturity tier, not a regression.
(-) Still a simulation/proposal layer, not a real plant-operations
control system; no integration with real plant-management databases
(equipment telemetry, batch tracking, freight dispatch, certification-
body APIs) -- a standalone coordinator blueprint, matching every prior
sibling's own stated scope limit.
