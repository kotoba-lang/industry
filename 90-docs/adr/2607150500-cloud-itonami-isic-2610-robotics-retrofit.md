# ADR-2607150500: `cloud-itonami-isic-2610` (fab) robotics-process-simulation retrofit

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607142800 (robotics premise → concrete process
simulation, fleet pattern, isic-2910 reference implementation),
ADR-2607081300 (`cloud-itonami-isic-2610` fab → `:implemented`)

## Context

ADR-2607142800 established `cloud-itonami-isic-2910` (motor vehicles)
as the reference implementation of a concrete, testable robotics-
process-simulation pattern: a per-actor `<domain>.robotics` namespace
that maps real physical process steps to a `kotoba.robotics/mission` +
a sequence of `kotoba.robotics/action`s, a deterministic ground-truth
tolerance check on the subject's own recorded fields, a new always-
human-approval write op that runs the mission and records its verdict
on a dedicated boolean, and a new governor HARD check that requires
the mission on file AND independently re-derives out-of-tolerance
before the vertical's real-world robot-actuation op may commit. That
ADR named `cloud-itonami-isic-2610` (fab) explicitly as one of the
manufacturing-stage actors carrying the `:robotics` flag without yet
exercising it concretely, and tracked the retrofit as follow-up work.

This ADR records that follow-up landing on `cloud-itonami-isic-2610`.

## Decision

**Retrofit the isic-2910 robotics-process-simulation pattern onto
`cloud-itonami-isic-2610`'s own domain** (Fab Advisor ⊣ Fab Operations
Governor, semiconductor/electronics fab — lot intake, per-jurisdiction
process-safety requirements verification, process-defect screening,
process-step dispatch and yield-audit finalization), adapted to its
own subject entity and fields (a wafer **lot**, not a vehicle):

1. **`fab.robotics`** — a new namespace mapping a three-step robot
   cleanroom process-step verification mission to `kotoba.robotics/
   mission`/`action`/`telemetry-proof`: automated wafer-probe
   electrical test (`:sense`/`:none`), robotic optical-defect
   inspection scan (`:sense`/`:none`), automated wire-bond pull-test
   (`:actuate`/`:low`). `bond-pull-strength-out-of-range?` is the
   ground-truth two-sided range check (a lot's own recorded
   `:bond-pull-strength-actual` against its own recorded
   `[:bond-pull-strength-min :bond-pull-strength-max]` bounds) — the
   same shape `automotive.registry/vehicle-emissions-out-of-range?`
   and siblings use, here a NEW field distinct from this actor's
   existing ratio-based `yield-rate-insufficient?` check (fab already
   had a ground-truth check family member; this retrofit adds a
   second, two-sided-range-shaped one specifically for the robotics
   mission, rather than reusing yield-rate for it).
   `simulation-out-of-tolerance?` independently re-derives the verdict
   from those same fields, never from the mission's self-reported
   `:passed?`.
2. **`:robotics/simulate-process-step`** — a new, always-human-
   approval write op (never a member of any phase's `:auto` set, at
   any phase) that runs the mission and commits `:robotics-sim-
   verified?` (dedicated boolean) + `:robotics-sim-record` onto the
   lot via the EXISTING `:lot/upsert` effect — no new Store protocol
   method needed, mirroring isic-2910's approach exactly.
3. **`fab.governor/robotics-simulation-violations`** — a new HARD
   check gating `:actuation/dispatch-process-step` (the real-world
   robot process-step-dispatch actuation, the fab analog of isic-
   2910's `:actuation/dispatch-vehicle`): HARD-hold if the mission
   never ran and was recorded on the lot, OR if it did but an
   INDEPENDENT recompute of the lot's own bond-pull-strength fields
   says out-of-tolerance right now. Inserted immediately after the
   existing `evidence-incomplete-violations` check and before the
   existing `process-defect-flag-unresolved-violations`/`yield-rate-
   insufficient-violations` checks — the governor's numbered docstring
   renumbered from six checks to seven accordingly.
4. **Tests**: the two existing dispatch-focused governor-contract
   tests (`dispatch-process-step-always-escalates-then-human-
   decides`, `dispatch-process-step-double-dispatch-is-held`) gained a
   `simulate-robotics!` setup step; a new dedicated fixture lot
   (`lot-5`, seeded `:robotics-sim-verified? true` but with an out-of-
   tolerance `:bond-pull-strength-actual` of 3.0 outside
   `[6.0,12.0]`) was added for the "on-file-but-independently-out-of-
   tolerance" scenario; `store_contract_test.clj`'s lot-count/read-
   parity assertions were bumped from 4 to 5 lots. Three new dedicated
   tests were added: `robotics-simulation-always-needs-approval`
   (never-auto-eligible), `dispatch-process-step-without-robotics-
   simulation-is-held` (missing-simulation HARD hold), `robotics-
   simulation-out-of-tolerance-is-held` (independent-recheck HARD
   hold) — plus two `phase_test.clj` invariant tests mirroring isic-
   2910's `robotics-simulate-assembly-line-never-auto-at-any-phase`/
   `-enabled-from-phase-2`.
5. `deps.edn` gained `io.github.kotoba-lang/robotics {:local/root
   "../../kotoba-lang/robotics"}` (fab's repo lives at the same depth
   as isic-2910's in this workspace, so the relative path is
   identical). `fab.sim` (`clojure -M:dev:run`) walks a clean lot
   through the new op plus both new HARD-hold scenarios (`lot-1`
   before/after the mission runs; `lot-5`'s on-file-but-out-of-
   tolerance case). README's `Robotics premise` section, Layout table
   and Business-process coverage table were updated to describe the
   concrete mission and the new governor check/op.

## Consequences

(+) `cloud-itonami-isic-2610`'s robotics premise is now exercised, not
just declared, following the exact precedent isic-2910's ADR-2607142800
established — a real governor HARD-check calls a real simulated
mission result, gated exactly like every other evidence/ground-truth
check in this actor.
(+) The retrofit reused the isic-2910 diff shape almost verbatim (one
new namespace, one new op, one new governor check, a handful of new
store fields, no new Store protocol method) while adapting every
field/namespace name to fab's own domain (lot, not vehicle;
bond-pull-strength, not structural-deviation; process-step-dispatch,
not vehicle-dispatch) — confirming the pattern is bounded and tractable
to replicate.
(+) Test suite grew from 36 tests / 177 assertions (ADR-2607081300) to
41 tests / 205 assertions, `clojure -M:lint` stays clean, and no
existing test silently regressed (the two dispatch-focused contract
tests needed — and received — a `simulate-robotics!` setup step to
keep passing under the new HARD check).
(−) Still a simulation, not a real robot-cell integration — `fab.
robotics` remains "policy, not control" by design, matching isic-
2910's own accepted limitation.
(−) `cloud-itonami-isic-0810` (quarryops) and `cloud-itonami-isic-4211`
(construction), the other two manufacturing-stage actors ADR-2607142800
named as carrying the `:robotics` flag without a concrete mission,
remain follow-up work outside this ADR.

## Verification

- `cloud-itonami-isic-2610`: `clojure -M:dev:test` green (41 tests /
  205 assertions, up from 36/177), `clojure -M:lint` clean, `clojure
  -M:dev:run` demo narrative exercises both new HARD holds
  (`:robotics-simulation-missing`, `:robotics-simulation-out-of-
  tolerance`) with no exceptions.
- Commit `da84cd2c2ad081f8d13e7b78e02aaa56e7be519b` pushed directly to
  `cloud-itonami/cloud-itonami-isic-2610`'s `main` (matching that
  repo's existing single-branch-direct-to-main convention).
