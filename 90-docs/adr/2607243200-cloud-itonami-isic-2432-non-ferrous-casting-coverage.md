# ADR-2607243200: cloud-itonami-isic-2432 (Casting of non-ferrous metals) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607152500 (cloud-itonami-isic-2431 Casting of iron and steel coverage, closest architectural analog and mirror source)

## Context

ISIC class 2432 (Casting of non-ferrous metals) is a fresh scaffold —
no prior repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-2432` before this ADR (checked and
confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-2432`
before starting). The `kotoba-lang/industry` registry entry `{:id
"2432" :name "Casting of non-ferrous metals" ...}` was verified
byte-for-byte from a fresh read via the GitHub Contents API (not
`raw.githubusercontent.com`) before any code was written (this fleet
has previously mislabeled an assigned ISIC class from memory rather
than reading the registry).

The closest architectural analog is `cloud-itonami-isic-2431` (Casting
of iron and steel): both are back-office coordination actors for a
fixed casting-foundry PLANT with heavy manufacturing equipment and a
real physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). The two verticals are, however, distinct
plants with distinct hazard and process profiles: 2431 melts and pours
iron/steel at high temperature via cupola/electric-arc/induction
furnaces into sand/permanent molds only, while 2432 melts lower-
melting-point non-ferrous alloys (aluminum, copper, zinc, brass,
bronze, magnesium) via crucible/reverberatory/induction furnaces and
routes a large share of production through high-pressure DIE-CASTING
machines — production equipment and a clamping/injection hazard 2431's
ferrous foundry does not have. 2432's non-ferrous metal-fume exposure
(zinc/brass/bronze vapor — "metal fume fever") is also a distinct
occupational-health profile from 2431's cast-iron/cast-steel
particulate and radiant-heat profile. This build mirrors 2431's
architecture closely (module-for-module: `nonferrousmfg.*` in place of
`foundrymfg.*`) but adapts the hazard profile, equipment vocabulary,
and output-form set to the non-ferrous casting plant: 2432's permanent
equipment-actuation block guards a melting FURNACE **or die-casting
machine** (`:actuate-furnace?`, kept as the same field name as 2431 to
preserve the governor-rule shape, documented to cover both); 2432's
production-batch record declares an `:alloy-grade` spanning aluminum/
copper/zinc/magnesium alloy families (10 grades across four families,
per ISIC 2432's own combined non-ferrous scope) rather than 2431's
cast-iron/cast-steel families; and 2432's `valid-output-forms` set
adds `:die-cast` (absent from 2431's set) alongside the shared
`:sand-cast`/`:permanent-mold-cast`/`:investment-cast`/
`:centrifugal-cast` forms.

This vertical has NO pre-existing `kotoba-lang/nonferrousmfg`-style
capability library to wrap (verified: `gh api search/repositories -f
q=nonferrousmfg` returns zero results). This build therefore uses
self-contained domain logic — pure functions in `nonferrousmfg.registry`
(equipment/batch verification, shipment-weight recompute, alloy-grade
validation, defect-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-2431`'s `foundrymfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:nonferrous-foundry-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh api "search/code?q=nonferrous-foundry-plant-
operations-governor"` returned `"total_count":0`, zero hits before
this repo was created).

## Decision

Build `cloud-itonami-isic-2432` from scratch as a governed-actor
implementation of the non-ferrous metal casting foundry blueprint,
following the langgraph StateGraph + independent Governor + Phase 0->3
rollout architecture established across the fleet:

1. **NonFerrousFoundryAdvisor** (`nonferrousmfg.advisor`, sealed
   intelligence node): proposes plant-operations coordination actions
   only, never commits
   - `:log-production-batch` — alloy-grade (aluminum/copper/zinc/brass/bronze/magnesium)/weight/defect-rate data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — furnace/mold/shakeout/die-casting-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a molten-metal-hazard (splash/burn, furnace radiant-heat, mold/core-binder fume exposure, non-ferrous metal-fume exposure, die-casting clamping/injection hazard)/equipment-safety concern (always escalates)
   - `:coordinate-shipment` — outbound non-ferrous casting shipment coordination proposal

2. **Non-Ferrous Foundry Plant Operations Governor**
   (`nonferrousmfg.governor`, independent validation layer, never
   trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into ten concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct furnace/die-casting-machine/pouring-line-
     equipment control); `:actuate-furnace? true` on a maintenance
     schedule (directly actuating the melting furnace or die-casting
     machine) is a PERMANENT block; a shipment may not push a batch's
     own recorded shipped weight past its own logged production weight
     (independently recomputed); no double-scheduling the same
     maintenance record; no fabricated `:alloy-grade` value; no
     physically implausible `:defect-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — molten-metal
   splash/burn risk, furnace radiant-heat exposure, mold/core-binder
   fume exposure, non-ferrous metal-fume exposure, high-pressure
   die-casting clamping/injection hazard):
   - Does NOT control the melting furnace, die-casting machine, or pouring line equipment directly
   - Does NOT make plant-safety or molten-metal-safety decisions (exclusive to the human plant supervisor)
   - Does NOT actuate the melting furnace, die-casting machine, or pouring line (permanently blocked,
     not a rollout milestone still to come — see `nonferrousmfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `nonferrousmfg.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-weight-
   exceeded?`, `alloy-grade-valid?`, `defect-rate-valid?`) are
   re-verified independently by the governor, following the "ground
   truth, not self-report" discipline established by prior actors
   (most directly `cloud-itonami-isic-2431`'s `foundrymfg.registry`).

5. **Store** (`nonferrousmfg.store`): a single `MemStore` backend
   behind a `Store` protocol, tracking four entity kinds (batches,
   equipment, maintenance, shipments) plus the append-only ledger.
   Like 2431, this build does NOT ship a second Datomic-backed store —
   a second backend can be added later behind the same protocol
   without changing any caller.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver. Full module set:
   `deps.edn`, `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later),
   `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
   `CONTRIBUTING.md`, `SECURITY.md`, `docs/adr/0001-architecture.md`.
   All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-2432` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Non-ferrous metal casting foundry plant-operations back-office
coordination is now genuinely implemented and tested (not merely
scaffolded). ISIC 2432 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or furnace/die-casting-machine actuation, independently
corroborated by `nonferrousmfg.phase`'s permanent exclusion of
`:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely casting-foundry-
specific elaboration mirroring 2431's own two-entity-kind gate — this
domain has two distinct ground-truth entity kinds a proposal can
reference, and each is independently re-derived from its own permanent
record, never trusting the proposal's self-report.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 71 tests / 199 assertions
across 5 test namespaces (`nonferrousmfg.operation-test`,
`nonferrousmfg.governor-contract-test`, `nonferrousmfg.phase-test`,
`nonferrousmfg.store-contract-test`, `nonferrousmfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch systems — scope is
deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-2432` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed directly to
  `main` at `github.com/cloud-itonami/cloud-itonami-isic-2432` (no
  GitHub-generated LICENSE-only seed commit to reconcile — `gh repo
  create --source=.` pushed this repo's own commit as the initial
  commit), commit `8cc94003f80c91252f630e5b8a49bef41d7e42e8` (confirmed
  as the tip of `cloud-itonami-isic-2432`'s `main` via `gh api
  repos/cloud-itonami/cloud-itonami-isic-2432/commits/main` matching
  the local HEAD SHA).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 71 tests containing 199 assertions. 0 failures, 0 errors.`**
  — independently re-verified from a second, fully fresh clone
  (separate `kotoba-lang/{langgraph,langchain}` sibling checkouts)
  after the initial push, same result.
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-weight-exceeded,
  furnace-actuate-blocked, already-scheduled, invalid-alloy-grade,
  invalid-defect-rate), with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:nonferrous-foundry-plant-
  operations-governor` is grep-verified UNIQUE fleet-wide (`gh api
  "search/code?q=nonferrous-foundry-plant-operations-governor"`,
  `"total_count":0` before this repo was created).
- `kotoba-lang/industry` registry entry for `"2432"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2432" ...}` block (no wholesale
  regeneration; `:repo`/`:business-id` also corrected from the stale
  never-created `gftdcojp/cloud-itonami-C2432` placeholder to the real
  `cloud-itonami/cloud-itonami-isic-2432`), landed via a branch +
  GitHub API server-side merge, merge commit
  `6932c2bfc1b99241abb77ea3f25d0045f8db930c` (confirmed as the tip of
  `kotoba-lang/industry`'s `main` at the time via `gh api
  repos/kotoba-lang/industry/commits/main`). One follow-up correction
  landed on the same branch before merge: the first draft of this
  block's own comment cited ADR-2607159600, which a concurrent agent's
  own `cloud-itonami-isic-3821` promotion had already claimed (visible
  in `industry_test.clj`'s own testing-block text on `main` at the
  time) — corrected to this ADR's real ID (2607243200) before the
  branch was merged, exact-block edit only, diff-verified.
  `industry_test.clj`'s own `maturity-summary` count assertion was
  bumped to match the live-recomputed count at each point of contact
  (this file's own tail region saw heavy concurrent-fleet contention:
  four `gh api repos/kotoba-lang/industry/merges` branch-merge attempts
  409'd in a row against other agents' own concurrent count-catch-up
  edits landing on the same lines faster than each attempt could land;
  each 409 was recovered by re-fetching `origin/main`, recomputing the
  live count fresh via `(kotoba.industry/maturity-summary)`, and
  retrying — never by rebase or manual conflict-marker editing). The
  final landing switched to a direct GitHub Contents API single-file
  PUT (SHA-checked optimistic concurrency against a freshly re-fetched
  blob SHA, not a branch merge — a technique a concurrent
  `cloud-itonami-isic-2790` agent's own testing-block text on `main`
  documented as more reliable against this exact fleet's contention on
  this file's tail region), commit
  `b261e4f0f020fa3fbbc99ba4c8845e149a97b3f1`, adding this promotion's
  own testing narration block (this promotion's own `:implemented`
  count contribution had already been folded into the live count by
  other concurrent agents' own count-catch-up edits by the time this
  narration-only PUT landed, so the final count assertion, `314`, was
  left unchanged by this specific edit). Post-merge re-verification
  from a brand-new fresh clone (plus a fresh `technology` sibling
  clone) reproduced a green result
  (`Ran 15 tests containing 988 assertions. 0 failures, 0 errors.`),
  confirmed zero UTF-8 mojibake (`grep -c "â"
  resources/kotoba/industry/registry.edn` = 0), confirmed
  `(kotoba.industry/maturity "2432")` returns `:implemented`, and
  confirmed `(kotoba.industry/maturity-summary)` returns `{:total 648,
  :spec 309, :blueprint 25, :implemented 314}` — matching the test
  file's own pinned assertion at that point in time.
