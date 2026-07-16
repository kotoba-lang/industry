# ADR-2607151600: cloud-itonami ⟷ kami-engine-vehicle-designer real engineering-simulation integration (automotive pilot)

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (owner directive, this session)
**Supersedes**: ADR-2607083500 (automotive-vertical scope only — its "zero
dependency" rule for OTHER verticals is unaffected)
**Amends**: ADR-2606272230 (delivers the "real wiring is a later stage" work
that ADR deferred)
**Related**: ADR-2607142800 (cloud-itonami robotics-process-simulation,
symbolic layer), ADR-2607010930 (clj-wgsl migration, restored `physics-2d`),
ADR-2607031200 (`kami.webgpu.mesh` executor)

## Context

ADR-2607083500 designed `cloud-itonami.vehicle-design-link` as a pure
governance facade with an explicit, quoted invariant: **"cloud-itonami は
`kami-engine-vehicle-designer`/`brep`/`cnc` への依存を一切持たない"** — design
references are treated as opaque EDN payloads, and the ADR lists as
deliberately out of scope: "実際のfab/MES制御・実機ロボットモーションプランニング・
EDA/CAEシミュレーションはモデル化しない". ADR-2606272230 scoped
`kami-engine-vehicle-designer`'s own `vdesign.physics`/`vdesign.simverify` to
closed-form symbolic models only, noting "実体配線（シーンを実kami-genesis/
kami-camへ）は次段" (real wiring is a later stage).

The 2026-07-14 robotics-process-simulation work (ADR-2607142800) then gave
every cloud-itonami manufacturing actor a `<domain>.robotics` namespace —
but this is **symbolic only**: a deterministic pass/fail derived from static
ground-truth numeric fields (e.g. `:structural-deviation-actual` vs. min/
max), with zero 3D geometry, zero physics timestep, zero rendering. A
same-day repo survey confirmed this same "symbolic, not physically
simulated" shape is universal across this monorepo's design/CAE layer too
(`vdesign.physics`'s mass-convergence fixed-point iteration,
`vdesign.simverify`'s closed-form crash-energy safety factor) — genuinely
real 3D physics + rendering infrastructure DOES exist elsewhere in
`kotoba-lang` (`physics-2d`: a real, tested impulse-based rigid-body 2D
solver restored from the deleted Rust `kami-physics-2d` crate;
`kotoba-lang/webgpu`'s `kami.webgpu.mesh`: a real, working 536-line WebGPU
executor for arbitrary triangle meshes with skin/morph support, proven in
`network-isekai`/`kami-app-amenominaka`; `org-iso-10303`(brep)/`org-iso-6983`
(cnc) via `vdesign.cad`/`vdesign.process`: a real, honestly-scoped
BREP-tessellation + CAM toolpath/G-code pipeline) — it was simply never
wired to any cloud-itonami governance actor.

**Owner directive (2026-07-14, this session)**: extend the automotive pilot
(isic-2910 ⟷ kami-engine-vehicle-designer) to a REAL engineering simulation
— actual physics timestep, actual CAD/CAM geometry, actual robot motion
planning, actually visualized — and update the governance-only ADRs to
reflect this integration, rather than leaving the "no dependency,
opaque-payload-only" wall in place indefinitely.

## Decision

1. **Reuse only existing real components — do not invent a new physics
   engine, CAD kernel, or renderer, and do not write new Rust** (per this
   repo's runtime-priority rule: kotoba wasm → clojurewasm → ClojureScript →
   nbb, with JVM/bb and any NEW Rust crate explicitly off the table). The
   pilot composes:
   - **Geometry**: `vdesign.cad/envelope-solid` (BREP feature tree via
     `org-iso-10303`, already real, honestly scoped as a packaging
     envelope) → tessellated vertex/edge data.
   - **Manufacturing process**: `vdesign.process` (real `org-iso-6983` CAM —
     stock, tool library, toolpath generation, G-code post-processing — plus
     the existing BOM + 4D assembly-order sequencing, the "giemon-factory
     `construction.order.json :seq`" pattern already documented in that ns).
   - **Physics**: a NEW `vdesign.simphysics` namespace built directly on
     `kotoba-lang/physics-2d`'s real, tested `world-step` impulse solver (2
     tests ported 1:1 from the original Rust `kami-physics-2d`) — models the
     vehicle-dispatch end-of-line event as an actual time-stepped rigid-body
     simulation (the vehicle body + a fixture/barrier as `Body2D`/`Collider2D`
     entities, stepped via `world-step` over N ticks), replacing
     `vdesign.simverify`'s closed-form-only safety-factor derivation with an
     option to derive it from the ACTUAL simulated collision/settling
     trajectory. This is honestly a 2D projection (physics-2d has no 3D
     solver) — stated as a real, disclosed scoping limit, not hidden.
   - **Rendering**: a NEW `vdesign.scene` bridge translating
     `envelope-solid`'s tessellated mesh + `simphysics`'s per-tick body
     transforms into the vertex/index + per-frame transform format
     `kotoba-lang/webgpu`'s `kami.webgpu.mesh` executor already consumes —
     genuinely renders the simulated process in a browser via real WebGPU,
     not just computes numbers.
   - **Robot motion planning**: a NEW `vdesign.motionplan` namespace
     extending `vdesign.process`'s existing BOM + 4D assembly-order into an
     actual Cartesian-waypoint trajectory per assembly station (a minimal,
     honest waypoint list — not an inverse-kinematics solver or a real
     robot-controller driver).
2. **`cloud-itonami-isic-2910` takes a REAL dependency on
   `kami-engine-vehicle-designer`**, pinned by git SHA in `deps.edn` —
   superseding ADR-2607083500's "zero dependency" invariant FOR THE
   AUTOMOTIVE VERTICAL ONLY (that ADR's rule remains the default for every
   OTHER cloud-itonami vertical until/unless a similar ADR extends them).
3. **`automotive.robotics/simulate-assembly-line` is rewired to call the
   real engine** (`vdesign.simphysics`/`vdesign.scene`/`vdesign.motionplan`)
   instead of its current synthetic, deterministic `simulate-assembly-line`
   function. `:passed?` and the telemetry-proof now derive from the ACTUAL
   simulated physics outcome (e.g. a real settling-distance/deceleration
   reading from the stepped rigid-body trajectory), not a static
   ground-truth field comparison alone. `automotive.governor`'s independent-
   recheck discipline (never trust the mission's self-report) is PRESERVED
   — it now independently re-derives from the real engine's own trajectory
   output fields, the same invariant, a real input.
4. **Honest scope statement (replaces, does not merely restate,
   ADR-2607083500's "does not model" list)**: this integration DOES model —
   for the automotive vertical only — a real time-stepped rigid-body physics
   simulation, real (packaging-envelope-level) CAD/CAM geometry and
   toolpaths, a real assembly-station Cartesian waypoint plan, and a real
   WebGPU-rendered visualization of the simulated process. It still does
   NOT model: electromagnetic/electronic (EDA) simulation, a connection to
   any real physical PLC/MES/SCADA system, or a real robot controller/
   inverse-kinematics driver — those remain simulation-only, the same
   "policy, not control" boundary `kotoba.robotics`'s docstring already
   establishes for the symbolic layer, now extended to the physically-
   simulated layer rather than abandoned.

## Consequences

(+) The automotive vertical's "sim" claim is now genuinely backed by a
time-stepped physics engine, real geometry, and a real renderable scene —
not merely a symbolic pass/fail, closing the gap this session's owner
question identified.
(+) Zero new physics engines, CAD kernels, or Rust crates were written —
100% composed from existing, previously-unwired real components
(`physics-2d`, `kami.webgpu.mesh`, `org-iso-10303`/`org-iso-6983`).
(−) Scope is the automotive pilot only. The other 6 manufacturing actors
touched in ADR-2607142800 (isic-2610/2620/2394/2930/4741/0810/4211) remain
on the symbolic robotics-simulation layer until a similar real-engine
integration is built for each — explicitly follow-up work, not silently
implied as already done.
(−) The physics simulation is a 2D projection (physics-2d has no 3D
rigid-body solver) — disclosed, not hidden. A genuine 3D solver is future
work if the 2D projection proves insufficient for a given check.
(−) "Robot motion planning" is a waypoint LIST, not a full inverse-
kinematics/trajectory-optimization solver, and does not drive any real
robot controller — still simulation, not control.

## Verification

- See the accompanying implementation PRs/commits on
  `kami-engine-vehicle-designer` (`vdesign.simphysics`/`vdesign.scene`/
  `vdesign.motionplan`, tests green) and `cloud-itonami-isic-2910`
  (`automotive.robotics` rewired to the real engine, governor contract
  tests green, `clojure -M:dev:run` demo produces a real rendered scene
  artifact) for exact test counts and commit SHAs.
