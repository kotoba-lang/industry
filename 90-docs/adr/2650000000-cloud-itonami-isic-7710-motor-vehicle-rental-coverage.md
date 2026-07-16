# ADR-2650000000: cloud-itonami-isic-7710 (Renting and leasing of motor vehicles) motor-vehicle-rental-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
wave plan), ADR-2607152500 (Wave 4 rollout-start amendment to
ADR-2607121000's P3->P4 sequencing gate, defining the person-facing-
service safety guardrail this actor is built under), ADR-2607121200
(Wave 0 cljs-first safety kernel), skill `build-actor` (advisor/
governor/StateGraph/audit-ledger actor pattern). Mirrors
`cloud-itonami-isic-7722` (Renting of video tapes and disks), whose
`vidrentalops.*` module shape this ADR follows module-for-module as
`vehiclerentalops.*`.

## Context

ISIC Rev.4 class 7710 covers renting and leasing of motor vehicles --
self-drive rental of cars, vans and light trucks to consumers and
businesses (checkout/return desk operations, fleet-availability/
maintenance scheduling, vehicle-safety-concern flagging, fleet
procurement/replacement coordination). Before scaffolding, the live
`kotoba-lang/industry` registry entry for `"7710"` was independently
verified fresh (not assumed from this task's premise) by cloning the
registry repo directly: its `:name` field read `"Renting and leasing
of motor vehicles"`, an exact match. A separate, redundant 3-digit
group registry entry `{:id "771"}` exists with the same name (`:repo
nil`, `:maturity :spec`) -- a known artifact of an earlier
data-seeding pass, left untouched, only the `"7710"` class-level
block was edited.

`gh api repos/cloud-itonami/cloud-itonami-isic-7710` confirmed a
PRE-EXISTING repo already at `:blueprint` tier (CODE_OF_CONDUCT.md/
CONTRIBUTING.md/GOVERNANCE.md/LICENSE/README.md/SECURITY.md/
blueprint.edn/docs/ only, from an earlier bulk-scaffolding pass, no
`deps.edn`/`src`/`test`) -- this work ADDED the missing `deps.edn`/
`src`/`test` module set on top of that existing boilerplate rather
than re-scaffolding (docs kept, not recreated).

A motor-vehicle-rental business touches two person-facing-service
risk dimensions squarely within ADR-2607152500's Wave 4 guardrail:
**driver-eligibility decisions** (whether a renter may be approved to
drive) and **vehicle-safety-clearance decisions** (whether a vehicle
with a known mechanical defect may be released for rental). Both are
decisions this actor must coordinate the back office around, never
make itself. Per ADR-2607152500, the closed op allowlist must never
include an op that directly finalizes a driver-eligibility override or
a vehicle-safety-clearance decision -- those are always either a hard
permanent block or an always-escalate op, never auto-commit-eligible;
any "flag a concern" op must always escalate to human sign-off, never
appearing in any phase's `:auto` set.

This fleet has independently discovered and re-fixed the same
recurring bug class across multiple Wave 4 actors: a governor's own
`scope-excluded-terms` list phrased as a bare noun (e.g. "safety",
"eligibility") can accidentally match inside the mock advisor's own
default rationale/disclaimer text for a legitimate, allowed proposal
-- self-blocking the actor on its own happy path. This is a live risk
here because `:flag-vehicle-safety-concern`'s entire purpose is to
talk *about* mechanical-defect/recall/damage concerns using exactly
those bare nouns.

## Decision

Implement `cloud-itonami-isic-7710` with Store, Advisor, Governor,
Phase, Operation, Sim (mirrors `cloud-itonami-isic-7722`'s
`vidrentalops.*` module shape module-for-module, renamed to
`vehiclerentalops.*`):

- **`vehiclerentalops.store`** -- `Store` protocol + `MemStore`.
  String-keyed `accounts` directory (`:account-id`/`:name`/
  `:registered?`/`:verified?`, representing a rental-desk/branch
  operating account), append-only `ledger`, append-only `rental-log`.
- **`vehiclerentalops.advisor`** -- `Advisor` protocol +
  `mock-advisor`. Four proposal generators, all `:effect :propose`:
  `:log-rental-record` (checkout/return/mileage/damage-note metadata
  logging), `:schedule-fleet-operation` (vehicle-availability/
  maintenance scheduling proposal), `:flag-vehicle-safety-concern`
  (mechanical-defect/recall/damage concern surfacing),
  `:coordinate-fleet-restock` (fleet procurement/replacement
  coordination).
- **`vehiclerentalops.governor`** (`VehicleRentalGovernor`) --
  independent compliance layer, HARD checks (always hold,
  un-overridable):
  1. `account-unverified` -- target rental-account/vehicle record
     must exist AND be independently `:registered?`/`:verified?` in
     the store, never trusting the proposal's own claim.
  2. `effect-not-propose` -- any `:effect` other than `:propose` is a
     claim to directly actuate/commit outside governance.
  3. `scope-excluded`/`op-not-allowed` -- ANY proposal (regardless of
     op) attempting to finalize a driver-eligibility override, or
     finalize a vehicle-safety-clearance decision, is permanently
     blocked; an op outside the closed four-op allowlist is folded
     into the same check.

  **Self-trip discipline (per this fleet's known bug class)**:
  `scope-excluded-terms` are phrased as the finalization/execution
  ACTION -- `"finalize the driver-eligibility override"`, `"finalize
  the vehicle-safety clearance"`, `"rent out the vehicle despite the
  known defect"`, Japanese equivalents (`"運転資格の可否を確定"` etc.)
  -- never as a bare noun ("eligibility", "driver", "safety",
  "clearance"). A dedicated regression test
  (`default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  in `governor_test.clj`) asserts all four default proposal
  generators, for a clean registered+verified account, never trip
  `:scope-excluded` and never HARD-hold.

  ESCALATE (always human sign-off): `:flag-vehicle-safety-concern`
  always escalates (`always-escalate-ops`), independently confirmed
  by `vehiclerentalops.phase` never including it in any phase's
  `:auto` set; low confidence (< 0.6) also escalates.
- **`vehiclerentalops.phase`** -- 0->3 rollout: phase 0 read-only;
  phase 1 `:log-rental-record` only, approval-gated; phase 2 adds
  `:schedule-fleet-operation`/`:coordinate-fleet-restock`, still
  approval-gated; phase 3 auto-commits the three non-concern ops when
  governor-clean and confident, `:flag-vehicle-safety-concern` still
  always escalates.
- **`vehiclerentalops.operation`** -- real `langgraph-clj`
  `StateGraph` (intake -> advise -> govern -> decide ->
  commit|hold|request-approval), `interrupt-before
  #{:request-approval}` for human-in-the-loop resume, mirroring
  `vidrentalops.operation` exactly (not a stub).
- **`vehiclerentalops.sim`** -- demo runner (`clojure -M:run`)
  walking the full happy path (all four ops at phase 1 then phase 3),
  the always-escalate vehicle-safety-concern flow, and every
  HARD-hold scenario (unregistered account, unverified account,
  non-`:propose` effect, out-of-scope drift).
- Tests: `store_contract_test`, `advisor_test`, `governor_test` (incl.
  the mandatory self-trip regression), `phase_test`,
  `governor_contract_test` -- 40 tests / 119 assertions, all green.
- All `.cljc` (portable, no JVM-only interop). AGPL-3.0-or-later.
  Pre-existing README/GOVERNANCE/CONTRIBUTING/SECURITY/
  CODE_OF_CONDUCT boilerplate kept as-is (from the earlier
  bulk-scaffolding pass), not recreated.

## Scope exclusions (hardcoded in governor checks, not just prose)

- Finalizing a driver-eligibility override (waiving or overriding a
  driver-eligibility check to approve a renter) -- always a hard,
  permanent block.
- Finalizing a vehicle-safety-clearance decision (renting out a
  vehicle with a known mechanical defect) -- always a hard, permanent
  block.
- Payment capture/refund, contract negotiation, and binding
  rental-agreement execution -- out of scope, not represented in the
  closed op allowlist at all.

## Consequences

(+) ISIC 7710 motor-vehicle-rental-operations-coordination is
genuinely implemented and fully tested, filling in a pre-existing
blueprint-tier repo rather than leaving it stalled.

(+) The driver-eligibility-override and vehicle-safety-clearance
exclusions are hardcoded in governor checks
(`scope-exclusion-violations`), not just asserted in README prose.

(+) The self-trip bug class this fleet has repeatedly encountered is
fixed by construction (action-phrased terms) and covered by a
dedicated regression test, not just avoided by luck.

(+) `:flag-vehicle-safety-concern` always-escalate is a two-layer
invariant (governor `always-escalate-ops` + phase's permanent absence
from every `:auto` set), matching every sibling Wave 4 actor's own
safety discipline.

(+) Portable `.cljc`, zero JVM-only constructs; `clojure -M:lint` is 0
errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up;
tests use in-memory `MemStore`, matching every sibling actor's current
maturity.

(-) `vehiclerentalops.advisor`'s `mock-advisor` is deterministic, not
a real LLM call; the `Advisor` protocol seam is ready for that swap
but it is not wired in this ADR.

## Verification

- `cloud-itonami-isic-7710`: `clojure -M:test` (pinned git/sha) ->
  "Ran 40 tests containing 119 assertions. 0 failures, 0 errors."
  `clojure -M:dev:test` (local-root override, fresh `../../kotoba-lang/
  langgraph` and `../../kotoba-lang/langchain` sibling clones) ->
  identical result. `clojure -M:lint` -> 0 errors, 0 warnings.
- Commit `87ef53c45930791b40c68ee4522068e211bff248` pushed directly to
  `cloud-itonami/cloud-itonami-isic-7710`'s `main` (fast-forward from
  the pre-existing boilerplate-only tip `7aeed35`; no divergence,
  plain push, no PR needed since this repo has no branch-protection
  gate).

## Verification addendum: registry promotion (Step 7, landed)

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`:
  `"7710"` entry promoted (previously had NO `:maturity` key at all,
  resolving to `:blueprint` only via the `(:repo industry)` fallback
  in `maturity-of`) -> `:maturity :implemented` explicitly added; also
  corrected `:business-id` from the stale, inconsistently-shaped
  `"cloud-itonami-7710"` to `"cloud-itonami-isic-7710"`, matching
  every sibling implemented entry's own business-id shape.
  `:repo`/`:required-technologies`/`:optional-technologies`/
  `:operating-states` were already correct and left unchanged. The
  separate 3-digit group entry `{:id "771"}` was confirmed untouched.
  Landed via a Contents-API single-file PUT (sha-checked optimistic
  concurrency, content freshly re-fetched immediately before the PUT
  per this fleet's hot-contention discipline, exact-block edit
  verified via an exact old/new substring occurrence count PLUS
  sample-verified against `7722`/`7730`/`771`-group entries also
  intact, no mojibake detected, landed on the first attempt), commit
  `68d5b3f587d92d0898ca5291ee5c9be13fdc3c80`.
- `test/kotoba/industry_test.clj`: this file and `registry.edn` are
  both extremely hot, high-concurrency shared files across the fleet
  -- during this promotion's own edit window, five separate concurrent
  sibling promotions (`cloud-itonami-isic-5610`, `-6010`, `-7729`, and
  two earlier ones) landed on `registry.edn`, moving the live
  `:blueprint`/`:implemented` split from `12`/`405` (this promotion's
  own baseline read) to `6`/`411` by the time this test-file edit
  landed -- each intermediate delta was live-recomputed via a
  brand-new fresh clone's `(kotoba.industry/maturity-summary)`
  immediately before finalizing the edit, never assumed from a stale
  buffer. Added a dedicated corroboration `testing` block for `"7710"`
  (replacing a generic sibling-added placeholder that had already
  fixed the stale spot-check ahead of this edit landing); also
  mechanically fixed two OTHER entries' stale `:blueprint` spot-checks
  that were independently found contradicting the live registry during
  this same edit window (`cloud-itonami-isic-6010`, `cloud-itonami-
  isic-7729`, both promoted by concurrent sibling agents, not this
  promotion's own work -- corroborated only to keep the shared suite
  green, their own registry.edn data untouched by this promotion).
  Landed via a Contents-API single-file PUT on the first attempt
  (sha-checked optimistic concurrency, content freshly re-fetched
  immediately before the PUT), commit
  `67f63c69a1a3b55458a1385aa13b9d2363e76a56`. The pinned
  `:implemented`/`:blueprint` count assertions were bumped to `411`/`6`
  respectively, recomputed live via a brand-new fresh clone's
  `(kotoba.industry/maturity-summary)` immediately before the PUT, not
  assumed.
- Post-merge re-verification from a brand-new fresh clone of
  `kotoba-lang/industry` `main` (plus a fresh `../technology` sibling
  clone): `clojure -M:test` -> "Ran 15 tests containing 1063
  assertions. 0 failures, 0 errors." `clojure -M:lint` -> 0 errors, 0
  warnings. No mojibake detected in `registry.edn` (zero U+FFFD
  replacement characters). `"7710"`'s own entry independently
  re-confirmed `:implemented` with the correct `:repo`/`:business-id`,
  the separate `"771"` group entry re-confirmed untouched at `:spec`,
  and sample entries `7722`/`7730`/`6310` also confirmed intact. Live
  `(kotoba.industry/maturity-summary)` at this final re-verification:
  `{:total 649, :spec 232, :blueprint 6, :implemented 411}`.
