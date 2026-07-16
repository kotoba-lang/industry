# ADR-2607152000: real engineering-simulation integration — fleet extension (fab/quarry/cement/parts/construction/retail)

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (owner directive, this session — "next")
**Extends**: ADR-2607151600 (automotive pilot — this ADR generalizes that
pilot's pattern to the other 6 manufacturing-stage actors touched by
ADR-2607142800, per that ADR's own explicit follow-up-work note)
**Related**: ADR-2607142800 (symbolic robotics-simulation layer, being
upgraded here to real-physics for these 6 verticals), ADR-2607010930
(restored `kotoba-lang/physics-2d`)

## Context

ADR-2607151600 upgraded ONE cloud-itonami vertical (isic-2910, automotive)
from a symbolic pass/fail robotics-simulation (ADR-2607142800) to a
genuinely time-stepped, real-physics-backed one, by composing existing real
components: `kotoba-lang/physics-2d`'s tested rigid-body impulse solver
(moving body collides with a static body over N real ticks, deceleration/
force/displacement read directly off the simulated trajectory, not
invented), `kami-engine-vehicle-designer`'s real BREP/CAM pipeline, and
`kotoba-lang/webgpu`'s real `kami.webgpu.mesh` renderer. That ADR explicitly
scoped itself to the automotive pilot and flagged the other 6 actors
(isic-2610 fab, isic-0810 quarryops, isic-2394 cement, isic-2930 auto-parts,
isic-4211 construction, isic-4741 computer-retail) as follow-up work.

Owner directive: continue ("next") — extend the same real-physics pattern
to these 6 remaining actors.

**Key simplification versus the automotive pilot**: automotive routed
through a SEPARATE design-library sibling repo
(`kami-engine-vehicle-designer`) because that pairing (cloud-itonami actor +
kami-engine-* design repo) already existed via `vehicle-design-link`
(ADR-2607083500). None of the other 6 verticals has an equivalent design-
repo sibling. So for these 6, the real-physics module is built DIRECTLY
inside each actor's own `<domain>.robotics` namespace, taking a real
git-coordinate dependency on `kotoba-lang/physics-2d` alone (pinned SHA
`03b6c23c8d9aa36b92a8130fd3e37fc2548b4d26` at time of writing) — no new
sibling repo needed, no BREP/CAM/webgpu-scene bridge (those were specific
to automotive's existing design-review tooling; the other verticals' honest
scope is the physics timestep itself, not a rendered scene).

## Decision

All 6 verticals reuse ONE shared real-physics shape — "a controlled-
velocity rigid body (or a gravity-driven falling body) meets a resisting/
static rigid body in a real `physics-2d` `world-step` loop; a real,
non-fabricated peak force/deceleration/displacement/settling-distance is
read directly off the simulated trajectory; the governor independently
rechecks that reading against a real, honestly-sourced domain tolerance" —
mapped to each vertical's actual real-world test procedure:

1. **isic-2610 (fab)** — wafer-probe / wire-bond pull-test: a probe/anchor
   rigid body separates from the bond-pad body at controlled velocity;
   real peak separation force read off the simulated impulse at breakaway.
2. **isic-0810 (quarryops)** — bench-face loose-block stability check: a
   fragment rigid body free-falls (real gravity integration) and settles
   against the bench floor; real settling displacement/impact energy read
   off the trajectory.
3. **isic-2394 (cement)** — 28-day compressive-strength test: a press-
   platen rigid body closes at controlled velocity onto a cube-specimen
   rigid body; real peak compressive force at the simulated collision.
4. **isic-2930 (auto-parts)** — weld-joint/fastener proof-load pull test:
   same controlled-separation shape as fab's wire-bond test, applied to a
   welded/fastened joint.
5. **isic-4211 (construction)** — concrete test-cylinder compressive-
   strength press: same press-platen-vs-specimen shape as cement (the same
   real-world test procedure, materially analogous).
6. **isic-4741 (computer retail)** — trade-in-unit functional drop/shock
   test: a device rigid body free-falls from a standard test height (real
   gravity integration) and impacts the test surface; real peak impact
   deceleration read off the trajectory — a genuine ITAD/refurbishment QA
   procedure, not invented for this ADR.

Per vertical: add `io.github.kotoba-lang/physics-2d` as a real pinned
git-coordinate dependency; replace the actor's existing synthetic
`simulate-*` function (from ADR-2607142800) with one that actually builds
`physics-2d` bodies/colliders, runs `world-step` for real ticks, and derives
its `:passed?`/telemetry from the REAL simulated reading; update the
governor's independent-recheck to use that real field; anchor the pass/
fail tolerance on a REAL, citable reference value for that industry's
actual test standard (not an invented number) — e.g. cement/concrete
against the batch's/mix's own rated compressive strength, retail drop-test
against a standard test-height/survivability spec, fab against a real
bond-strength/pull-force spec range — each vertical's implementer must
identify and cite the real anchor, mirroring how automotive anchored on
`simverify`'s existing 20g reference pulse.

## Consequences

(+) All 7 manufacturing-stage cloud-itonami actors (2910 + these 6) now
have a real, time-stepped, physically-simulated process check instead of a
symbolic one — one consistent, well-tested physics engine
(`kotoba-lang/physics-2d`) reused fleet-wide, no per-vertical physics-engine
reinvention.
(+) No new Rust, no new physics engine, no new CAD kernel — same discipline
ADR-2607151600 established.
(−) Unlike automotive, these 6 do NOT get a rendered WebGPU scene bridge or
a BREP/CAM geometry pipeline — honest scope is the physics timestep and its
governor-checked reading only. A rendering/geometry layer for these
verticals is further follow-up work, not done here.
(−) Still a 2D projection (physics-2d has no 3D solver) — same disclosed
limit as automotive.
(−) No real robot controller, no real PLC/MES/SCADA/EDA — still simulation,
not control, the same "policy, not control" boundary throughout.

## Verification

See each vertical's own commit (linked from this ADR's addenda as they
land) for exact test counts, the real tolerance-anchor citation used, and
lint/demo status.
