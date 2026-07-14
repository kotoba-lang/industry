# ADR-2607150600: cloud-itonami-isic-0810 (quarryops) robotics-process-simulation retrofit

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607142800 (robotics premise -> concrete process
simulation, fleet pattern + isic-2910 reference implementation),
ADR-2607091800 (cloud-itonami-isic-0810 quarryops deepened to
`:implemented`), ADR-2607011000 (robotics premise + ISIC section
coverage 21/21)

## Context

ADR-2607142800 established `cloud-itonami-isic-2910` (motor vehicles)
as the reference implementation of a concrete, testable robotics-
process-simulation pattern, and explicitly named `cloud-itonami-
isic-0810` (quarryops) as one of the manufacturing-stage actors
carrying the `:robotics` flag (`blueprint.edn`'s
`:itonami.blueprint/robotics true`) without ever dispatching a
simulated `kotoba.robotics` mission/action through an independently-
gated governor check -- tracked there as follow-up work.

`quarryops`'s own README already declared the premise in prose ("an
extraction robot performs drilling, cutting and loadout at the quarry
face"), and its Quarry Governor already had five HARD checks (spec-
basis, evidence-incomplete, royalty-mismatch, extraction-permit-
invalid, blast-safety-clearance-unconfirmed) plus two double-actuation
guards -- but nothing in the actor ever ran a robot mission or
independently re-verified its result before `:actuation/extract-
material` could commit. The gap was identical in shape to isic-2910's
pre-retrofit state: a declared flag, not a function call in a
governor's HARD-check path.

## Decision

Replicate ADR-2607142800's pattern into `cloud-itonami-isic-0810`,
adapted to quarryops's own domain (extraction records, not vehicles):

1. **`quarryops.robotics`** (new namespace) maps the vertical's real
   physical process to a `kotoba.robotics/mission` + three
   `kotoba.robotics/action`s -- bench-face dimensional survey (robot/
   drone geometry scan against the permitted extraction boundary,
   `:sense`/`:none`), core-sample quality assay (`:actuate`/`:low`),
   dust/particulate-emissions scan (`:sense`/`:none`) -- each with a
   `kotoba.robotics/telemetry-proof`. `face-boundary-deviation-out-of-
   range?` is the ground-truth check (extraction's own recorded
   `:face-deviation-actual`/`:face-deviation-min`/`:face-deviation-
   max`), and `simulation-out-of-tolerance?` is the governor's
   independent recheck -- the same "ground truth, not self-report"
   discipline `quarryops.registry/royalty-matches-claim?` already
   established for royalty.
2. **New op `:robotics/simulate-quarry-face-verification`** -- always
   human-approval, never a member of any phase's `:auto` set (added to
   `write-ops` and phase 2/3's `:writes`, matching every sibling
   verification op's posture). Commits onto the extraction via the
   EXISTING generic `:extraction/upsert` effect -- no new Store
   protocol method needed -- as two new dedicated fields,
   `:robotics-sim-verified?` (boolean) and `:robotics-sim-record`
   (map), never a `:status` value.
3. **New governor HARD check, `robotics-simulation-violations`** --
   for `:extraction/extract`: HARD-hold if the mission never ran and
   was recorded (`:robotics-sim-verified?` false), OR if it did but an
   INDEPENDENT recompute of the extraction's own face-boundary-
   deviation fields says out-of-tolerance right now, ignoring whatever
   `:passed?` verdict the mission itself stored. Inserted right after
   `evidence-incomplete-violations` and before `royalty-mismatch-
   violations` (quarryops's own pre-existing ground-truth-recompute
   check) in the governor's `concat` chain and numbered docstring --
   the same priority position isic-2910 used relative to its own
   pre-existing `vehicle-emissions-out-of-range-violations`.
4. **New dedicated fixture** -- `extraction-7`, seeded with
   `:robotics-sim-verified? true` (already on file) but
   `:face-deviation-actual 0.30` outside its own recorded
   `[-0.05, 0.05]` bounds, isolating the "on file but independently
   out-of-tolerance" scenario exactly like isic-2910's `vehicle-5`.
5. **Test updates** -- three happy-path dispatch tests
   (`extraction-extract-is-a-noop-when-no-blasting`,
   `extraction-always-escalates-then-human-decides`,
   `extraction-double-extraction-is-held`) gained a new
   `simulate-robotics!` setup step; `store_contract_test.clj`'s
   subject-count assertion bumped from 6 to 7 extractions; 3 new
   dedicated tests added (`robotics-simulation-always-needs-approval`,
   `extraction-without-robotics-simulation-is-held`,
   `robotics-simulation-out-of-tolerance-is-held`), plus 2 new phase-
   invariant tests mirroring isic-2910's own additions. No existing
   test regressed.
6. **`quarryops.sim`** (demo driver) walks extraction-1 through the
   new op before its happy-path extraction, demonstrates
   `:robotics-simulation-missing` on extraction-6 before running its
   mission, and demonstrates `:robotics-simulation-out-of-tolerance`
   on extraction-7.
7. **`deps.edn`** gains `io.github.kotoba-lang/robotics {:local/root
   "../../kotoba-lang/robotics"}`, matching isic-2910's own addition.

## Consequences

(+) quarryops's robotics premise is now exercised, not just declared:
a real governor HARD-check calls a real simulated mission result,
gated exactly like every other evidence/ground-truth check in this
actor.
(+) The retrofit is a small, bounded diff (one new namespace, one new
op, one new governor check, a handful of new store fields, matching
test/README updates) -- 11 files changed, 388 insertions / 53
deletions in `cloud-itonami-isic-0810`.
(+) Two manufacturing-stage actors (isic-2910, isic-0810) now share
the identical robotics-process-simulation pattern verbatim in shape
(different field/op names, same structure), making the next
retrofit (isic-2610 fab, isic-4211 construction, per ADR-2607142800's
own follow-up list) a known-good template to replicate rather than a
fresh design.
(-) Still a simulation, not a real robot-cell integration --
`quarryops.robotics` remains "policy, not control" by design, matching
`kotoba.robotics`'s own posture.
(-) Retrofitting the remaining manufacturing actors identified in
ADR-2607142800 (isic-2610 fab, isic-4211 construction) remains
follow-up work, tracked outside this ADR.

## Verification

- `cloud-itonami-isic-0810`: `clojure -M:dev:test` green (44 tests /
  204 assertions, up from 39/176), `clojure -M:lint` clean, `clojure
  -M:dev:run` demo narrative exercises both new HARD holds
  (`:robotics-simulation-missing`, `:robotics-simulation-out-of-
  tolerance`) with no exceptions.
- Commit `ba7f5ea` pushed directly to `cloud-itonami-isic-0810`'s
  `main` (matching that repo's own single-branch-direct-to-main
  convention).
