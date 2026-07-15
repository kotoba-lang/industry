# ADR-2607159600: cloud-itonami-isic-3821 (Treatment and disposal of non-hazardous waste) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (ISIC Wave operations-coordination pattern), `cloud-itonami-isic-3700` (Sewerage, back-office regulated-utility coordination pattern this build mirrors module-for-module), `cloud-itonami-isic-3822` (Treatment and disposal of HAZARDOUS waste, sibling class with an inverted hazard-gate governor), `cloud-itonami-isic-3830` (materials-recovery/recycling, a separate vertical)

## Context

ISIC class 3821 (Treatment and disposal of non-hazardous waste)
covers landfill, sorting-and-materials-recovery, composting, and
incineration-without-hazmat facility operations for non-hazardous
waste streams — distinct from sibling class 3822 (Treatment and
disposal of hazardous waste, already `:implemented`, inverted
hazard-gate governor) and from class 3830 (materials-recovery/
recycling, its own `:traceability-governor` vertical). The
`kotoba-lang/industry` registry entry for `"3821"` pointed at a
never-created placeholder repo (`https://github.com/gftdcojp/
cloud-itonami-E3821`, `:business-id "cloud-itonami-E3821"`) and
carried `:required-technologies [:robotics ... :telemetry]`, wrongly
implying a physical-actuation/robotics scope. This ADR and the
underlying implementation build the real actor from scratch and
correct the registry entry to match.

Before any work began, the live registry entry was independently
re-verified against a fresh clone of `kotoba-lang/industry`
(`{:id "3821" :name "Treatment and disposal of non-hazardous waste"}`)
to rule out this fleet's known ID/name-mismatch failure mode — no
mismatch found, distinct from sibling `"3822"` (`"Treatment and
disposal of hazardous waste"`). No prior `cloud-itonami-isic-3821`
repo existed on GitHub (`gh api repos/cloud-itonami/
cloud-itonami-isic-3821` returned 404 before starting) — this is a
fresh scaffold, not a redo of a broken prior attempt.

## Decision

Build `cloud-itonami-isic-3821` as a governed-actor implementation of
the non-hazardous-waste treatment/disposal operations blueprint,
following the langgraph StateGraph + independent Governor + Phase
0->3 rollout architecture established across the fleet, mirroring
`cloud-itonami-isic-3700`'s (Sewerage) back-office-coordination
discipline module-for-module (`sewerops.*` -> `wasteops.*`,
system-record -> facility-record, `:order-supplies` ->
`:coordinate-shipment`):

1. **WasteOpsAdvisor** (`wasteops.advisor`, sealed intelligence node):
   proposes coordination actions only, never commits
   - `:log-facility-record` — intake-volume/sorting-yield/disposal-method data logging
   - `:schedule-maintenance` — sorting/incineration/composting-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface an environmental-contamination/fire-hazard/emissions-exceedance concern (always escalates)
   - `:coordinate-shipment` — outbound recovered-material/residual-waste shipment coordination proposal

2. **WasteTreatmentOpsGovernor** (`wasteops.governor`, independent
   validation layer, never trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally): target
     waste-treatment/disposal facility record (the facility that owns
     every batch it processes) must exist AND be independently
     `:registered?`/`:verified?` in the store before any proposal for
     it may commit or even escalate; `:effect` must always be
     `:propose`; any proposal (regardless of op) outside the closed
     four-op allowlist, or whose op/summary/rationale/citations/value
     touches sorting/incineration-equipment control (direct
     actuation) or an environmental-permit-authority decision
     (environmental-permit issuance, disposal-permit issuance,
     environmental-compliance determination) is a permanent,
     un-overridable block
   - ESCALATE (human sign-off, always, when the governor is otherwise
     clean): `:flag-safety-concern` always escalates regardless of
     confidence; low advisor confidence (`< 0.6`). Unlike 3700, this
     governor carries no cost-threshold escalation gate (no
     `:order-supplies`-style `:estimated-cost` field on
     `:coordinate-shipment`) — a deliberate simplification of this
     build's own governor scope, not an omission (this actor's task
     brief specifies only the safety-concern and low-confidence
     escalate triggers).

3. **Scope boundary**:
   - Does NOT directly control sorting/incineration equipment (actuation)
   - Does NOT make an environmental-permit-authority decision
     (environmental-permit issuance, disposal-permit issuance,
     environmental-compliance determination) — exclusively human/
     authority territory
   - All proposals are `:effect :propose`; actuation is human-
     approval-gated

4. **Rollout phases** (`wasteops.phase`): Phase 0 (read-only) -> 1
   (facility-record logging, approval-gated) -> 2 (adds maintenance
   scheduling + shipment coordination, approval-gated) -> 3
   (supervised auto: facility-record/maintenance/shipment-coordination
   may auto-commit when governor-clean and confident).
   `:flag-safety-concern` is deliberately absent from every phase's
   `:auto` set, at any phase — a permanent structural fact, matching
   `wasteops.governor`'s own `always-escalate-ops` independently (two
   layers, not one).

5. **Store** (`wasteops.store`): a single `MemStore` backend behind a
   `Store` protocol, seeded with three demo facilities (a landfill and
   a sorting facility both registered+verified, an incineration
   facility registered-but-unverified) covering both the happy path
   and every governor HARD check.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver (`wasteops.sim`). Full
   module set: `deps.edn`, `blueprint.edn`, `LICENSE`
   (AGPL-3.0-or-later), `README.md`, `GOVERNANCE.md`,
   `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `SECURITY.md`. All source
   pushed to `github.com/cloud-itonami/cloud-itonami-isic-3821`
   (public OSS, AGPL-3.0-or-later).

## Consequences

(+) Non-hazardous-waste treatment/disposal facility back-office
operations coordination is now genuinely implemented and tested (not
merely scaffolded). ISIC 3821 moves from a broken `:spec`-tier
placeholder entry to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized sorting/
incineration-equipment actuation or environmental-permit-authority
decisions, contract-tested end-to-end through the full langgraph
StateGraph, not merely unit-tested against hand-built proposals.

(+) The registry entry's `:repo`/`:business-id` are corrected from a
stale, never-created `gftdcojp/cloud-itonami-E3821` placeholder to the
real, verified, pushed repo, and `:required-technologies` is corrected
to drop `:robotics`/`:telemetry` (this is a non-robotics back-office
coordination actor, matching `blueprint.edn`'s
`:itonami.blueprint/robotics false`).

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) Governor keyword `:waste-treatment-ops-governor` is grep-verified
distinct from sibling `"3822"`'s own `:hazardous-waste-governor`
(inverted hazard-gate) and `"3830"`'s own `:traceability-governor` —
no cross-actor keyword collision.

(-) Still a simulation/proposal layer, not integrated with real
weighbridge/SCADA/environmental-monitoring telemetry systems — scope
is deliberately bounded to back-office coordination.

(-) No cost-threshold escalation gate on `:coordinate-shipment`
(unlike 3700's `:order-supplies`) — a deliberate scope simplification
per this actor's task brief, not a parity gap with the reference
pattern's every feature.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-
backed store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-3821` repo: full module set (advisor/governor/
  operation/phase/sim/store + deps.edn + blueprint.edn + LICENSE +
  governance docs) built and pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-3821`, commit
  `390d42f2a26c392ef3e37ade6d855972ec09642a` (fresh repo, first
  commit — `git merge-base --is-ancestor` confirmed it landed on
  `origin/main` immediately after push).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  `langgraph` via a `:git/sha` in top-level `:deps`):
  **`Ran 45 tests containing 135 assertions. 0 failures, 0 errors.`**
  across 5 test namespaces (`wasteops.advisor-test`,
  `wasteops.governor-test`, `wasteops.governor-contract-test`,
  `wasteops.phase-test`, `wasteops.store-contract-test`). Identical
  result under `:dev:test` (local monorepo `:local/root` override).
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:run` demo narrative exercises all four ops at phase 1
  and phase 3, the `:flag-safety-concern` always-escalate case, and
  every HARD-hold scenario directly (unregistered facility,
  registered-but-unverified facility, non-`:propose` `:effect`,
  sorting/incineration-equipment-control scope drift), with no
  exceptions — output independently inspected (40 commit dispositions,
  6 escalate, 36 hold across the full demo run; audit ledger + committed
  coordination log both match the expected disposition per scenario).
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested,
  `every-decision-leaves-one-ledger-fact`).
- Post-push re-verification from an INDEPENDENT fresh clone of
  `cloud-itonami-isic-3821` (no `:dev` alias): `clojure -M:test` ->
  **`Ran 45 tests containing 135 assertions. 0 failures, 0 errors.`**
- `kotoba-lang/industry` registry entry for `"3821"` updated in place
  via an exact-text in-place edit of the single `{:id "3821" ...}`
  block (no wholesale regeneration): `:repo`/`:business-id` corrected,
  `:required-technologies` corrected (drops `:robotics`/`:telemetry`),
  `:maturity :spec` -> `:implemented`. Landed via a feature branch +
  server-side merge (`gh api repos/kotoba-lang/industry/merges`) on
  the first attempt (no 409 contention) at merge commit
  `7876227b02a303c4cbdf9e93f1677d2e28b58d0b`.
- The true fleet-wide `:implemented` count (computed via
  `kotoba.industry/maturity-summary`, not raw grep) was live-
  recomputed against a freshly re-cloned `origin/main` immediately
  before the registry edit: 307 -> 308 after promoting `"3821"`. The
  `industry_test.clj` `maturity-summary-counts-tiers` assertion was
  bumped from a stale `304` to the live-recomputed `308` accordingly
  (not an assumed fixed number); a corroboration `testing` block for
  `"3821"` was added alongside it.
  `clojure -M:test` on the industry repo before pushing:
  **`Ran 15 tests containing 983 assertions. 0 failures, 0 errors.`**
- Post-merge re-verification from an INDEPENDENT fresh clone of
  `kotoba-lang/industry` at merge commit
  `7876227b02a303c4cbdf9e93f1677d2e28b58d0b` (plus a fresh
  `kotoba-lang/technology` sibling clone): `clojure -M:test` ->
  **`Ran 15 tests containing 983 assertions. 0 failures, 0 errors.`**
  `grep -c "â" resources/kotoba/industry/registry.edn` = 0 (no
  file-wide UTF-8 mojibake). The `"3821"` block was independently
  re-read from this fresh clone and confirmed intact
  (`:maturity :implemented`, correct `:repo`/`:business-id`, no other
  entry touched).
