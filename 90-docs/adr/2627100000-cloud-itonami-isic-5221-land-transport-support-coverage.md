# ADR-2627100000: cloud-itonami-isic-5221 (service activities incidental to land transportation) land-transport-support-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
wave plan), ADR-2607152500 (Wave 4 rollout-start amendment), ADR-2607121200
(Wave 0 cljs-first safety kernel), skill `build-actor` (advisor/governor/
StateGraph/audit-ledger actor pattern). Mirrors `cloud-itonami-isic-3512`
(Community Renewable Energy Operations), a verified actor whose module
structure this ADR follows module-for-module.

## Context

ISIC Rev.5 class 5221 covers service activities incidental to land
transportation: toll-road/highway operation, bus-terminal operation, and
related infrastructure-support services -- infrastructure SUPPORT, not the
carriage of passengers or freight itself (that is `cloud-itonami-isic-4911`/
`4912`/`4920`) and not third-party inspection consulting. The live
`kotoba-lang/industry` registry entry for `"5221"` was independently
verified fresh (cloned from scratch) before any work began: its `:name`
field read exactly `"Service activities incidental to land transportation"`
-- unambiguous, matching the task's premise, and distinct from siblings
`5222` [water], `5223` [air], `5224` [cargo handling], `5229` [other], each
its own registry entry.

**This build's premise differs from most entries in this ADR series**:
`gh api repos/cloud-itonami/cloud-itonami-isic-5221` confirmed a
PRE-EXISTING repo, not a 404. It was already at `:blueprint` tier
(`CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`GOVERNANCE.md`/`LICENSE`/
`README.md`/`SECURITY.md`/`blueprint.edn`/`docs/` only -- no `deps.edn`,
`src`, or `test`) from an earlier bulk-scaffolding pass. This work ADDED
the missing implementation on top of that existing boilerplate rather than
re-scaffolding a fresh repo; the pre-existing docs (`README.md`,
`docs/business-model.md`, `docs/operator-guide.md`, `blueprint.edn`) were
kept as-is, with a new "Implementation (R0)" section appended to `README.md`
describing the actual actor module underneath.

The two sibling actors named as the reference for this build
(`cloud-itonami-isic-4911`/`4912`, passenger/freight rail) were, on direct
inspection, ALSO still blueprint-tier-only at build time (no `deps.edn`/
`src`/`test` in either repo) -- their own build-out apparently had not yet
landed in this batch. Rather than block on that, this build instead
mirrored `cloud-itonami-isic-3512`'s verified `energy.*` module shape
module-for-module, a repo independently confirmed fully implemented and
green before use as a template.

Toll-road/bridge/tunnel/parking-facility structural-safety and
traffic-safety decisions warrant the standard governor pattern even though
this is an infrastructure-SUPPORT actor, not a carrier or a structural-
safety authority: any op finalizing an infrastructure-safety-clearance
decision must be a hard, permanent block or an always-escalate op, never
auto-commit-eligible, and any "flag a concern" op must always escalate to
human sign-off, never appearing in any phase's `:auto` set.

This batch's sibling agents have independently discovered and fixed the
same recurring bug class across several actors in this fleet: a governor's
`scope-exclusion`/`scope-excluded-terms` list phrased as a bare noun (e.g.
"safety", "structural") can accidentally match inside the mock advisor's
own default rationale/disclaimer text for a legitimate, allowed proposal --
self-blocking the actor on its own happy path. This is a live risk here
because `:flag-structural-safety-concern`'s entire purpose is to talk
*about* a structural/traffic-safety concern using exactly the bare-ish
words a naive term list would ban.

## Decision

Fill in `cloud-itonami-isic-5221` (pre-existing blueprint repo) with Store,
Advisor, Governor, Phase, Operation, Sim (mirrors `cloud-itonami-isic-3512`'s
`energy.*` module shape module-for-module, renamed to `landsupport.*`):

- **`landsupport.store`** -- `Store` protocol + `MemStore` + `DatomicStore`
  (`langchain.db`-backed, same contract). String-keyed `facilities`
  directory (`:id`/`:facility-name`/`:facility-type`/`:registered?`/
  `:verified?`/`:structural-safety-concern-unresolved?`/`:jurisdiction`),
  append-only `ledger`, plus a dedicated append-only log per op
  (facility-record / maintenance-schedule / concern / supply-order).
- **`landsupport.advisor`** -- `Advisor` protocol + `mock-advisor` (+
  `llm-advisor` seam). Four proposal generators, ALL `:effect :propose`:
  `:log-facility-record` (toll-transaction/parking-occupancy/facility-usage
  data logging), `:schedule-facility-maintenance` (road/bridge/facility
  maintenance-scheduling proposal), `:flag-structural-safety-concern`
  (surfaces a structural-defect/traffic-hazard concern for human review),
  `:coordinate-supply-order` (maintenance-materials procurement proposal).
- **`landsupport.governor`** (Land Transport Support Governor) --
  independent compliance layer, FOUR HARD checks (always hold,
  un-overridable):
  1. `facility-unverified` -- target facility record must exist AND be
     independently `:registered?`/`:verified?` in the store (by an
     EXTERNAL permit/registration authority this actor never writes to),
     never trusting the proposal's own claim.
  2. `op-not-allowed` -- `:op` must be one of the four ops above; anything
     else is rejected outright.
  3. `effect-not-propose` -- this actor NEVER actuates; any `:effect`
     other than `:propose` is rejected outright.
  4. `structural-safety-clearance-finalization` -- ANY proposal (regardless
     of which op it claims to be) whose text reads as finalizing/
     certifying a structural-safety clearance is a HARD, permanent block.

  **Self-trip discipline (the fix for this fleet's known bug class)**: the
  finalization-phrase term list (`landsupport.governor/finalization-
  phrases`) is phrased as the finalization/execution ACTION -- `"finalize
  the structural-safety clearance"`, `"certify ... structurally safe"`,
  `"issue [a] structural safety clearance"`, `"構造安全性クリアランスを確定"` --
  never as a bare noun ("safety", "structural" alone). A dedicated
  regression test suite
  (`default-mock-advisor-proposals-never-self-trip-scope-exclusion` and
  `flag-structural-safety-concern-specifically-never-self-trips` in
  `test/landsupport/scope_exclusion_test.clj`) asserts all four default
  proposal generators, for a clean registered+verified facility, never
  trip `:structural-safety-clearance-finalization` and never HARD-hold.

  ESCALATE (always human sign-off): `:flag-structural-safety-concern`
  always escalates (`always-escalate-ops`), independently confirmed by
  `landsupport.phase` never including it in any phase's `:auto` set;
  `:coordinate-supply-order` escalates when its `:cost-estimate` exceeds
  `high-cost-threshold` ($50000); confidence below 0.6 also escalates
  regardless of op.
- **`landsupport.phase`** -- 0->3 rollout: phase 0 read-only; phase 1
  `:log-facility-record` only, approval-gated; phase 2 adds
  `:schedule-facility-maintenance`/`:coordinate-supply-order`, still
  approval-gated; phase 3 auto-commits `:log-facility-record` always, and
  `:coordinate-supply-order` only when the governor's own high-cost gate
  has already cleared it (two independent layers, deliberately).
  `:flag-structural-safety-concern` is NEVER a member of any phase's
  `:auto` set.
- **`landsupport.operation`** -- real `langgraph-clj` `StateGraph` (intake
  -> advise -> govern -> decide -> commit|hold|request-approval),
  `interrupt-before #{:request-approval}` for human-in-the-loop resume,
  mirroring `energy.operation` exactly (not a stub).
- **`landsupport.sim`** -- demo runner (`clojure -M:dev:run`) walking the
  full happy path (auto-commits + escalate-then-approve flows) and every
  HARD-hold scenario (unregistered facility, unverified facility,
  out-of-allowlist op via a dedicated test, a proposal that words its way
  into finalizing a structural-safety clearance).
- Tests: `governor_test`, `scope_exclusion_test` (dedicated self-trip
  regression, mandatory per this build's own requirements), `phase_test`,
  `store_contract_test`, `operation_test` -- 40 tests / 138 assertions, all
  green.
- All `.cljc` (portable, no JVM-only interop). AGPL-3.0-or-later.
  Pre-existing `README.md`/`GOVERNANCE.md`/`CONTRIBUTING.md`/`SECURITY.md`/
  `CODE_OF_CONDUCT.md`/`blueprint.edn` kept as-is; `README.md` extended with
  an "Implementation (R0)" section.

## Scope exclusions (hardcoded in governor checks, not just prose)

- Finalizing/certifying a structural-safety clearance (e.g. certifying a
  bridge, tunnel, or toll-gantry structurally safe after a reported
  concern) -- always a hard, permanent block, applied regardless of which
  op a proposal claims to be. There is also no op in the closed
  four-op allowlist that itself performs this finalization -- defense in
  depth, not a single point of failure.
- Direct toll-lane dispatch or facility-equipment operation -- out of
  scope, not represented in the closed op allowlist at all (this is an
  operations-COORDINATION actor, not a facility-equipment controller).
- Surfacing a structural-safety concern (`:flag-structural-safety-concern`)
  always escalates to a human structural-safety engineer; this actor never
  resolves, dismisses, or auto-commits a concern flag itself.

## Consequences

(+) ISIC 5221 land-transport-support-operations-coordination is genuinely
implemented and fully tested, filling in a pre-existing blueprint-tier repo
that had sat unimplemented since an earlier bulk-scaffolding pass, rather
than leaving it stalled.

(+) The structural-safety-clearance-finalization exclusion is hardcoded in
a governor check (`structural-safety-clearance-finalization-violations`),
not just asserted in README prose, and applies regardless of which op a
proposal claims to be.

(+) The self-trip bug class this fleet has repeatedly encountered is fixed
by construction (action-phrased terms) and covered by a dedicated
regression test, not just avoided by luck.

(+) `:flag-structural-safety-concern` always-escalate is a two-layer
invariant (governor `always-escalate-ops` + phase's permanent absence from
every `:auto` set), matching this fleet's own established safety
discipline.

(+) Portable `.cljc`, zero JVM-only constructs; `clojure -M:lint` is 0
errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up in
practice; tests use in-memory `MemStore` as the default. A `DatomicStore`
(`langchain.db`-backed) implementation exists and passes the identical
`store_contract_test.clj` contract, matching every sibling actor's current
maturity, but is not the wired default.

(-) `landsupport.advisor`'s `mock-advisor` is deterministic, not a real LLM
call; the `Advisor` protocol seam is ready for that swap but it is not
wired in this ADR.

(-) The two reference actors named for this build (`cloud-itonami-isic-4911`/
`4912`) turned out to also still be blueprint-tier-only at build time --
this is a scope note for whichever agent eventually fills those in
themselves, not a blocker encountered here.

## Verification

- `cloud-itonami-isic-5221`: `clojure -M:test` -> "Ran 40 tests containing
  138 assertions. 0 failures, 0 errors." `clojure -M:lint` -> 0 errors, 0
  warnings. `clojure -M:dev:run` demo runs end-to-end: `:log-facility-record`
  and a low-cost `:coordinate-supply-order` auto-commit at phase 3;
  `:schedule-facility-maintenance` and a high-cost `:coordinate-supply-order`
  escalate then commit after approval; `:flag-structural-safety-concern`
  always escalates then commits after approval, setting the facility's
  `:structural-safety-concern-unresolved?` flag; an unregistered facility,
  a registered-but-unverified facility, an out-of-allowlist op, and a
  structural-safety-clearance-finalization attempt all HARD-hold, never
  reaching a human. Independently re-verified from a brand-new fresh clone
  (with fresh `kotoba-lang/langgraph` + `kotoba-lang/langchain` siblings):
  identical "Ran 40 tests containing 138 assertions. 0 failures, 0 errors."
- Commit `a51bf77692db16fd85cb1102b62e7843dc26467c` pushed directly to
  `cloud-itonami/cloud-itonami-isic-5221`'s `main` (added onto the
  pre-existing single boilerplate commit `e8a98bc`).

## Verification addendum: registry promotion (Step 7, landed)

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`: `"5221"`
  entry promoted -- it had NO `:maturity` key before this edit (resolving
  to `:blueprint` only via the `(:repo industry)` fallback in
  `kotoba.industry/maturity-of`), so this edit ADDS `:maturity :implemented`
  explicitly. `:repo`/`:business-id` were independently confirmed already
  correct and left unchanged. `:required-technologies` trimmed from the
  stale 7-item placeholder `[:robotics :identity :forms :dmn :bpmn
  :audit-ledger :logistics]` to the coordination-only shape actually
  implemented `[:identity :forms :dmn :bpmn :audit-ledger]` (matching
  sibling `cloud-itonami-isic-3512`'s own `:required-technologies`).
  `:operating-states` updated from the stale `[:intake :book :transit
  :deliver :reconcile :audit]` placeholder to match the actor state machine
  `[:intake :advise :govern :approve :commit :audit]`. Landed via a
  Contents-API single-file PUT (sha-checked optimistic concurrency,
  immediately re-fetched fresh content before the PUT, exact-block edit
  verified via a prefix/suffix scan showing only the intended region
  changed), commit `8d567dbb542f465decd08e6078543c412f1c3b01`, landed on
  the first attempt.
- `test/kotoba/industry_test.clj`: dedicated corroboration `testing` block
  added for `"5221"`, and the pinned `:implemented`-count assertion bumped.
  This edit ALSO fixed a pre-existing hardcoded assertion in this same file
  that locked `"5221"` to `:blueprint` (now corrected to `:implemented`).
  Mid-edit, a SEPARATE, unrelated hardcoded assertion for
  `cloud-itonami-isic-4911` started failing against live registry state --
  a concurrent sibling fleet agent had independently promoted `4911`
  `:blueprint` -> `:implemented` in the same window, confirmed directly via
  `(kotoba.industry/maturity "4911")` => `:implemented` on a freshly
  re-fetched `origin/main`. That is NOT this ADR's own work; it is
  corroborated here (assertion corrected, not narrated in full) only to
  keep this shared, extremely hot test file truthful -- attribution and
  full narration of `4911`'s own promotion is that sibling agent's own
  responsibility in its own ADR. The `:implemented`-count assertion was
  live-recomputed via `(kotoba.industry/maturity-summary)` immediately
  before the edit (not assumed): `:blueprint` 24 -> 22 (this promotion's
  own `5221` -1, plus `4911`'s concurrent -1), `:implemented` 393 -> 395,
  `:spec` flat at 232 -- internally consistent with exactly these two
  `:blueprint`-tier entries being promoted. Landed via a Contents-API
  single-file PUT (sha-checked, freshly re-fetched immediately before),
  commit `077a0ac8c866139b03ed3a0ba9bdbc2c90b0e770`, landed on the first
  attempt after a local re-verification run against the freshly-fetched
  buffer caught and fixed a Python string-escaping bug (unescaped internal
  quotes from a non-raw string literal) before any push was attempted.
- Post-merge re-verification from a brand-new fresh clone of
  `kotoba-lang/industry` `main` (plus a fresh `../technology` sibling
  clone): `clojure -M:test` -> "Ran 15 tests containing 1058 assertions. 0
  failures, 0 errors." `clojure -M:lint` -> 0 errors, 0 warnings. No
  mojibake detected in `registry.edn`. `"5221"`'s own entry, and sample
  sibling entries `5222`/`5223`/`7740`/`3512`/`4911`, all independently
  re-confirmed intact.
