# ADR-2613200000: cloud-itonami ISIC 2396 (Cutting, shaping and finishing of stone) actor coverage

## Status

Accepted. `cloud-itonami-isic-2396` scaffolded fresh (no prior repo
existed — confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-2396`
404 and `gh api repos/gftdcojp/cloud-itonami-C2396` 404 before this
work began) and landed at
`https://github.com/cloud-itonami/cloud-itonami-isic-2396`, following
the verified fresh-scaffold protocol established across this fleet
(most recently `cloud-itonami-isic-2393`, porcelain/ceramic products).
This is the final actor of Wave 3 — after this batch, only the two
deliberately-scoped-out sensitive classes (2520 weapons/ammunition,
3040 military fighting vehicles) remain unimplemented in the entire
wave.

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carries `{:id "2396", :name "Cutting, shaping and finishing of
stone", ...}` at `:maturity :spec` with a stale placeholder
`:repo`/`:business-id` (`https://github.com/gftdcojp/cloud-itonami-C2396`
/ `cloud-itonami-C2396`) predating the current `cloud-itonami-isic-<id>`
naming convention. This work implements the blueprint as a real,
tested `cloud-itonami-isic-<id>` actor repo and corrects the registry
entry's `:repo`/`:business-id` fields to match.

ISIC 2396 covers **dimension-stone fabrication**: cutting, shaping and
finishing of stone — a downstream FABRICATION process from stone
quarrying/extraction itself (ISIC 0810, out of this actor's scope).
The plant takes in quarried blocks and saws (gang-saw/bridge-saw/
wire-saw), polishes, edge-profiles/CNC-shapes and inspects them,
producing granite slabs, marble slabs, limestone slabs, sandstone
slabs, monuments/headstones, countertops, dimension-stone tile and
cut-stone veneer for construction, monumental and decorative use. The
closest sibling already in this fleet is `cloud-itonami-isic-2393`
(Manufacture of other porcelain and ceramic products) — both are
back-office coordination actors for a fixed processing PLANT with
heavy cutting/finishing-line equipment and a real physical safety
dimension, sharing the same four-op shape and the same two-entity
verified/registered gate structure, but with distinct hazard profiles:
2393's is kiln-firing thermal/burn hazard plus glaze-material-safety
(lead/cadmium leaching), while 2396's is crystalline-silica-dust
exposure (respirable crystalline silica at sawing/polishing —
silicosis is a well-documented, serious occupational-health concern in
dimension-stone fabrication) plus saw-blade/wire-saw kickback and
heavy-slab handling/crush hazard.

The full architecture, module set, governor rules, tests and
verification runs are documented in the actor repo's own
`docs/adr/0001-architecture.md`
(https://github.com/cloud-itonami/cloud-itonami-isic-2396/blob/main/docs/adr/0001-architecture.md).
This superproject ADR records the coverage decision and the exact
commands/outputs used to verify it landed cleanly, per this
superproject's own verification-discipline conventions.

## Decision

Scaffold `cloud-itonami-isic-2396` mirroring the `cloud-itonami-isic-2393`
(porcelain/ceramic products) architecture closely — same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/
`:coordinate-shipment`, all `:effect :propose` only), same langgraph-clj
StateGraph + independent Governor + Phase 0->3 rollout pattern, same
two-entity (equipment + batch) independently-verified/registered gate
structure — but retargeted to the dimension-stone plant's own hazard
profile and quality vocabulary:

- Namespace prefix `stonemfg` (grep-verified UNIQUE fleet-wide:
  `gh search code "stonemfg" --owner cloud-itonami`, zero hits before
  this repo was created).
- Governor keyword `:dimension-stone-plant-operations-governor`
  (grep-verified UNIQUE fleet-wide: `gh search code
  "dimension-stone-plant-operations-governor" --owner cloud-itonami`,
  zero hits before this repo was created).
- Product-type closed set: `:granite-slab`/`:marble-slab`/
  `:limestone-slab`/`:sandstone-slab`/`:monument`/`:countertop`/
  `:dimension-stone-tile`/`:cut-stone-veneer`.
- Quality-plausibility fields replacing 2393's `:glaze-defect-rate-
  percent`/`:chip-resistance-newtons` pair: `:slab-thickness-
  tolerance-mm` (-20.0 to 20.0, deviation from nominal slab thickness)
  and `:polish-gloss-level` (0-100 gloss units, a 60-degree glossmeter
  reading) — both independently re-verified by the governor against
  physically plausible ranges, never taken on the advisor's
  self-report (never let a fabricated or gauge-error reading through).
- Permanent equipment-actuation block guards a sawing line/polishing
  line (`:actuate-sawing-polishing-line?`), matching 2393's own
  permanent actuation-block pattern but retargeted to this vertical's
  own equipment (bridge saw, gang saw, wire saw, polishing line)
  rather than 2393's forming-line/kiln-line.
- Safety-concern flagging covers crystalline-silica-dust exposure,
  saw-blade/wire-saw kickback and pinch-point hazard, and heavy-slab
  handling/crush hazard; ALWAYS escalates, matching every sibling
  actor's own safety-concern posture.

Full rule-by-rule detail (eleven concrete governor checks elaborating
four HARD invariants) lives in the actor repo's own
`docs/adr/0001-architecture.md` and `src/stonemfg/governor.cljc`
docstring — not duplicated here.

## Verification

Actor repo, from
`/tmp/isic-2396-scratch/orgs/cloud-itonami/cloud-itonami-isic-2396`
(a scratch dir mirroring the workspace's `orgs/cloud-itonami/<repo>`
layout, sibling to freshly-cloned `orgs/kotoba-lang/{langgraph,langchain}`,
so `deps.edn`'s `:local/root` paths resolve exactly as they would in
the monorepo checkout):

```
$ clojure -M:test
Ran 75 tests containing 210 assertions.
0 failures, 0 errors.

$ clojure -M:lint
linting took 497ms, errors: 0, warnings: 0

$ clojure -M:dev:run
(full demo narrative: happy-path commit/escalate/approve for all four
ops, then all ten HARD-hold scenarios exercised directly -- not-
propose-effect, unknown-op, equipment-not-verified, batch-not-
verified, shipment-weight-exceeded, sawing-polishing-line-actuate-
blocked, already-scheduled, invalid-product-type, invalid-slab-
thickness-tolerance, invalid-polish-gloss-level -- no exceptions)
```

Pushed to `main` at commit `e94193355a07ca6f1e626c5f3e5a8025b905b348`
(`cloud-itonami/cloud-itonami-isic-2396`).
Re-verified from an INDEPENDENT fresh clone
(`/tmp/isic-2396-verify2/orgs/cloud-itonami/cloud-itonami-isic-2396`,
same sibling layout):

```
$ clojure -M:test
Ran 75 tests containing 210 assertions.
0 failures, 0 errors.
```

`kotoba-lang/industry` registry: `"2396"` entry promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` corrected from the stale
`gftdcojp/cloud-itonami-C2396` placeholder to
`https://github.com/cloud-itonami/cloud-itonami-isic-2396` /
`cloud-itonami-isic-2396`, exact in-place edit of only the `"2396"`
entry's block (no other entry touched). `test/kotoba/industry_test.clj`
maturity-count assertion bumped to the freshly recomputed true
`:implemented` count (via `kotoba.industry/maturity-summary`, not
`grep -c`) at edit time — see the registry repo's own commit/PR for
the exact before/after counts and `clojure -M:test` output landed via
`gh api repos/kotoba-lang/industry/merges` (or Contents-API fallback).

## Consequences

(+) ISIC 2396 now has a documented, governed, auditable plant-
operations-coordination actor, following this fleet's established
pattern, cleanly scoped downstream of stone quarrying/extraction
itself (ISIC 0810, out of scope) and distinct from its closest
ceramics/refractory-adjacent sibling (2393).

(+) The "coordination, not control" boundary is a hard, permanent,
unconditional governor block plus a structural phase-table absence for
`:schedule-maintenance` -- two independent layers agree, not just
asserted in prose.

(+) Safety-concern escalation (crystalline-silica-dust exposure,
saw-blade/wire-saw kickback, heavy-slab crush hazard) is a core design
invariant; the actor structurally cannot auto-decide a safety concern
at any confidence or phase.

(+) The registry's stale pre-`cloud-itonami-isic-<id>`-convention
placeholder repo/business-id for this entry is corrected as part of
this promotion.

(+) Portable `.cljc`, zero JVM-only constructs, clj-kondo 0 errors / 0
warnings.

(-) Still a simulation/proposal layer -- single `MemStore` backend,
mock advisor, no real plant-management database integration. Same
limitation as every sibling actor in this fleet at this stage.

(-) With this batch, Wave 3 of the cloud-itonami ISIC coverage rollout
is complete except for the two deliberately-scoped-out sensitive
classes (2520 weapons/ammunition, 3040 military fighting vehicles),
which remain unimplemented by design.
