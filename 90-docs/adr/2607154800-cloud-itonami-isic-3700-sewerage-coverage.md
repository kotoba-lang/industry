# ADR-2607154800: cloud-itonami-isic-3700 (Sewerage) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (ISIC Wave 3 operations-coordination pattern), `cloud-itonami-isic-0510` (Mining of hard coal, closest domain analog for a non-robotics back-office coordination actor)

## Context

ISIC class 3700 (Sewerage) covers operation of sewer systems/networks
and sewage (wastewater) treatment plants, and related collection/
cleaning/maintenance activities. The `kotoba-lang/industry` registry
entry for `"3700"` pointed at a never-created placeholder repo
(`https://github.com/gftdcojp/cloud-itonami-E3700`, `:business-id
"cloud-itonami-E3700"`) and carried `:required-technologies
[:robotics ... :telemetry]`, wrongly implying a physical-actuation/
robotics scope. This ADR and the underlying implementation build the
real actor from scratch and correct the registry entry to match.

No prior `cloud-itonami-isic-3700` repo existed on GitHub (confirmed
via `gh repo view` before starting) — this is a fresh scaffold, not a
redo of a broken prior attempt.

## Decision

Build `cloud-itonami-isic-3700` as a governed-actor implementation of
the sewerage-operations blueprint, following the langgraph StateGraph
+ independent Governor + Phase 0->3 rollout architecture established
across the fleet, mirroring `cloud-itonami-isic-0510`'s back-office-
coordination discipline (coalops.* -> sewerops.*, site -> facility):

1. **SewerOpsAdvisor** (`sewerops.advisor`, sealed intelligence node):
   proposes coordination actions only, never commits
   - `:log-system-record` — flow-rate/inspection/maintenance data logging
   - `:schedule-maintenance` — pipe-inspection/pump-maintenance/cleaning scheduling proposal
   - `:flag-safety-concern` — surface an overflow/contamination/public-health concern (always escalates)
   - `:order-supplies` — equipment/chemical-treatment procurement proposal

2. **SewerageOpsGovernor** (`sewerops.governor`, independent
   validation layer, never trusts the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally): target
     sewer-system-segment/treatment-facility record must exist AND be
     independently `:registered?`/`:verified?` in the store before any
     proposal for it may commit or even escalate; `:effect` must
     always be `:propose`; any proposal (regardless of op) outside the
     closed four-op allowlist, or whose op/summary/rationale/citations/
     value touches pump/valve-equipment-control (direct actuation) or
     a public-health-authority discharge decision (discharge-permit
     issuance, boil-water-notice issuance, effluent-discharge
     authorization) is a permanent, un-overridable block
   - ESCALATE (human sign-off, always, when the governor is otherwise
     clean): `:flag-safety-concern` always escalates regardless of
     confidence; a genuinely NEW governor rule for this actor — an
     `:order-supplies` proposal whose draft `:value` carries an
     `:estimated-cost` above `sewerops.governor/supply-cost-threshold`
     (5000, currency-unit-agnostic) always escalates for human budget
     sign-off, REGARDLESS of rollout phase (a high-value order that
     would otherwise auto-commit at phase 3 still escalates once its
     cost clears the threshold — the phase gate only adds caution,
     never removes a governor escalation); low advisor confidence
     (`< 0.6`)

3. **Scope boundary**:
   - Does NOT directly control pump/valve equipment (actuation)
   - Does NOT make a public-health-authority discharge decision
     (discharge-permit issuance, boil-water-notice issuance,
     effluent-discharge authorization) — exclusively human/authority
     territory
   - All proposals are `:effect :propose`; actuation is human-
     approval-gated

4. **Rollout phases** (`sewerops.phase`): Phase 0 (read-only) -> 1
   (system-record logging, approval-gated) -> 2 (adds maintenance
   scheduling + supply ordering, approval-gated) -> 3 (supervised
   auto: system-record/maintenance/supply-order may auto-commit when
   governor-clean and confident). `:flag-safety-concern` is
   deliberately absent from every phase's `:auto` set, at any phase —
   a permanent structural fact, matching `sewerops.governor`'s own
   `always-escalate-ops` independently (two layers, not one).

5. **Store** (`sewerops.store`): a single `MemStore` backend behind a
   `Store` protocol, seeded with three demo facilities (two
   registered+verified, one registered-but-unverified) covering both
   the happy path and every governor HARD check.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver (`sewerops.sim`). Full
   module set: `deps.edn`, `blueprint.edn`, `LICENSE`
   (AGPL-3.0-or-later), `README.md`, `GOVERNANCE.md`,
   `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `SECURITY.md`. All source
   pushed to `github.com/cloud-itonami/cloud-itonami-isic-3700`
   (public OSS, AGPL-3.0-or-later).

## Consequences

(+) Sewerage back-office operations coordination is now genuinely
implemented and tested (not merely scaffolded). ISIC 3700 moves from
a broken `:spec`-tier placeholder entry to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized pump/valve
actuation or public-health-authority discharge decisions,
contract-tested end-to-end through the full langgraph StateGraph, not
merely unit-tested against hand-built proposals.

(+) The supply-cost-threshold escalate gate
(`sewerops.governor/high-cost-supply-order?`) is a genuinely new
governor rule beyond a straight port of 0510's shape: it independently
re-derives whether an `:order-supplies` proposal's own claimed
`:estimated-cost` clears a threshold, and forces human budget sign-off
regardless of rollout phase — exercised end-to-end via
`high-cost-supply-order-escalates-even-at-phase-3` in
`governor_contract_test.clj`.

(+) The registry entry's `:repo`/`:business-id` are corrected from a
stale, never-created `gftdcojp/cloud-itonami-E3700` placeholder to the
real, verified, pushed repo, and `:required-technologies` is corrected
to drop `:robotics`/`:telemetry` (this is a non-robotics back-office
coordination actor, matching `blueprint.edn`'s
`:itonami.blueprint/robotics false`).

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(-) Still a simulation/proposal layer, not integrated with real SCADA/
telemetry/CMMS systems — scope is deliberately bounded to back-office
coordination.

(-) The supply-cost threshold (5000) is a simplified, currency-unit-
agnostic placeholder; a real deployment would tie this to a
jurisdiction- or utility-specific procurement-approval threshold.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-
backed store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-3700` repo: full module set (advisor/governor/
  operation/phase/sim/store + deps.edn + blueprint.edn + LICENSE +
  governance docs) built and pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-3700`, commit
  `f793bec49d7e44bfa2ea4546661d4a6d839f1567` (fresh repo, first
  commit — `git merge-base --is-ancestor` confirmed it landed on
  `origin/main` immediately after push).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  `langgraph` via a `:git/sha` in top-level `:deps`):
  **`Ran 48 tests containing 147 assertions. 0 failures, 0 errors.`**
  across 5 test namespaces (`sewerops.advisor-test`,
  `sewerops.governor-test`, `sewerops.governor-contract-test`,
  `sewerops.phase-test`, `sewerops.store-contract-test`).
- One real bug caught and fixed during this build (not a weakened
  assertion): `sewerops.governor/high-cost-supply-order?` used
  `some->`, which short-circuits to `nil` (not `false`) when a
  proposal's `:value` has no `:estimated-cost` — this `nil` then
  propagated through `stakes?`/`:escalate?`/`:high-stakes?` in
  `check`, failing `happy-path-every-non-escalating-op-is-clean`'s
  `(false? (:escalate? verdict))` assertion (`actual: (not (false?
  nil))`). Fixed by wrapping both `high-cost-supply-order?`'s return
  and `check`'s `stakes?` binding in `boolean`.
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:run` demo narrative exercises all four ops at phase 1
  and phase 3, the HIGH-VALUE `:order-supplies` escalate case (always
  interrupts even at phase 3), the `:flag-safety-concern` always-
  escalate case, and every HARD-hold scenario directly (unregistered
  facility, registered-but-unverified facility, non-`:propose`
  `:effect`, pump/valve-control scope drift), with no exceptions —
  output independently inspected (audit ledger + committed
  coordination log both match the expected disposition per scenario).
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested,
  `every-decision-leaves-one-ledger-fact`).
- `kotoba-lang/industry` registry entry for `"3700"` updated in place
  via an exact-text in-place edit of the single `{:id "3700" ...}`
  block (no wholesale regeneration): `:repo`/`:business-id` corrected,
  `:required-technologies` corrected (drops `:robotics`/`:telemetry`),
  `:maturity :spec` -> `:implemented`. Landed via server-side merge on
  the first attempt (no 409 contention this time) at merge commit
  `9ee255b5153a9f98fe78d0788caab7063b50224a`.
- The true fleet-wide `:implemented` count (computed via
  `kotoba.industry/maturity-summary`, not raw grep) was live-
  recomputed against a freshly re-cloned `origin/main` immediately
  before the registry edit: 206 -> 207 after promoting `"3700"`. The
  `industry_test.clj` `maturity-summary-counts-tiers` assertion was
  bumped to `(is (= 207 (:implemented m)))` accordingly (not an
  assumed fixed number).
- Post-merge re-verification from an INDEPENDENT fresh clone of
  `kotoba-lang/industry` at merge commit
  `9ee255b5153a9f98fe78d0788caab7063b50224a` (plus a fresh
  `kotoba-lang/technology` sibling clone): `clojure -M:test` ->
  **`Ran 15 tests containing 942 assertions. 0 failures, 0 errors.`**
  `grep -c "â" resources/kotoba/industry/registry.edn` = 0 (no
  file-wide UTF-8 mojibake).
