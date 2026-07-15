# ADR-2608500000: cloud-itonami-isic-2815 (Manufacture of ovens, furnaces and furnace burners) plant-operations-coordination actor -- fresh scaffold

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: `cloud-itonami-isic-2812` (Manufacture of fluid power
equipment -- the closest structural analog: also a back-office
plant-operations coordination actor for a fixed manufacturing plant
with QC-tested, discrete-unit finished-goods output and a real
physical/consumer safety dimension, mirrored module-for-module here
and adapted from machining/assembly/pressure-test-bench/pressure-test
vocabulary to fabrication/assembly/thermal-test-bench/thermal-test
vocabulary), `cloud-itonami-isic-2814` (Manufacture of bearings,
gears, gearing and driving elements -- 2812's own closest analog, same
two-entity verified/registered gate shape), `kotoba-lang/industry`
registry's `"2815"` catalog entry (was `:maturity :spec` with a stale
`gftdcojp/cloud-itonami-C2815` placeholder repo reference that was
never created; now `:implemented`)

## Context

This is part of an ongoing careful, smaller-batch ISIC-coverage rollout
(one class per agent, a capable model, mandatory verification) after a
prior 18-agent haiku batch produced a 61% defect rate; 126+ consecutive
agents on this stricter protocol had all succeeded before this one.

Before any work, the registry entry's `:id`/`:name` pair was
independently re-verified against a fresh `git` blob fetch of
`kotoba-lang/industry` via the GitHub git-data/Contents API (not
`raw.githubusercontent.com`, which can be stale): `{:id "2815" :name
"Manufacture of ovens, furnaces and furnace burners" ...}` confirmed
verbatim at `resources/kotoba/industry/registry.edn` -- this fleet has
previously mislabeled an assigned ISIC class more than once, so this
check runs before any design work, not after. `gh api
repos/cloud-itonami/cloud-itonami-isic-2815` returned 404 -- fresh
scaffold, no prior repository at either the registry's stale
`gftdcojp/cloud-itonami-C2815` placeholder or the real `cloud-itonami`
org.

## Decision: OvenFurnaceAdvisor ⊣ Oven & Furnace Plant Operations Governor, plant-operations coordination only

Implemented `cloud-itonami-isic-2815` end-to-end in `src/ovenfurnacemfg`
using the SAME `.cljc` actor pattern (langgraph-clj StateGraph,
mock-by-default advisor, single MemStore backend, 0->3 phase rollout)
`cloud-itonami-isic-2812` (Manufacture of fluid power equipment) uses,
mirrored module-for-module (`ovenfurnacemfg.*` in place of
`fluidpowermfg.*`) and adapted from hydraulic/pneumatic fluid-power
vocabulary to industrial oven/furnace/furnace-burner vocabulary:
`:fabrication-line` and `:thermal-test-bench` equipment kinds in place
of `:machining-line` and `:pressure-test-bench`;
`:industrial-oven`/`:heat-treatment-furnace`/`:melting-furnace`/
`:furnace-burner` product types in place of `:hydraulic-pump`/
`:hydraulic-cylinder`/`:hydraulic-valve`/`:hydraulic-motor`/
`:pneumatic-cylinder`/`:pneumatic-valve`; a `:thermal-test-degc`
thermal-performance plausibility check (0-3000 degC, informed by real
industrial oven/furnace/burner test practice) in place of
`:pressure-test-bar` (0-2000 bar, ISO 4413/ISO 4414 proof-pressure
test).

This actor is **strictly plant-operations coordination, not direct
fabrication/assembly-line-equipment control authority**. It never
touches the fabrication/assembly/thermal-test-bench equipment
directly, and it is never a combustion-equipment safety-certification
authority (e.g. UL 795 commercial-industrial gas-fired equipment / UL
726 oil-fired equipment listing, CSA certification, or CE marking
under the EU Gas Appliances Regulation 2016/426) -- both are
permanent, un-overridable HARD governor blocks
(`ovenfurnacemfg.governor/equipment-actuate-blocked-violations` and
`certification-authority-blocked-violations`), not policy that could
be relaxed by a future rollout phase.

### Closed op-allowlist (4 ops, all `:effect :propose`)

- `:log-production-batch` -- fabrication/assembly/thermal-test batch,
  output-quality data logging. Administrative, not an operational
  decision; the ONLY op eligible to auto-commit, and only at phase 3
  when governor-clean.
- `:schedule-maintenance` -- fabrication/assembly/test-bench-equipment
  maintenance scheduling proposal. Never in any phase's `:auto` set,
  matching 2812's own `:schedule-maintenance` posture -- always human
  approval, even when clean.
- `:flag-safety-concern` -- surfaces an equipment-safety/burner-
  combustion-safety concern. ALWAYS escalates to a human,
  unconditionally, at every phase.
- `:coordinate-shipment` -- outbound product shipment coordination
  proposal. Never in any phase's `:auto` set; always escalates for
  human approval even when clean and within quantity.

### Governor -- twelve concrete checks elaborating four HARD invariants, all un-overridable by human approval

1. Request-level propose-only (`:effect` must be `:propose`)
2. Closed op allowlist (the four ops above only)
3. Closed proposal-effect allowlist (`:batch/upsert`/`:maintenance/
   schedule`/`:safety-concern/flag`/`:shipment/propose` only -- no
   direct fabrication/assembly-line-equipment control effect)
4. Equipment-actuate blocked (`:actuate-equipment? true` on a
   `:schedule-maintenance` proposal, PERMANENT)
5. Certification-authority blocked (`:issue-certification? true` on
   ANY proposal, PERMANENT -- this actor never self-issues a UL/CSA/CE
   combustion-equipment safety-certification mark)
6. Equipment not independently verified/registered before maintenance
   scheduling
7. Already-scheduled double-schedule guard (dedicated `:scheduled?`
   fact)
8. Batch not independently verified/registered before shipment
   coordination
9. Shipment quantity independently recomputed against the batch's own
   logged production quantity
10. Invalid product-type (closed 4-value set)
11. Invalid `:thermal-test-degc` (0-3000 degC plausibility bound)
12. Invalid `:defect-rate-percent` (0-100% plausibility bound)

Plus the SOFT confidence-floor/high-stakes escalation gate (low
confidence, or `:stake :coordination/safety-concern` -- ALWAYS set for
`:flag-safety-concern`).

### Domain adaptation: thermal-performance test in place of proof-pressure test

2812's routine QC field is `:pressure-test-bar` (hydraulic/pneumatic
proof-pressure test, 0-2000 bar). This vertical's routine QC field is
instead `:thermal-test-degc` (industrial oven/furnace/burner thermal-
performance test, in degrees Celsius), since industrial ovens,
furnaces and furnace burners are QC'd on operating/rated-temperature
withstand rather than pressure withstand. The 0-3000 degC plausibility
ceiling is informed by real industrial-furnace test practice:
heat-treatment furnaces commonly operate in the 200-1300 degC range,
melting furnaces (e.g. steel electric-arc or induction furnaces) can
reach roughly 1700 degC, and specialized high-temperature furnaces
(sintering, glass-melting) can exceed 1600 degC -- generously bounded
well above any real routine measurement, the same "ceiling above
practice, not at it" discipline 2812's own 2000 bar pressure ceiling
and every prior sibling's plausibility bound establish.

### Two equipment kinds, mirroring 2812's two-kind pattern

`:fabrication-line` (verified+registered, schedulable) and
`:thermal-test-bench` (unverified/unregistered, blocks scheduling) --
mirroring 2812's `:machining-line`/`:pressure-test-bench` pair
exactly. Assembly appears in this actor's own scope description
(`fabrication/assembly/thermal-test-bench lines`) but, like 2812's own
posture, is not modeled as a third separate schedulable equipment
entity -- the task brief names `:schedule-maintenance` as
"fabrication/assembly/test-bench-equipment maintenance scheduling"
generically (not a distinct assembly-line entity), and the HARD
equipment-control-block language is scoped to "fabrication/assembly-
line-equipment control" the same way.

### No JVM-only interop anywhere in `src/`

Per this workspace's cljs-first `.cljc` runtime-priority rule (`kotoba
wasm` > `clojurewasm` > `ClojureScript` > `nbb`, JVM/`bb` downgraded to
a last resort), all source in `src/ovenfurnacemfg/` is `.cljc` with the
`#?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])` and
`#?(:clj Exception :cljs :default)` portable idioms only -- no
`java.*`/`System.*` references, and the actor graph is invoked
exclusively via `langgraph.graph/run*` (not `.invoke`, which is not
cljs-portable), matching 2812's own portability discipline exactly.

## What this actor does NOT do

Direct fabrication/assembly-line-equipment control and combustion-
equipment safety-certification (UL/CSA/CE) issuance remain exclusive
to the licensed plant supervisor / accredited certification body,
permanently -- enforced structurally by the closed op-allowlist, the
permanent equipment-actuate block, and the permanent certification-
authority block, not just documented in the README.

## Verification

- `cloud-itonami-isic-2815`: `clojure -M:test` -- raw final line: `Ran
  77 tests containing 209 assertions.` / `0 failures, 0 errors.`
  Re-run green a second time against a fresh `git clone` after pushing
  to `main` (identical output).
- `clojure -M:lint` -- `linting took 814ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` (the `ovenfurnacemfg.sim` demo driver) exercises
  the full coordination episode (log-production-batch auto-commit,
  schedule-maintenance/flag-safety-concern/coordinate-shipment
  escalate-then-approve) plus every HARD-hold scenario
  (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, already-scheduled, invalid-product-type, invalid-thermal-
  test-degc, invalid-defect-rate, certification-authority-blocked) and
  exits 0 with no exceptions.
- All source is `.cljc`; no JVM-only interop (confirmed by manual
  review of every `#?(:clj ...)` branch -- none reference
  `java.*`/`System.*`).
- `grep -rc "â"` across all source/doc files in the new repo returned
  no matches (no mojibake).
- Repo scaffolded and tested from a uniquely-named scratch directory
  (`/private/tmp/.../scratchpad/2815-work/build-2815/orgs/cloud-
  itonami/cloud-itonami-isic-2815`, outside the shared superproject
  checkout), then pushed to a fresh GitHub repo (`cloud-itonami/
  cloud-itonami-isic-2815`, created via `gh repo create`) as its
  initial `main` commit. Confirmed landed via `gh api
  repos/cloud-itonami/cloud-itonami-isic-2815/commits/main`.
- This ADR itself was authored and committed from a sibling `git
  worktree` outside the superproject root, branched from a freshly
  fetched `origin/main`, landed via a server-side merge (`gh api
  repos/com-junkawasaki/root/merges`), per this workspace's
  concurrent-session worktree discipline.
- `kotoba-lang/industry` registry `"2815"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-C2815` placeholder
  to the real repo); `test/kotoba/industry_test.clj`'s
  maturity-summary assertion bumped to match the live-recomputed
  implemented-entry count (recomputed from a freshly re-fetched
  `origin/main`, not assumed). Full `kotoba-lang/industry` suite
  re-run green post-edit and again post-merge against a fresh clone;
  see this session's final report for the raw post-merge output.
- The `:itonami.blueprint/governor` keyword,
  `:oven-furnace-plant-operations-governor`, was grep-verified UNIQUE
  fleet-wide (`gh search code "oven-furnace-plant-operations-governor"
  --owner cloud-itonami`, zero hits before this repo was created).

## Consequences

(+) `cloud-itonami-isic-2815` now exists with a genuinely green,
independently re-verified test suite and a Governor that structurally
enforces its documented invariants (closed op-allowlist, `:effect
:propose`-only, equipment/batch-registration, permanent equipment-
actuate and certification-authority blocks) rather than only claiming
them in prose.
(+) `kotoba-lang/industry` registry `"2815"` entry promoted to
`:maturity :implemented`, with its stale placeholder repo reference
corrected.
(+) Demonstrates faithful module-for-module adaptation of a verified
sibling (2812) to a distinct manufacturing domain (thermal-performance
withstand rather than proof-pressure withstand as the routine QC
gate), without inventing scope beyond the task brief's explicit
four-op/HARD-invariant/ESCALATE shape.
(-) `ovenfurnacemfg.advisor` remains a `mock-advisor` (no real
LLM/langchain integration yet) -- consistent with every other actor in
this fleet's current maturity tier, not a regression.
(-) Still a simulation/proposal layer, not a real plant-operations
control system; no integration with real plant-management databases
(equipment telemetry, batch tracking, freight dispatch, certification-
body APIs) -- a standalone coordinator blueprint, matching every prior
sibling's own stated scope limit.
