# ADR-2607160500: cloud-itonami isic-2720 battery/cell manufacturing → `:implemented`

- Status: Accepted (2026-07-15)
- Related: ADR-2607111600 (isic-2910 motor-vehicle promotion — sibling
  architecture this repo mirrors), ADR-2607160100 (isic-2630
  smartphone/communication-equipment, first downstream consumer),
  ADR-2607160200 (isic-2920 body-shop, second downstream consumer),
  ADR-2607151600 (real engineering-simulation integration, automotive
  pilot), ADR-2607152000 (real engineering-simulation fleet extension)

## Context

ISIC 2720 (manufacture of batteries and accumulators) sat as a
`:maturity :spec` placeholder with a dead `gftdcojp/cloud-itonami-C2720`
URL — no repo, no business model, no actor. This session already built
`cloud-itonami-isic-2630` (smartphone/communication-equipment assembly)
and `cloud-itonami-isic-2920` (motor-vehicle body/coachwork
manufacturing) as native real-physics actors from day one. A value-chain
review found that BOTH of those verticals, plus `cloud-itonami-isic-
2910` (motor-vehicle final assembly, whose `kami-engine-vehicle-
designer` pairing already models a BEV/FCEV powertrain's own
`:energy`/`:store-mass-kg` fields), share the exact same missing
upstream input: a safety-certified, crush-test-verified lithium-ion
battery cell-batch. No cloud-itonami actor produced one — a real gap
common to two value chains at once, not a niche single-vertical gap.

## Decision

1. **`cloud-itonami-isic-2720`** — Cell Advisor ⊣ Cell-Safety Governor
   (cell-batch intake, per-scheme battery-safety-certification rules
   verify, end-of-line internal-resistance/capacity-fade quality
   screen, robot UN 38.3 T6 mechanical crush-test mission, dual
   actuation: ship-cell-batch + issue-safety-certificate, audit
   export). Namespaces live under `cellworks.*` with the standard
   facts/registry/store/governor/phase/advisor/operation/sim/robotics/
   export shape, mirroring `cloud-itonami-isic-2910`/`cloud-itonami-
   isic-2920`'s own structure exactly (`langgraph-clj` StateGraph,
   `MemStore`/`DatomicStore` dual backend proven by a shared contract
   test, dedicated boolean double-actuation guards, honest facts
   catalog).
2. **Native real-physics simulation from day one**
   (ADR-2607151600/ADR-2607152000 pattern, delivered the same way
   `cloud-itonami-isic-2630`/`cloud-itonami-isic-2920` delivered it —
   as a new build, not a retrofit): `cellworks.robotics` runs an
   ACTUAL time-stepped `kotoba-lang/physics-2d` rigid-body simulation
   of UN 38.3 Test T6 ("Impact/Crush") — a press-platen `Body2D`
   closes at a controlled velocity onto a static cylindrical-cell
   `Body2D`, crushing perpendicular to the cell's longitudinal axis
   (literally matching the standard's own specified crush
   orientation), over a crush-travel distance that literally matches
   the standard's own 50%-deformation stopping criterion. A real
   `:sim-peak-crush-force-n` is read directly off the simulated
   collision trajectory (F = m·a) and independently rechecked against
   UN 38.3 T6's own real, cited 13 kN crush-force ceiling — a
   well-corroborated secondary citation (multiple independent
   test-lab/industry sources describe the current UN Manual of Tests
   and Criteria's crush-test stopping-force criterion as 13 kN ± 0.78
   kN), disclosed as such rather than treated as a verbatim
   primary-source quote.
3. **Battery safety-certification evidence catalog** (`cellworks.
   facts`) seeds four real, current, cited schemes — UN 38.3 (global
   transport, mandatory for ALL lithium cells/batteries), IEC 62133-2
   (international baseline, e.g. adopted by the EU as EN 62133-2),
   UL 2054 (US pack-level standard) and GB 31241 (China's mandatory
   national standard, itself including its own crush/nail-penetration
   test suite). This repo does NOT fabricate a distinct GB 31241
   numeric crush-force/displacement threshold of its own — only UN
   38.3's 13 kN figure is used as the physics-derived tolerance
   ceiling, honestly disclosed as the only one this session could
   confidently source.
4. Entity is a **cell-batch** (a manufactured lot of battery cells, or
   a pack-batch). `cell-batch-resistance-out-of-range?` continues the
   fleet's two-sided range-check family, applied to a cell-batch's own
   measured end-of-line internal-resistance deviation against its own
   recorded acceptance-band bounds — a real electrical end-of-line QA
   metric, independent of the physics-derived crush-force check.
5. Dual actuation on the same entity, each with its own dedicated
   boolean guard (`:cell-batch-shipped?`/`:safety-certified?`, never a
   status lifecycle — ADR-2607071320/6492 lesson):
   `:actuation/ship-cell-batch` (the real upstream hand-off to BOTH
   `cloud-itonami-isic-2630`'s device-unit assembly and `cloud-
   itonami-isic-2910`/`cloud-itonami-isic-2920`'s vehicle/body
   assembly) and `:actuation/issue-safety-certificate` (a UN
   38.3/IEC 62133-style Battery Safety Test Report).
6. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2720` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2720` placeholder.
7. **Real visual render proof**: the crush-test's actual simulated
   trajectory (press-platen + cylindrical-cell rigid-body positions
   per tick) was rendered via `kami.webgpu.mesh` (fetched fresh from
   `kotoba-lang/webgpu`'s GitHub `main`) as two real frames (pre-
   contact / settled-impact), driven headlessly via Playwright — see
   Verification for backend results and screenshot paths.

## Consequences

(+) The battery cell/pack manufacturing stage gains a forkable OSS
operating stack with auditable governor holds, closing a gap common to
BOTH the smartphone-assembly and vehicle-assembly value chains at
once — a genuinely unifying upstream vertical, not a redundant one.
(+) The tolerance ceiling is anchored on a real, standard-specified
numeric value (UN 38.3 T6's 13 kN) rather than a newly-defined proxy
multiple, an improvement in citation confidence over some prior
real-physics siblings' own disclosed-analog thresholds.
(+) Genuine dual-downstream hand-off value: the same cell-batch-
shipment/safety-certificate shape serves both `cloud-itonami-
isic-2630` and `cloud-itonami-isic-2910`/`cloud-itonami-isic-2920`
without this actor needing to know which downstream consumer a given
shipment goes to.
(−) `physics-2d` is a 2D projection with no material-stiffness/
deformation model; the modeled cell's real cylindrical cross-section
is approximated as an AABB bounding box (a disclosed simplification —
`physics-2d`'s narrowphase has no mixed AABB/circle collision support).
The simulation's closing velocity is a disclosed analog rate, not UN
38.3's own literal (much slower) controlled crush-test speed — see
`cellworks.robotics`'s own docstring for the full disclosure.
(−) Battery safety-certification-scheme coverage is a starting catalog
(4 schemes), not exhaustive, and does not capture every jurisdiction's
own supplementary requirements or cell-format-specific exemptions.
(−) No chemistry/thermal-runaway/venting simulation — `physics-2d` has
no such model at all; the crush-test check is a mechanical-force
reading only, the same "policy, not control" boundary every real-
physics sibling in this fleet discloses.

## Verification

- `cloud-itonami-isic-2720`: `clojure -M:dev:test` green (45 tests /
  230 assertions), `clojure -M:lint` clean, `clojure -M:dev:run`
  completes with no exceptions and exercises every HARD hold
  (no-spec-basis, evidence-incomplete, robotics-simulation-missing,
  robotics-simulation-out-of-tolerance, cell-batch-resistance-out-of-
  range, end-of-line-defect-unresolved, already-shipped,
  already-certified).
- Real physics: batch-1 (80kg platen) → 7619.05 N peak crush force,
  clears the 13,000 N UN 38.3 T6 ceiling; batch-5 (300kg platen,
  deliberately oversized press-run configuration) → 28,571.43 N,
  exceeds the ceiling — caught on the governor's independent recheck
  even though `:robotics-sim-verified?` was seeded `true` ("already on
  file").
- Industry: maturity `"2720"` → `:implemented`
- GitHub public repo under `cloud-itonami/cloud-itonami-isic-2720`
