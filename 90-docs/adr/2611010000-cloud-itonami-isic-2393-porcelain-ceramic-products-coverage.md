# ADR-2611010000: cloud-itonami ISIC 2393 (Manufacture of other porcelain and ceramic products) actor coverage

## Status

Accepted. `cloud-itonami-isic-2393` scaffolded fresh (no prior repo
existed — confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-2393`
404 and `gh api repos/gftdcojp/cloud-itonami-C2393` 404 before this
work began) and landed at
`https://github.com/cloud-itonami/cloud-itonami-isic-2393`, following
the verified fresh-scaffold protocol established across this fleet
(most recently `cloud-itonami-isic-2391`, refractory products).

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carries `{:id "2393", :name "Manufacture of other porcelain and
ceramic products", ...}` at `:maturity :spec` with a stale placeholder
`:repo`/`:business-id` (`https://github.com/gftdcojp/cloud-itonami-C2393`
/ `cloud-itonami-C2393`) predating the current `cloud-itonami-isic-<id>`
naming convention. This work implements the blueprint as a real,
tested `cloud-itonami-isic-<id>` actor repo and corrects the registry
entry's `:repo`/`:business-id` fields to match.

ISIC 2393 covers porcelain/ceramic **tableware, decorative ceramics,
and technical/industrial ceramics** — raw-material (kaolin, feldspar,
quartz, ball clay) body preparation, forming (jiggering/jolleying, RAM
pressing, slip casting), glazing, kiln-firing (bisque + glost firing),
and inspection. This is distinct from the two closest sibling
verticals already in this fleet: `cloud-itonami-isic-2391`
(Manufacture of refractory products — fire brick/kiln lining for
furnace/kiln service, a hotter, higher-temperature-service kiln-firing
line with no glaze chemistry) and `cloud-itonami-isic-2392`
(Manufacture of clay building materials — construction brick/tile).
2391's own README and ADR-0001 explicitly flag this three-way scope
boundary and record that 2391 "does not depend on or wrap" 2393 (or
vice versa).

The full architecture, module set, governor rules, tests and
verification runs are documented in the actor repo's own
`docs/adr/0001-architecture.md`
(https://github.com/cloud-itonami/cloud-itonami-isic-2393/blob/main/docs/adr/0001-architecture.md).
This superproject ADR records the coverage decision and the exact
commands/outputs used to verify it landed cleanly, per this
superproject's own verification-discipline conventions.

## Decision

Scaffold `cloud-itonami-isic-2393` mirroring the `cloud-itonami-isic-2391`
(refractory products) architecture closely — same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/
`:coordinate-shipment`, all `:effect :propose` only), same langgraph-clj
StateGraph + independent Governor + Phase 0->3 rollout pattern, same
two-entity (equipment + batch) independently-verified/registered gate
structure — but retargeted to the porcelain/ceramic-products plant's
own hazard profile and quality vocabulary:

- Namespace prefix `porcelainmfg` (grep-verified UNIQUE fleet-wide:
  `gh search code "porcelainmfg" --owner cloud-itonami`, zero hits
  before this repo was created).
- Governor keyword `:porcelain-ceramic-plant-operations-governor`
  (grep-verified UNIQUE fleet-wide: `gh search code
  "porcelain-ceramic-plant-operations-governor" --owner cloud-itonami`,
  zero hits before this repo was created).
- Product-type closed set: `:porcelain-tableware`/`:bone-china-tableware`/
  `:stoneware-tableware`/`:earthenware-tableware`/`:decorative-ceramic`/
  `:art-pottery`/`:technical-ceramic`/`:ceramic-insulator`.
- Quality-plausibility fields replacing 2391's `:thermal-shock-cycles`/
  `:cold-crushing-strength-mpa` pair: `:glaze-defect-rate-percent`
  (0–100, percent of a batch with a visible glaze defect) and
  `:chip-resistance-newtons` (0–500, an edge chip-resistance test
  reading) — both independently re-verified by the governor against
  physically plausible ranges, never taken on the advisor's
  self-report (never let a fabricated or gauge-error reading through).
- Permanent equipment-actuation block guards a forming line/kiln line
  (`:actuate-forming-kiln-line?`), matching 2391's own permanent
  actuation-block pattern but retargeted to this vertical's own
  equipment (jiggering/jolleying machine, RAM press, tunnel kiln)
  rather than 2391's pressing-line press/kiln line.
- Safety-concern flagging covers kiln-safety/thermal-hazard, glaze-
  material-safety (lead/cadmium leaching risk — lead-free glaze
  compliance), and silica-dust hazard; ALWAYS escalates, matching
  every sibling actor's own safety-concern posture.

Full rule-by-rule detail (eleven concrete governor checks elaborating
four HARD invariants) lives in the actor repo's own
`docs/adr/0001-architecture.md` and `src/porcelainmfg/governor.cljc`
docstring — not duplicated here.

## Verification

Actor repo, from `/tmp/isic-2393-work/verify/orgs/cloud-itonami/cloud-itonami-isic-2393`
(a scratch dir mirroring the workspace's `orgs/cloud-itonami/<repo>`
layout, sibling to symlinked `orgs/kotoba-lang/{langgraph,langchain}`,
so `deps.edn`'s `:local/root` paths resolve exactly as they would in
the monorepo checkout):

```
$ clojure -M:test
Ran 76 tests containing 209 assertions.
0 failures, 0 errors.

$ clojure -M:lint
linting took 566ms, errors: 0, warnings: 0

$ clojure -M:dev:run
(full demo narrative: happy-path commit/escalate/approve for all four
ops, then all nine HARD-hold scenarios exercised directly -- not-
propose-effect, unknown-op, equipment-not-verified, batch-not-
verified, shipment-weight-exceeded, forming-kiln-line-actuate-blocked,
already-scheduled, invalid-product-type, invalid-glaze-defect-rate,
invalid-chip-resistance -- no exceptions)
```

Pushed to `main` at commit `b826d0e0b9101f11793fee0efa4440a12db04910`.
Re-verified from an INDEPENDENT fresh clone
(`/tmp/isic-2393-work/reverify1/orgs/cloud-itonami/cloud-itonami-isic-2393`,
same sibling-symlink layout):

```
$ git log --oneline -1
b826d0e feat: scaffold cloud-itonami-isic-2393 (Manufacture of other porcelain and ceramic products)

$ clojure -M:test
Ran 76 tests containing 209 assertions.
0 failures, 0 errors.
```

`kotoba-lang/industry` registry: `"2393"` entry promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` corrected from the stale
`gftdcojp/cloud-itonami-C2393` placeholder to
`https://github.com/cloud-itonami/cloud-itonami-isic-2393` /
`cloud-itonami-isic-2393`, exact in-place edit of only the `"2393"`
entry's block (no other entry touched). `test/kotoba/industry_test.clj`
maturity-count assertion bumped to the freshly recomputed true
`:implemented` count (via `kotoba.industry/maturity-summary`, not
`grep -c`) at edit time — see the registry repo's own commit/PR for
the exact before/after counts and `clojure -M:test` output landed via
`gh api repos/kotoba-lang/industry/merges`.

## Consequences

(+) ISIC 2393 now has a documented, governed, auditable plant-
operations-coordination actor, following this fleet's established
pattern, cleanly scoped apart from its two closest ceramics/refractory
siblings (2391, 2392).

(+) The registry's stale pre-`cloud-itonami-isic-<id>`-convention
placeholder repo/business-id for this entry is corrected as part of
this promotion.

(-) Still a simulation/proposal layer (single `MemStore` backend, mock
advisor) — no real plant-management system integration. Same
limitation as every sibling actor in this fleet at this stage.
