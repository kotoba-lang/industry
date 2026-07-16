# ADR-2607150700: cloud-itonami-isic-4211 (community building construction) robotics-process-simulation retrofit

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607142800 (robotics premise -> concrete process
simulation, fleet pattern + isic-2910 reference implementation),
ADR-2607150600 (cloud-itonami-isic-0810 quarryops robotics-retrofit --
the immediately-prior sibling retrofit, same pattern), ADR-2607082700
(`cloud-itonami-isic-4211` disaster/severe-weather safety slice ->
`:partially-implemented`), ADR-2607092151 (`cloud-itonami-isic-4211`
robot-dispatch build/handover slice -> `:implemented`), ADR-2607011000
(robotics premise + ISIC section coverage 21/21)

## Context

ADR-2607142800 established `cloud-itonami-isic-2910` (motor vehicles)
as the reference implementation of a concrete, testable robotics-
process-simulation pattern, and explicitly named `cloud-itonami-
isic-4211` (construction) as one of the manufacturing-stage actors
carrying the `:robotics` flag (`blueprint.edn`'s
`:itonami.blueprint/robotics true`) without ever dispatching a
simulated `kotoba.robotics` mission/action through an independently-
gated governor check -- tracked there as follow-up work.
ADR-2607150600 already replicated the pattern into `cloud-itonami-
isic-0810` (quarryops) and explicitly named isic-4211 as the next
retrofit in the same follow-up list.

`cloud-itonami-isic-4211` already had TWO governed-actor slices on the
SAME `:construction-governor`: the disaster/severe-weather SAFETY slice
(ADR-2607082700) and the physical ROBOT-DISPATCH (build/handover) slice
(ADR-2607092151, the actor repo's own `docs/adr/0002-robot-dispatch-
slice.md`). The build slice already had a REAL-world robot-dispatch
actuation (`:build/dispatch-placement` -- a construction robot
physically places a building element) gated by a genuinely-new
`permit-and-inspection-required` HARD check (governor check 8) -- but,
identical in shape to isic-2910's and isic-0810's pre-retrofit state,
nothing in the actor ever ran a robot verification mission or
independently re-verified its result before that dispatch could
commit. `:robotics` was a declared flag, not a function call in a
governor's HARD-check path.

## Decision

Replicate ADR-2607142800's pattern into `cloud-itonami-isic-4211`,
adapted to construction's own domain (a `site` entity, not a vehicle
or an extraction), gating the build slice's EXISTING robot-dispatch
actuation (`:build/dispatch-placement`) rather than introducing a new
actuation op:

1. **`construction.robotics`** (new namespace) maps the vertical's real
   physical pre-placement verification process to a `kotoba.robotics/
   mission` + three `kotoba.robotics/action`s -- rebar-placement scan
   (`:sense`/`:none`), robotic total-station as-built survey confirming
   as-built dimensions against design (`:sense`/`:none`), concrete-cure
   compressive-strength test-cylinder press (`:actuate`/`:low`) -- each
   with a `kotoba.robotics/telemetry-proof`. `as-built-tolerance-out-
   of-range?` is the ground-truth check (the site's own recorded
   `:as-built-deviation-actual`/`:as-built-deviation-min`/`:as-built-
   deviation-max`, mm), and `simulation-out-of-tolerance?` is the
   governor's independent recheck -- the same "ground truth, not
   self-report" discipline `construction.facts/weather-threshold-
   exceeded?` already established for weather.
2. **New op `:robotics/simulate-placement-verification`** -- always
   human-approval, never a member of any phase's `:auto` set (added to
   `write-ops` and phase 2/3's `:writes`, matching every sibling
   verification/screening op's posture, incl. `:inspection/screen`).
   Commits onto the site via the EXISTING generic `:site/upsert`
   effect -- no new Store protocol method needed -- as two new
   dedicated fields, `:robotics-sim-verified?` (boolean) and
   `:robotics-sim-record` (map), never a `:status` value.
3. **New governor HARD check (check 9), `robotics-simulation-
   violations`** -- for `:build/dispatch-placement` ONLY (not
   `:handover/complete`, exactly as isic-2910's sibling check gates
   only `:actuation/dispatch-vehicle`, never certificate issuance):
   HARD-hold if the mission never ran and was recorded
   (`:robotics-sim-verified?` false), OR if it did but an INDEPENDENT
   recompute of the site's own as-built-deviation fields says
   out-of-tolerance right now, ignoring whatever `:passed?` verdict the
   mission itself stored. Inserted right after `permit-and-inspection-
   required-violations` (construction's own pre-existing check 8, the
   evidence-checklist analog) and before the double-actuation guards in
   the governor's `concat` chain and numbered docstring -- the same
   priority position isic-2910/isic-0810 used relative to their own
   pre-existing evidence/ground-truth-recompute checks.
4. **New dedicated fixture** -- `site-6` (ことぶき小学校増築棟), seeded
   with `:permit-issued? true` and `:robotics-sim-verified? true`
   (already on file) but `:as-built-deviation-actual 40` outside its
   own recorded `[-15, 15]` mm bounds, isolating the "on file but
   independently out-of-tolerance" scenario exactly like isic-2910's
   `vehicle-5` and isic-0810's `extraction-7`.
5. **Test updates** -- two happy-path dispatch tests
   (`placement-always-escalates-then-human-decides`,
   `double-placement-is-held`) gained a new `simulate-robotics!` setup
   step; `store_contract_test.clj`'s subject-count assertion bumped
   from 5 to 6 sites, plus a new `:site/upsert`-commits-robotics-sim
   testing block; 3 new dedicated tests added
   (`robotics-simulation-always-needs-approval`,
   `dispatch-placement-without-robotics-simulation-is-held`,
   `robotics-simulation-out-of-tolerance-is-held`), plus 2 new
   phase-invariant tests mirroring isic-2910's/isic-0810's own
   additions. No existing test regressed.
6. **`construction.sim`** (demo driver) walks site-4 through
   `:build/dispatch-placement` BEFORE its robot pre-placement
   verification mission ever ran (HARD hold,
   `:robotics-simulation-missing`), then runs the mission and retries
   (commits, human-approved), then demonstrates
   `:robotics-simulation-out-of-tolerance` on site-6.
7. **`deps.edn`** gains `io.github.kotoba-lang/robotics {:local/root
   "../../kotoba-lang/robotics"}`, matching isic-2910's/isic-0810's own
   addition.

## Consequences

(+) construction's robotics premise is now exercised, not just
declared: a real governor HARD-check (check 9) calls a real simulated
mission result, gated exactly like every other evidence/ground-truth
check in this actor, and specifically pre-conditions the build slice's
EXISTING real-world robot-dispatch actuation
(`:build/dispatch-placement`) rather than introducing a new actuation
event.
(+) The retrofit is a small, bounded diff (one new namespace, one new
op, one new governor check, a handful of new store fields, matching
test/README updates) -- 11 files changed, 372 insertions / 36 deletions
in `cloud-itonami-isic-4211`.
(+) Three manufacturing-stage actors (isic-2910, isic-0810, isic-4211)
now share the identical robotics-process-simulation pattern verbatim in
shape (different field/op names, same structure); isic-4211's variant
additionally demonstrates the pattern gating a PRE-EXISTING real-world
actuation op rather than only a freshly-introduced one, a useful
precedent for retrofitting other actors whose robot-dispatch actuation
already shipped.
(-) Still a simulation, not a real robot-cell integration --
`construction.robotics` remains "policy, not control" by design,
matching `kotoba.robotics`'s own posture.
(-) Retrofitting the remaining manufacturing actor identified in
ADR-2607142800 (isic-2610 fab) remains follow-up work, tracked outside
this ADR.

## Verification

- `cloud-itonami-isic-4211`: `clojure -M:dev:test` green (83 tests /
  372 assertions, up from 78/344), `clojure -M:lint` clean, `clojure
  -M:dev:run` demo narrative exercises both new HARD holds
  (`:robotics-simulation-missing`, `:robotics-simulation-out-of-
  tolerance`) with no exceptions.
- Commit `9aee372` pushed directly to `cloud-itonami-isic-4211`'s
  `main` (matching that repo's own single-branch-direct-to-main
  convention).
