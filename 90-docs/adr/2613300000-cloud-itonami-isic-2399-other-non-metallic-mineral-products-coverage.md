# ADR-2613300000: cloud-itonami ISIC 2399 (Manufacture of other non-metallic mineral products n.e.c.) actor coverage

## Status

Accepted. `cloud-itonami-isic-2399` scaffolded fresh (no prior repo
existed — confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-2399`
404 before this work began) and landed at
`https://github.com/cloud-itonami/cloud-itonami-isic-2399`, following
the verified fresh-scaffold protocol established across this fleet
(most recently `cloud-itonami-isic-2391`, refractory products, the
architectural template mirrored here). This is the final actor
scaffolded in Wave 3 of this careful, smaller-batch rollout — after
this, only the two deliberately-scoped-out sensitive classes (2520
weapons/ammunition, 3040 military fighting vehicles) remain
unimplemented in the entire wave.

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carries `{:id "2399", :name "Manufacture of other non-metallic mineral
products n.e.c.", ...}` at `:maturity :spec` with a stale placeholder
`:repo`/`:business-id` (`https://github.com/gftdcojp/cloud-itonami-C2399`
/ `cloud-itonami-C2399`) predating the current `cloud-itonami-isic-<id>`
naming convention. This work implements the blueprint as a real,
tested `cloud-itonami-isic-<id>` actor repo and corrects the registry
entry's `:repo`/`:business-id` fields to match.

ISIC 2399 is a **residual ("not elsewhere classified") category** —
covering non-metallic mineral products with no dedicated ISIC class of
their own: mineral wool/insulation products, abrasive products,
asbestos products, mica products and similar goods. Rather than
attempt to model this whole residual category abstractly, this build
picks ONE concrete, illustrative product line: a **mineral wool / rock
wool thermal-and-acoustic insulation plant** (raw-material charge →
cupola-melter melting → fiberizing → binder application → curing-oven
cure → cutting/forming line).

The closest architectural sibling already in this fleet is
`cloud-itonami-isic-2391` (Manufacture of refractory products): both
are back-office coordination actors for a fixed processing plant with
heavy process-line equipment and a real physical safety dimension,
sharing the same four-op shape and two-entity (equipment + batch)
verified/registered gate structure. `cloud-itonami-isic-2391`'s own
README and ADR-0001 note this vertical is distinct from
`cloud-itonami-isic-2393` (Manufacture of other porcelain and ceramic
products, a separate ceramics vertical) — this build is likewise
distinct from both: mineral wool insulation is neither pressed/
kiln-fired ceramic ware nor a refractory lining material, but a
fibre-forming + binder-cure process with its own hazard profile
(respirable mineral-fibre exposure, binder off-gassing/VOC exposure,
forming/curing-line-equipment pinch-point/thermal hazard).

The full architecture, module set, governor rules, tests and
verification runs are documented in the actor repo's own
`docs/adr/0001-architecture.md`
(https://github.com/cloud-itonami/cloud-itonami-isic-2399/blob/main/docs/adr/0001-architecture.md).
This superproject ADR records the coverage decision and the exact
commands/outputs used to verify it landed cleanly, per this
superproject's own verification-discipline conventions.

## Decision

Scaffold `cloud-itonami-isic-2399` mirroring the `cloud-itonami-isic-2391`
(refractory products) architecture closely — same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/
`:coordinate-shipment`, all `:effect :propose` only), same langgraph-clj
StateGraph + independent Governor + Phase 0->3 rollout pattern, same
two-entity (equipment + batch) independently-verified/registered gate
structure — but retargeted to the mineral-wool-insulation plant's own
hazard profile and quality vocabulary:

- Namespace prefix `mineralwoolmfg` (grep-verified UNIQUE fleet-wide:
  `gh search code "mineralwoolmfg" --owner cloud-itonami`, zero hits
  before this repo was created).
- Governor keyword `:mineral-wool-plant-operations-governor`
  (grep-verified UNIQUE fleet-wide: `gh search code
  "mineral-wool-plant-operations-governor" --owner cloud-itonami`,
  zero hits before this repo was created).
- Product-type closed set: `:batt-insulation`/`:roll-insulation`/
  `:loose-fill-insulation`/`:rigid-board-insulation`/
  `:pipe-section-insulation`/`:duct-wrap-insulation`/`:acoustic-panel`/
  `:fire-stop-wrap`.
- Quality-plausibility fields replacing 2391's `:thermal-shock-cycles`/
  `:cold-crushing-strength-mpa` pair: `:thermal-conductivity-w-mk`
  (0.020–0.060 W/(m·K), lambda/λ value per e.g. ASTM C518/ISO 8301) and
  `:density-kg-m3` (8–250 kg/m^3) — both independently re-verified by
  the governor against physically plausible ranges, never taken on the
  advisor's self-report (never let a fabricated or gauge-error reading
  through).
- Permanent equipment-actuation block guards the forming/curing line
  (`:actuate-forming-curing-line?`), matching 2391's own permanent
  actuation-block pattern but retargeted to this vertical's own
  equipment (cupola melter, fiberizing spinner, binder applicator,
  curing oven, cutting line) rather than 2391's pressing-line
  press/kiln line.
- Safety-concern flagging covers respirable mineral-fibre exposure
  (man-made vitreous fibre dust at fiberizing/cutting/packaging),
  binder off-gassing/VOC hazard, and forming/curing-line-equipment
  hazard; ALWAYS escalates, matching every sibling actor's own
  safety-concern posture.

Full rule-by-rule detail (eleven concrete governor checks elaborating
four HARD invariants) lives in the actor repo's own
`docs/adr/0001-architecture.md` and `src/mineralwoolmfg/governor.cljc`
docstring — not duplicated here.

## Verification

Actor repo, built from a uniquely-named scratch dir
(`/private/tmp/.../scratchpad/isic-2399-work/build-2399/cloud-itonami/cloud-itonami-isic-2399`,
sibling to cloned `kotoba-lang/{langgraph,langchain}` under
`build-2399/kotoba-lang/`, mirroring the workspace's `orgs/<org>/<repo>`
relative-path layout so `deps.edn`'s `:local/root` paths resolve
exactly as they would in the monorepo checkout):

```
$ clojure -M:test
Ran 76 tests containing 209 assertions.
0 failures, 0 errors.

$ clojure -M:lint
linting took 604ms, errors: 0, warnings: 0

$ clojure -M:dev:run
(full demo narrative: happy-path commit/escalate/approve for all four
ops, then all ten HARD-hold scenarios exercised directly -- not-
propose-effect, unknown-op, equipment-not-verified, batch-not-
verified, shipment-weight-exceeded, forming-curing-line-actuate-
blocked, already-scheduled, invalid-product-type, invalid-thermal-
conductivity, invalid-density -- no exceptions)
```

Pushed to `main` at commit `e6e25d07e57f1519b2139e36b500a8c7488e36a8`.
Re-verified from an INDEPENDENT fresh clone; see the task's final
report for the exact `git log`/`clojure -M:test` re-verification
output.

`kotoba-lang/industry` registry: `"2399"` entry promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` corrected from the stale
`gftdcojp/cloud-itonami-C2399` placeholder to
`https://github.com/cloud-itonami/cloud-itonami-isic-2399` /
`cloud-itonami-isic-2399`, exact in-place edit of only the `"2399"`
entry's block (no other entry touched). `test/kotoba/industry_test.clj`
maturity-count assertion bumped to the freshly recomputed true
`:implemented` count (via `kotoba.industry/maturity-summary`, not
`grep -c`) at edit time — see the registry repo's own commit/PR for
the exact before/after counts and `clojure -M:test` output landed via
`gh api repos/kotoba-lang/industry/merges`.

## Consequences

(+) ISIC 2399 now has a documented, governed, auditable plant-
operations-coordination actor, following this fleet's established
pattern, cleanly scoped apart from its closest ceramics/refractory
siblings (2391, 2393) already in this fleet, and clearly documenting
its own residual-category / single-illustrative-product-line
methodology for future forks targeting a different ISIC-2399 product
family (abrasives, asbestos products, mica products).

(+) The registry's stale pre-`cloud-itonami-isic-<id>`-convention
placeholder repo/business-id for this entry is corrected as part of
this promotion.

(+) This closes out Wave 3 of the fleet rollout — only 2520
(weapons/ammunition) and 3040 (military fighting vehicles) remain
unimplemented, both deliberately scoped out as sensitive classes.

(-) Still a simulation/proposal layer (single `MemStore` backend, mock
advisor) — no real plant-management system integration. Same
limitation as every sibling actor in this fleet at this stage.

(-) One illustrative product line (mineral wool insulation) does not
cover the full breadth of the ISIC 2399 residual category (abrasive
products, asbestos products, mica products remain unmodeled) — see
the actor repo's own ADR-0001 Consequences for the explicit note on
this scope choice.
