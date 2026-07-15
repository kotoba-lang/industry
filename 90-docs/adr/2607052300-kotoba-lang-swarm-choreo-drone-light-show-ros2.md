# ADR-2607052300: kotoba-lang/swarm-choreo — governed multi-agent choreography (drone light shows) over ROS 2 (org-ros)

**Status**: accepted
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki

## Context

A prior survey of this superproject found no drone-light-show or swarm-choreography
design anywhere. What exists: `kotoba-lang/kami-autodrive` (vehicle-class-agnostic
GNC — `autodrive.dynamics/multirotor` rotor thrust-vectoring/aero drag plant, plus
`autodrive.fleet`, a **reactive** priority + right-of-way multi-agent collision
avoidance; the `:drone` class is a 2D ground-projected agile-multirotor limit set,
not a 3D formation model); `orgs/etzhayyim/root/60-apps/etzhayyim-project-drone`
(Matrix protocol + MAVLink + XRPC operations platform for single/small-fleet survey
and inspection missions, not choreography); and a stub
`kotoba-lang/kotodama-cells/robotics_drone_swarm` README with no implementation
behind it.

Since that survey, ADR-2607052200 landed three new `kotoba-lang` repos:
`org-ros` (ROS 2 message/CDR/rosbridge v2/QoS contract, pure `.cljc`, no native
DDS-RTPS), `com-sony-dualsense` (HID protocol), and `teleop` (device-agnostic
speed-mode/deadman/e-stop bridge), layered on `kotoba-lang/robotics`'s governor
contract from ADR-2607011000 (an actor proposes a `kotoba.robotics/action`; an
independent governor gates it; the governor never itself drives hardware; a stop
always bypasses the gate). This gives the org a real, tested ROS 2 wire-format
layer and an established "policy not control, no device I/O" discipline to build
the swarm/light-show capability on top of, instead of starting cold.

External OSS surveyed for reference (not proposed as dependencies): Skybrush
(`skybrush-io` — server/live/Blender-studio choreography stack, the direct
real-world reference for drone light shows; MAVLink-based at the vehicle layer);
MAVROS (the standard ROS 2 ↔ MAVLink bridge PX4 and ArduPilot already ship — the
same flight-controller layer Skybrush itself drives); ZJU-FAST-Lab's
EGO-Planner-Swarm / Swarm-Formation (decentralized collision-free swarm-trajectory
research, ROS-native); Crazyswarm2 (USC ACT Lab, ROS 2 multi-Crazyflie swarm
architecture reference).

The open question from that survey was whether to invent a bespoke swarm wire
format or standardize on ROS 2. The owner's steer was that ROS 2 "seems more
general-purpose" (汎用性が高い). This ADR evaluates and confirms that steer.

## Decision

### ROS 2 (via the existing `org-ros` contract) is the wire/interop layer — not a bespoke format

Reasons: (1) `org-ros` already exists and is tested — no new wire format to invent;
(2) ROS 2 is the layer real hardware already speaks: PX4 and ArduPilot both ship a
MAVROS bridge, so a host process can take `org-ros`-shaped messages →
`rosbridge_suite` → native ROS 2 DDS → MAVROS → MAVLink → autopilot, without kotoba
ever touching MAVLink directly (the same boundary `teleop` already drew for
`/cmd_vel`); (3) ROS 2 carries the surrounding tooling this domain needs "for
free" — Gazebo/PX4-SITL multi-vehicle simulation, RViz visualization, tf2
multi-frame math, and prior art (Crazyswarm2, EGO-Swarm/Swarm-Formation) to study
or eventually interoperate with; (4) it keeps the whole
`org-ros`/`robotics`/`teleop`/`swarm-choreo` stack on one interop story instead of
two.

This decision does **not** add native DDS-RTPS pub/sub or a MAVROS/MAVLink
implementation to any `kotoba-lang` repo — that stays explicitly out of scope,
host-process responsibility, exactly as ADR-2607052200 already scoped for
`teleop`.

### New repo: `kotoba-lang/swarm-choreo`

Public, Apache-2.0, pure `.cljc`, no network, no device I/O — identical discipline
to `robotics`/`org-ros`/`teleop`. Depends on
`kotoba-lang/{kami-autodrive, robotics, org-ros}` via `:local/root`. Deliberately
generic across `kami-autodrive`'s vehicle classes (`:drone` is the flagship use
case; `:car`/`:ship`/`:aircraft` formation choreography reuse the same code) — does
not modify `kami-autodrive`, `robotics`, or `org-ros`.

Why a new repo instead of extending `autodrive.fleet`: `fleet.cljc` is a
**reactive** per-tick collision-avoidance simulator — each agent senses and yields
to higher-priority agents in real time. A light show is the opposite problem: the
whole timeline is **authored in advance** by an external tool and only needs to be
validated offline and replayed on a governed schedule. Conflating the two would
turn a small, well-scoped reactive-avoidance module into two unrelated concerns.

- `kotoba.swarm-choreo.show` — the ingested plan:
  `{:show/id .. :performers [{:id .. :class :drone :home [x y z]
  :trajectory [[t x y z yaw] ...]} ...] :geofence .. :abort-conditions ..}`.
  Format chosen to be a straightforward mechanical transform away from Skybrush's
  per-performer position/time export. `swarm-choreo` deliberately does **not**
  build a choreography-design UI (no Blender-equivalent) — authoring stays
  external (Skybrush Studio, or any tool emitting per-drone position/time
  waypoints), matching the "no authoring surface" discipline `kotoba.robotics.ui`
  already established (read-only dashboard, no `<form>`/`<button>`).
- `kotoba.swarm-choreo.validate` — offline verification of an ingested show
  before it is ever armed: whole-timeline pairwise minimum separation (the
  `fleet/min-separation` idea, but over the full precomputed timeline instead of
  one tick), geofence containment, and per-performer max-speed/max-accel against
  `autodrive.classes/limits`. A show that fails validation is rejected before any
  `kotoba.robotics/action` is even proposed.
- `kotoba.swarm-choreo.gate` — every show-lifecycle transition (arm → takeoff →
  formation-change → RTL/land) is a `kotoba.robotics/action` through
  `kotoba.robotics/gate`, safety-classed the way `teleop`'s turbo mode already is:
  routine formation playback is `:low`/`:medium`; arm and takeoff are
  `:high`/`:safety-critical` (human sign-off, interrupt-before). An all-abort
  ("RTL-all"/"land-all") bypasses the gate entirely and maps straight to
  `kotoba.robotics/safety-stop` — the same invariant `teleop`'s e-stop and
  tazuna's `teleop_safety.cljc` both independently encode: a stop must never wait
  on a permission check.
- `kotoba.swarm-choreo.ros` — turns one performer's per-tick setpoint into an
  `org-ros`-shaped message for the rosbridge path. This needs `org-ros` to gain
  `geometry_msgs/{Point,Quaternion,Pose,PoseStamped}` (encode/decode, same CDR
  pattern as the existing `Vector3`/`Twist`/`TwistStamped`) — a small additive
  extension to `org-ros`, not a new repo; called out here as a dependency of this
  ADR, to be delivered alongside `swarm-choreo`.

### Explicitly out of scope (documented follow-ups, not silent gaps)

- No choreography-design tool (Blender plugin or otherwise) — authoring stays
  external.
- No native DDS-RTPS transport, no MAVROS/MAVLink implementation — a host process
  bridges `org-ros`'s rosbridge messages to real hardware, exactly as already
  scoped for `teleop`.
- No `cloud-itonami-*` ISIC blueprint for "drone light show as a business" in this
  pass — this ADR is the capability layer only; a blueprint, if wanted, is a
  separate later decision.
- No integration into `com-etzhayyim-tazuna` or any specific product — generic
  capability only, matching how `teleop` was deliberately left unwired.

### Manifest registration

`manifest/repos.edn` `:extra-projects` gains `orgs/kotoba-lang/swarm-choreo`;
`west.yml` regenerated via `nbb scripts/gen-west-manifest.cljs --entry swarm-choreo`
(minimal diff, per `:manifest-workflow`'s guardrail against wholesale-regen
commits). `org-ros`'s new message shapes land as a normal commit/PR to the
existing repo — no manifest change needed since that path is already registered.

## Consequences

- (+) One interop story (ROS 2) across `teleop` and `swarm-choreo` instead of two
  incompatible wire formats.
- (+) Reuses `kami-autodrive`'s multirotor dynamics/classes and `robotics`'
  governor contract as-is — no changes to either.
- (+) A show can be authored in an existing external tool (e.g. Skybrush Studio)
  and still pass through kotoba's governor/validation discipline before anything
  reaches hardware.
- (+) A clear, already-precedented real-hardware path (rosbridge → DDS → MAVROS →
  MAVLink) reuses infrastructure PX4/ArduPilot already ship, instead of a bespoke
  swarm protocol.
- (−) Offline validation (`swarm-choreo.validate`) checks the planned timeline,
  not reality — RTK GPS drift, wind, clock sync, and radio range are invisible to
  a pure-data simulation; a real operator must still carry independent safety
  margins. This is a named limit, not a claim that validation makes a show safe by
  itself.
- (−) `org-ros` needs new message shapes (`Point`/`Quaternion`/`Pose`/
  `PoseStamped`) before `swarm-choreo.ros` can be built — a sequencing dependency,
  not a blocker to accepting this ADR.
- (−) No native DDS-RTPS or MAVROS in any `kotoba-lang` repo — a host process must
  still supply that bridge, the same honest limit `teleop` already carries.
- (−) No choreography-authoring UI — using this capability still requires an
  external tool (Skybrush Studio or equivalent) to actually design a show.

## References

- ADR-2607011000 — cloud-itonami robotics premise (the governor/action contract
  this design builds on)
- ADR-2607052200 — `org-ros`/`com-sony-dualsense`/`teleop` (the ROS 2 wire layer
  and governor-integration pattern this ADR reuses directly)
- ADR-2607050500 — operational-semantics gap assessment (confirmed no
  ROS2/DDS/mqtt code existed prior to ADR-2607052200)
- ADR-2607021330 — org/repo visibility policy (kotoba-lang defaults to public)
- `kotoba-lang/kami-autodrive` README + `autodrive.fleet`/`autodrive.classes`
  (existing multirotor dynamics + reactive fleet-avoidance this design does not
  modify)
- `kotoba-lang/robotics` README's safety model table (governor gate / safety-class
  contract)
- External reference (not a dependency): Skybrush (`skybrush-io`) — choreography
  design + real-world show operation reference; MAVROS — the ROS 2 ↔ MAVLink
  bridge PX4/ArduPilot already ship; ZJU-FAST-Lab EGO-Planner-Swarm/
  Swarm-Formation and USC ACT Lab Crazyswarm2 — swarm trajectory/architecture
  research prior art
- 本 ADR とペアの `.edn`

## Follow-up (2026-07-06)

- Landed `kotoba.swarm-choreo.physics-check`: a stronger, slower check that
  runs `kami-autodrive`'s *real* closed-loop `:drone` autopilot +
  `autodrive.dynamics/multirotor` plant (not `validate`'s straight-line
  finite-difference approximation) on each show segment, in an open world,
  and checks arrival within a slack-adjusted time budget
  (`horizontal-segment-feasible?`, `show-segments-feasible`). Vertical
  (climb/descent) rate is checked separately against a swarm-choreo-owned
  conservative default (`default-vertical-speed-limit`, 5 m/s) —
  `kami-autodrive`'s plant models no altitude channel at all for any
  vehicle class, `:drone` included, so there is nothing in the dependency
  itself to check a climb rate against. 22 tests / 65 assertions,
  clj-kondo clean, CI green (`kotoba-lang/swarm-choreo` commit `5afe83b`).
- This is additive to the already-scoped `kotoba-lang/swarm-choreo` repo —
  it does not change the Decision above, and does not touch `org-ros`,
  `kami-autodrive`, or `robotics`.
- Still open, unchanged from the original Decision's scope: the Skybrush
  import format, native DDS-RTPS/MAVROS, and any product integration
  remain deliberately out of scope.
