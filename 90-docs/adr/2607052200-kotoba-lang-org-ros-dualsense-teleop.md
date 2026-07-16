# ADR-2607052200: PS5 DualSense teleop bridge (org-ros / com-sony-dualsense / teleop) for cloud-itonami robotics

**Status**: accepted
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki

## Context

ADR-2607011000 put every `cloud-itonami-*` vertical on a robotics premise: a robot
does the physical work, an actor *proposes* a `kotoba.robotics/action`, and an
independent governor (`kotoba-lang/robotics`, pure data, "policy not control") gates
it before anything reaches hardware. `kotoba-lang/giemon` already layers a thin
product-specific governor wrapper on top of that contract for the Otete/Hitogata/
Caterpillar product line.

Two things were missing, confirmed both by direct grep and by ADR-2607050500's own
operational-semantics audit ("no mqtt/dds/ros2/quic repos exist" anywhere in the
superproject):

1. No ROS 2 message/wire-format support anywhere.
2. No PS5 DualSense (or any HID gamepad) support in Clojure anywhere — only Rust
   game-input code in `kami-engine` (UI input, not robot actuation), and only a
   generic keyboard/gamepad action-mapping layer in `kotoba-lang/input`.

Nothing let a *human*, rather than an LLM, be the proposer of a `kotoba.robotics/action`.

There is also a live, if dormant, consumer for a real "ROS 2 action" payload:
`orgs/etzhayyim/com-etzhayyim-tazuna` ("手綱", a separate, R0-gated, constitutionally
heavier teleop actor with its own G1-G12 governance framework) already ships a
`teleopCommand` lexicon whose `payload` field is documented as *"serialized
open-transport command (ROS 2 action / Open-RMF task shape); never proprietary"* —
today that field has nothing real behind it. Its `methods/teleop_safety.cljc`
safety reasoner also already establishes the precedent that `estop`/`halt` commands
must be immediately permitted, never gated behind a permission check — a design
choice this ADR's `teleop` library independently arrives at and reuses.

## Decision

Three new `kotoba-lang` repos (public, Apache-2.0, pure `.cljc`, no network, no
device I/O — identical discipline to `kotoba-lang/robotics`/`giemon`), plus this ADR.
This is deliberately generic/robot-agnostic: it does **not** modify
`kotoba-lang/giemon` or `com-etzhayyim-tazuna` (tazuna's own G7 Council-approval gate
for live actuation is untouched and out of scope here — this only gives its
`teleopCommand.payload` field something real to eventually contain).

### 1. `kotoba-lang/org-ros` — ROS 2 message/wire contract

`kotoba.ros.cdr` (alignment-aware DDS-XTypes plain-CDR, little-endian, primitives +
the standard 4-byte `[0x00 0x01 0x00 0x00]` encapsulation header), `kotoba.ros.msgs`
(EDN shapes + full CDR encode/decode for `builtin_interfaces/Time`,
`std_msgs/{Header,Bool}`, `geometry_msgs/{Vector3,Twist,TwistStamped}`,
`sensor_msgs/Joy`), `kotoba.ros.rosbridge` (a pure EDN↔JSON-shaped-map codec for the
rosbridge v2 protocol — the standard way non-native clients like roslibjs/roslibpy
talk to a live ROS 2 node via `rosbridge_suite`, without implementing DDS), and
`kotoba.ros.qos` (QoS profile EDN data). Explicitly **not** implemented: native
DDS-RTPS UDP discovery/pub-sub — flagged in the README as a documented follow-up,
not a silent gap. 34 tests / 91 assertions, clj-kondo clean.

### 2. `kotoba-lang/com-sony-dualsense` — DualSense HID protocol

`kotoba.dualsense.input` decodes both USB report `0x01` (64B) and Bluetooth report
`0x31` (78B incl. trailing CRC-32) into one EDN input-state shape (sticks, triggers,
buttons, D-pad, touch, battery, gyro/accel, `:crc-valid?` for BT).
`kotoba.dualsense.output` encodes (never writes to a device) LED/rumble/a minimal
adaptive-trigger mode. `kotoba.dualsense.crc32` is a from-scratch portable CRC-32.
Byte offsets were cross-checked against the mainline Linux kernel's
`hid-playstation.c` driver source (the most authoritative public reference, since
Sony publishes no official spec) — high-confidence fields (report IDs, sticks,
triggers, main buttons, D-pad, the BT CRC-32 algorithm/seeds) are fully tested;
lower-confidence fields (gyro/accel scale, touchpad, battery bit semantics, adaptive-
trigger encoding) are implemented best-effort and explicitly flagged in the README's
"Confidence" section as not yet validated against physical hardware. 31 tests / 111
assertions, clj-kondo clean.

### 3. `kotoba-lang/teleop` — the governed bridge

`kotoba.teleop` works over a device-agnostic axis-state shape (not hard-wired to
DualSense) and implements: a speed-mode state machine (`:crawl`/`:normal`/`:turbo`,
each with a velocity scale **and** a default safety-class
`:low`/`:medium`/`:high` — turbo therefore *requires* `kotoba.robotics/gate`'s
existing human-sign-off path before it can move the robot, a deliberate safety
feature); a deadman-switch whose release maps to `kotoba.robotics/safety-stop`
reason `:operator`; an e-stop chord mapping to `safety-stop` reason `:e-stop` — both
stops bypass the action gate entirely, since a stop must never wait on a permission
check (this is the same invariant tazuna's `teleop_safety.cljc` independently
encodes for `estop`/`halt`). `->twist`/`->move-action` build a
`geometry_msgs/Twist`-shaped payload and a gated `kotoba.robotics/action`; `step` is
a pure fold from axis-state frames to emitted actions/stops. `kotoba.teleop.dualsense`
is a thin adapter from `com-sony-dualsense`'s decoded report to the generic
axis-state shape. `kotoba.teleop.governor` mirrors `kotoba.giemon.governor`'s pattern
exactly (thin constructor wrapper, does not redefine `gate`). `kotoba.teleop.ros`
names the one topic teleop cares about (`/cmd_vel`, `geometry_msgs/msg/Twist`) via
`org-ros`'s rosbridge codec. Depends on `kotoba-lang/{robotics,org-ros,
com-sony-dualsense}` via `:local/root`. 12 tests / 63 assertions, clj-kondo clean.

Host-side device I/O (opening a USB/Bluetooth HID handle, opening a rosbridge
WebSocket) is deliberately excluded from all three repos, matching every sibling
kotoba-lang capability's "no network, no I/O" discipline — that glue belongs to
whichever host process actually drives a physical robot.

### Manifest registration

`manifest/repos.edn` `:extra-projects` gains the 3 new paths; `west.yml` regenerated
via `nbb scripts/gen-west-manifest.cljs --entry <name>` per repo (minimal diff per
`:manifest-workflow`'s guardrail against wholesale-regen commits).

## Consequences

- (+) A human can now be the proposer of a `kotoba.robotics/action`, gated by the
  same governor contract every LLM-driven cloud-itonami actor already uses; turbo
  speed is safety-gated by construction, not by convention.
- (+) `org-ros` gives any future consumer (including, eventually, tazuna's
  `teleopCommand.payload`) a real, tested ROS 2 message/CDR/rosbridge contract where
  today there is none in this superproject.
- (+) All three repos stay within the established "org-*/com-\<vendor\>-*/plain-name"
  naming and "pure data, no I/O" architectural conventions — no new pattern
  introduced.
- (−) No native DDS-RTPS transport — a real ROS 2 robot must run `rosbridge_suite`
  (or a future consumer must add DDS separately) to receive commands built with this
  library today.
- (−) Several DualSense byte offsets (gyro/accel scale, touchpad, battery bits,
  adaptive-trigger encoding) are best-effort and unvalidated against physical
  hardware — a named follow-up, not a silent gap.
- (−) `kotoba-lang/teleop` is not wired into any specific cloud-itonami vertical or
  into `com-etzhayyim-tazuna` in this pass — that integration is deliberately left
  to a future, separately-scoped change once a concrete consumer is ready.

## References

- ADR-2607011000 — cloud-itonami robotics premise (the governor/action contract
  this design builds on)
- ADR-2607020000 — `kotoba-lang/giemon` product line (the governor-wrapping pattern
  `kotoba.teleop.governor` mirrors)
- ADR-2607050500 — operational-semantics gap assessment (confirmed no ROS2/DDS/mqtt
  code existed anywhere prior to this ADR)
- ADR-2607021330 — org/repo visibility policy (kotoba-lang defaults to public)
- `orgs/etzhayyim/com-etzhayyim-tazuna/lex/teleopCommand.edn`,
  `methods/teleop_safety.cljc` — related prior art (not modified by this ADR)
- `kotoba-lang/robotics` README's safety model table
- 本 ADR とペアの `.edn`

## Addendum (2026-07-06, org-ros scope growth)

A separate, unrelated project (`kotoba-lang/swarm-choreo`, ADR-2607052300 —
governed, precomputed multi-agent drone-light-show choreography over ROS 2) landed
`kotoba-lang/org-ros` PR #1, extending `kotoba.ros.msgs` with
`geometry_msgs/{Point,Quaternion,Pose,PoseStamped}` encode/decode plus the matching
`type-geometry-msgs-{point,quaternion,pose,pose-stamped}` rosbridge type-strings,
to place a performer's per-tick 3D setpoint on the wire (38 tests / 99 assertions,
up from this ADR's original 34/91). This is exactly the kind of consumer this ADR's
"Decision" section anticipated ("any future consumer... a real, tested ROS 2
message/CDR/rosbridge contract") — noted here so `org-ros`'s message coverage as
described above (§1) is read as a snapshot at this ADR's original date, not
`org-ros`'s current state; the CDR/rosbridge architecture and the native-DDS-out-of-
scope boundary are unchanged. No code in `com-sony-dualsense` or `teleop` was
touched by this addendum.
