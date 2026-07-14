# ADR-2607142800: robotics premise → concrete process simulation (fleet pattern)

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607011000 (robotics premise + ISIC section coverage
21/21), ADR-2607111600 (isic-2910 motorvehicle → `:implemented`),
ADR-2607141000 (kotoba-lang/plm BOP/MPS/PDM integration)

## Context

ADR-2607011000 mandated a **robotics premise** across all cloud-itonami
verticals: every ISIC industry-registry entry carries `:robotics` as a
required technology, every blueprint.edn carries
`:itonami.blueprint/robotics true`, and `kotoba-lang/robotics` defines
the pure-data contract (`mission`/`action`/`safety-stop`/
`telemetry-proof`/`gate`) a governor uses to refuse unsafe actuation
before it reaches hardware.

That ADR's own Consequences section was explicit about the gap it
left open: **"Physics / plant digital-twin geometry remains out of
scope (export + operator console samples only)."** Concretely,
`cloud-itonami-isic-2910` (motor vehicles) required a vehicle-dispatch
proposal to cite a "CAE-simulation-report" as one of four evidence-
checklist items, but that checklist was a **self-reported string**
inside `automotive.facts` — nothing in the actor actually ran a
simulation or independently verified its result. The robotics premise
was declared but not exercised: `:robotics` was a flag on a registry
entry, not a function call in a governor's HARD-check path.

A survey of the manufacturing value chain across automotive, computer/
electronics and construction verticals (2026-07-14) found the same
gap repeated: manufacturing-stage actors (isic-2910, isic-2610 fab,
isic-0810 quarryops, isic-4211 construction) all carry the `:robotics`
flag but none dispatch a simulated `kotoba.robotics` mission/action as
part of an independently-gated governor check.

## Decision

**Establish `cloud-itonami-isic-2910` as the reference implementation**
of a concrete, testable robotics-process-simulation pattern, to be
replicated by every manufacturing-stage cloud-itonami actor going
forward (new and existing):

1. **A per-actor `<domain>.robotics` namespace** (here:
   `automotive.robotics`) maps the vertical's real physical process
   steps to a `kotoba.robotics/mission` + a sequence of
   `kotoba.robotics/action`s (kind + safety-class), and a
   `kotoba.robotics/telemetry-proof` per step. `simulate-*` is a pure,
   deterministic function — no network/IO, matching `kotoba.robotics`'s
   own "policy, not control" ethos — that derives its verdict from the
   subject's own recorded ground-truth fields (never randomized, never
   self-reported by an LLM).
2. **A new, always-human-approval write op** (here:
   `:robotics/simulate-assembly-line`) that runs the mission and
   commits its verdict onto the subject as a dedicated boolean field
   (here: `:robotics-sim-verified?`, never a `:status` value — the same
   "dedicated boolean, not status" discipline this fleet's governors
   already use for double-actuation guards). Never a member of any
   phase's `:auto` set, at any phase — matching the posture every prior
   verification/screening op in this fleet already has.
3. **A new governor HARD check** that gates the vertical's real-world
   actuation op: (a) HARD-hold if the mission never ran and was
   recorded on the subject, and (b) HARD-hold if it did, but an
   INDEPENDENT recompute of the subject's own ground-truth tolerance
   fields disagrees with the mission's stored verdict — the governor
   never trusts the self-reported `:passed?` alone, the same "ground
   truth, not self-report" discipline the fleet's existing range-check
   family (`vehicle-emissions-out-of-range?` and siblings) already
   established. This is the **physical-actuation-safety analog** of
   that discipline: `kotoba.robotics/gate`'s single invariant
   ("governor never dispatches hardware it would reject") is now
   exercised by an actual governor function, not merely declared.
4. **Reference implementation delivered in this ADR**: `cloud-itonami-
   isic-2910`'s `automotive.robotics` (crash-simulation replay /
   chassis-weld torque-check / paint-thickness scan mission),
   `automotive.governor/robotics-simulation-violations`, and 5 new
   tests (44 tests / 219 assertions total, up from 39/191; `clojure
   -M:lint` clean) — see that repo's `automotive.robotics` ns docstring
   and README "Robotics premise" section.

## Consequences

(+) The robotics premise is now exercised, not just declared: a real
governor HARD-check calls a real simulated mission result, gated
exactly like every other evidence/ground-truth check in this fleet.
(+) The pattern is a small, bounded diff per actor (one new namespace,
one new op, one new governor check, a handful of new store fields) —
tractable to replicate across the fleet without a rewrite.
(+) Establishes precedent other manufacturing actors in this fleet
must cite (per this fleet's own convention of ADR-0001s citing prior
siblings) rather than re-deriving their own ad-hoc robotics-integration
shape.
(−) Still a simulation, not a real robot-cell integration — `kotoba.
robotics` remains "policy, not control" by design; a real deployment
swaps the `simulate-*` function for a real robot-cell telemetry feed
without changing the governor contract (the same Store-protocol swap
seam this fleet already uses for MemStore → DatomicStore).
(−) Retrofitting existing manufacturing actors (isic-2610 fab,
isic-0810 quarryops, isic-4211 construction) and building the parts/
midstream/downstream gap actors identified in the 2026-07-14
value-chain survey (isic-2930 automotive parts, isic-2620 computer/
peripheral manufacture, isic-2394 cement, isic-4741 computer retail)
with this pattern from day one is follow-up work, tracked outside this
ADR (see addenda as each lands).

## Verification

- `cloud-itonami-isic-2910`: `clojure -M:dev:test` green (44 tests /
  219 assertions, up from 39/191), `clojure -M:lint` clean, `clojure
  -M:dev:run` demo narrative exercises both new HARD holds
  (`:robotics-simulation-missing`, `:robotics-simulation-out-of-
  tolerance`) with no exceptions.
