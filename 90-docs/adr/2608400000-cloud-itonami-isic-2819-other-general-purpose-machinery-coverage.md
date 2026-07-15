# ADR-2608400000: cloud-itonami-isic-2819 (Manufacture of other general-purpose machinery) plant-operations-coordination actor -- fresh scaffold

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: `cloud-itonami-isic-2812` (Manufacture of fluid power
equipment -- the closest structural analog: also a back-office plant-
operations coordination actor for a fixed manufacturing plant with
QC-tested, discrete-unit finished-goods output and a real physical/
consumer safety dimension, mirrored module-for-module here and
adapted from machining/assembly/pressure-test-bench/pressure-test
vocabulary to assembly/calibration-test-bench/calibration-accuracy-
test vocabulary), `cloud-itonami-isic-2814` (Manufacture of bearings,
gears, gearing and driving elements -- 2812's own closest analog, same
two-entity verified/registered gate shape), `cloud-itonami-isic-2817`
(Manufacture of office machinery and equipment (except computers and
peripheral equipment) -- an already-`:implemented` sibling in the same
28xx "other special-purpose machinery" family), `kotoba-lang/industry`
registry's `"2819"` catalog entry (was `:maturity :spec` with a stale
`gftdcojp/cloud-itonami-C2819` placeholder repo reference that was
never created; now `:implemented`)

## Context

This is part of an ongoing careful, smaller-batch ISIC-coverage rollout
(one class per agent, a capable model, mandatory verification) after a
prior 18-agent haiku batch produced a 61% defect rate; 120+ consecutive
agents on this stricter protocol had all succeeded before this one.

Before any work, the registry entry's `:id`/`:name` pair was
independently re-verified against a fresh clone of `kotoba-lang/
industry` and a GitHub Contents-API fetch of `resources/kotoba/
industry/registry.edn` (not `raw.githubusercontent.com`, which can be
stale): `{:id "2819" :name "Manufacture of other general-purpose
machinery" ...}` confirmed verbatim -- this fleet has previously
mislabeled an assigned ISIC class more than once, so this check runs
before any design work, not after. `gh api
repos/cloud-itonami/cloud-itonami-isic-2819` returned 404 -- fresh
scaffold, no prior repository at either the registry's stale
`gftdcojp/cloud-itonami-C2819` placeholder or the real `cloud-itonami`
org.

ISIC 2819 is the RESIDUAL general-machinery class -- e.g. weighing/
packaging machinery, gas generators, fire-extinguisher equipment, and
other general-purpose machinery not elsewhere classified -- distinct
from siblings 2812 (fluid power equipment), 2814 (bearings/gears/
driving elements), 2815 (ovens/furnaces), and 2817 (office machinery),
several already `:implemented` in this fleet. Per the task brief, this
build picks ONE concrete illustrative product line, documented plainly
in the README: **industrial weighing/packaging-machinery
manufacturing** (platform scales, checkweighers, batching scales,
filling machines, wrapping machines, labeling machines).

## Decision: WeighPkgAdvisor ⊣ General-Purpose Machinery Plant Operations Governor, plant-operations coordination only

Implemented `cloud-itonami-isic-2819` end-to-end in `src/weighpkgmfg`
using the SAME `.cljc` actor pattern (langgraph-clj StateGraph,
mock-by-default advisor, single MemStore backend, 0->3 phase rollout)
`cloud-itonami-isic-2812` (Manufacture of fluid power equipment) uses,
mirrored module-for-module (`weighpkgmfg.*` in place of
`fluidpowermfg.*`) and adapted from hydraulic/pneumatic vocabulary to
weighing/packaging-machinery vocabulary: `:assembly-line` and
`:calibration-test-bench` equipment kinds in place of `:machining-
line` and `:pressure-test-bench`; `:platform-scale`/`:checkweigher`/
`:batching-scale`/`:filling-machine`/`:wrapping-machine`/`:labeling-
machine` product types in place of `:hydraulic-pump`/`:hydraulic-
cylinder`/`:hydraulic-valve`/`:hydraulic-motor`/`:pneumatic-cylinder`/
`:pneumatic-valve`; a `:calibration-accuracy-percent` plausibility
check (0-10%, informed by real legal-for-trade weighing-equipment
calibration practice) in place of `:pressure-test-bar` (0-2000 bar).

This actor is **strictly plant-operations coordination, not direct
assembly/calibration-line-equipment control authority**. It never
touches the assembly/calibration-test-bench equipment directly, and it
is never a legal-metrology or machinery-safety certification authority
(e.g. OIML R76 / NIST Handbook 44 legal-for-trade weighing-accuracy
certification, or EU Machinery Directive 2006/42/EC CE conformity /
ANSI PMMI B155.1 packaging-machinery safety marking) -- both are
permanent, un-overridable HARD governor blocks
(`weighpkgmfg.governor/equipment-actuate-blocked-violations` and
`certification-authority-blocked-violations`), not policy that could
be relaxed by a future rollout phase.

### Closed op-allowlist (4 ops, all `:effect :propose`)

- `:log-production-batch` -- assembly/calibration-test batch,
  output-quality data logging. Administrative, not an operational
  decision; the ONLY op eligible to auto-commit, and only at phase 3
  when governor-clean.
- `:schedule-maintenance` -- assembly/calibration-test-bench-equipment
  maintenance scheduling proposal. Never in any phase's `:auto` set,
  matching 2812's own `:schedule-maintenance` posture -- always human
  approval, even when clean.
- `:flag-safety-concern` -- surfaces an equipment-safety/quality-
  defect concern. ALWAYS escalates to a human, unconditionally, at
  every phase.
- `:coordinate-shipment` -- outbound product shipment coordination
  proposal. Never in any phase's `:auto` set; always escalates for
  human approval even when clean and within quantity.

### Governor -- twelve concrete checks elaborating four HARD invariants, all un-overridable by human approval

1. Request-level propose-only (`:effect` must be `:propose`)
2. Closed op allowlist (the four ops above only)
3. Closed proposal-effect allowlist (`:batch/upsert`/`:maintenance/
   schedule`/`:safety-concern/flag`/`:shipment/propose` only -- no
   direct assembly/calibration-line-equipment control effect)
4. Equipment-actuate blocked (`:actuate-equipment? true` on a
   `:schedule-maintenance` proposal, PERMANENT)
5. Certification-authority blocked (`:issue-certification? true` on
   ANY proposal, PERMANENT -- this actor never self-issues an OIML
   R76/NIST Handbook 44 legal-for-trade weighing-accuracy certification
   or a CE/ANSI-PMMI machinery-safety conformity mark)
6. Equipment not independently verified/registered before maintenance
   scheduling
7. Already-scheduled double-schedule guard (dedicated `:scheduled?`
   fact)
8. Batch not independently verified/registered before shipment
   coordination
9. Shipment quantity independently recomputed against the batch's own
   logged production quantity
10. Invalid product-type (closed 6-value set)
11. Invalid `:calibration-accuracy-percent` (0-10% plausibility bound)
12. Invalid `:defect-rate-percent` (0-100% plausibility bound)

Plus the SOFT confidence-floor/high-stakes escalation gate (low
confidence, or `:stake :coordination/safety-concern` -- ALWAYS set for
`:flag-safety-concern`).

### Domain adaptation: calibration-accuracy test in place of proof-pressure test

2812's routine QC field is `:pressure-test-bar` (hydraulic/pneumatic
proof-pressure measurement, 0-2000 bar). This vertical's routine QC
field is instead `:calibration-accuracy-percent` (percent deviation
from a certified reference weight/measure during assembly-line
calibration test), since weighing/packaging machinery is QC'd on
measurement accuracy rather than pressure withstand. The 0-10%
plausibility ceiling is informed by real legal-for-trade weighing-
equipment calibration practice: OIML R76 / NIST Handbook 44 accuracy
classes typically require well under 1% deviation at any given test
load, and even the most permissive commercial/industrial non-legal-
for-trade classes stay in the low single-digit percent range --
generously bounded well above any real routine calibration-test
reading, the same "ceiling above practice, not at it" discipline
2812's own 2000 bar pressure ceiling and every prior sibling's
plausibility bound establish.

### Two equipment kinds, mirroring 2812's two-kind pattern

`:assembly-line` (verified+registered, schedulable) and
`:calibration-test-bench` (unverified/unregistered, blocks scheduling)
-- mirroring 2812's `:machining-line`/`:pressure-test-bench` pair
exactly. The task brief names `:schedule-maintenance` as "machining/
assembly/test-bench-equipment maintenance scheduling" generically (not
a distinct third equipment entity), matching 2812's own treatment of
its scope description.

### No JVM-only interop anywhere in `src/`

Per this workspace's cljs-first `.cljc` runtime-priority rule (`kotoba
wasm` > `clojurewasm` > `ClojureScript` > `nbb`, JVM/`bb` downgraded to
a last resort), all source in `src/weighpkgmfg/` is `.cljc` with the
`#?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])` and
`#?(:clj Exception :cljs :default)` portable idioms only -- no
`java.*`/`System.*` references, and the actor graph is invoked
exclusively via `langgraph.graph/run*` (not `.invoke`, which is not
cljs-portable), matching 2812's own portability discipline exactly.

## What this actor does NOT do

Direct assembly/calibration-line-equipment control and legal-metrology/
machinery-safety certification issuance remain exclusive to the
licensed plant supervisor / accredited certification body, permanently
-- enforced structurally by the closed op-allowlist, the permanent
equipment-actuate block, and the permanent certification-authority
block, not just documented in the README.

## Verification

- `cloud-itonami-isic-2819`: `clojure -M:test` -- raw final line: `Ran
  70 tests containing 201 assertions.` / `0 failures, 0 errors.`
  Re-run green a second time against a fresh `git clone` after pushing
  to `main` (identical output).
- `clojure -M:lint` -- `linting took 705ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` (the `weighpkgmfg.sim` demo driver) exercises
  the full coordination episode (log-production-batch auto-commit,
  schedule-maintenance/flag-safety-concern/coordinate-shipment
  escalate-then-approve) plus every HARD-hold scenario
  (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, already-scheduled, invalid-product-type, invalid-
  calibration-accuracy-percent, invalid-defect-rate, certification-
  authority-blocked) and exits with no exceptions.
- All source is `.cljc`; no JVM-only interop (confirmed by manual
  review of every `#?(:clj ...)` branch -- none reference
  `java.*`/`System.*`).
- No mojibake found across source/doc files in the new repo.
- Repo scaffolded and tested from a uniquely-named scratch directory
  (`.../scratchpad/2819work/orgs/cloud-itonami/
  cloud-itonami-isic-2819`, outside the shared superproject checkout),
  then pushed to a fresh GitHub repo (`cloud-itonami/
  cloud-itonami-isic-2819`, created via `gh repo create`) as its
  initial `main` commit.
- This ADR itself was authored and committed from a sibling `git
  worktree` outside the superproject root, branched from a freshly
  fetched `origin/main`, landed via a server-side merge (`gh api
  repos/com-junkawasaki/root/merges`), per this workspace's
  concurrent-session worktree discipline.
- `kotoba-lang/industry` registry `"2819"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-C2819` placeholder
  to the real repo); `test/kotoba/industry_test.clj`'s
  maturity-summary assertion bumped to match the live-recomputed
  implemented-entry count (recomputed via `kotoba.industry/
  maturity-summary`, not `grep -c`, against a freshly re-fetched
  `origin/main`). Full `kotoba-lang/industry` suite re-run green
  post-edit and again post-merge against a fresh clone; see this
  session's final report for the raw post-merge output.
- The `:itonami.blueprint/governor` keyword,
  `:general-purpose-machinery-plant-operations-governor`, and the
  `weighpkgmfg` namespace were both grep-verified UNIQUE fleet-wide
  (`gh search code "general-purpose-machinery-plant-operations-
  governor" --owner cloud-itonami` and `gh search code "weighpkgmfg"
  --owner cloud-itonami`, zero hits before this repo was created).

## Consequences

(+) `cloud-itonami-isic-2819` now exists with a genuinely green,
independently re-verified test suite and a Governor that structurally
enforces its documented invariants (closed op-allowlist, `:effect
:propose`-only, equipment/batch-registration, permanent equipment-
actuate and certification-authority blocks) rather than only claiming
them in prose.
(+) `kotoba-lang/industry` registry `"2819"` entry promoted to
`:maturity :implemented`, with its stale placeholder repo reference
corrected.
(+) Demonstrates faithful module-for-module adaptation of a verified
sibling (2812) to a distinct manufacturing domain (calibration-
accuracy conformance rather than pressure withstand as the routine QC
gate), without inventing scope beyond the task brief's explicit
four-op/HARD-invariant/ESCALATE shape.
(-) `weighpkgmfg.advisor` remains a `mock-advisor` (no real
LLM/langchain integration yet) -- consistent with every other actor in
this fleet's current maturity tier, not a regression.
(-) Still a simulation/proposal layer, not a real plant-operations
control system; no integration with real plant-management databases
(equipment telemetry, batch tracking, freight dispatch, certification-
body APIs) -- a standalone coordinator blueprint, matching every prior
sibling's own stated scope limit.
