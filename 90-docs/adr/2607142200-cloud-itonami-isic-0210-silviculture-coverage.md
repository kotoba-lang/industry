# ADR-2607142200: cloud-itonami-isic-0210 (Silviculture and other forestry activities) coverage

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami Wave 3 forestry/silviculture coverage proposal)

## Context

ADR-2607121000 proposed Wave 3 expansion of cloud-itonami actor coverage into
primary-sector resource industries: agriculture (crop production), forestry
(timber/biomass), mining (extraction). ISIC class 0210 (Silviculture and other
forestry activities) is the focal point: back-office coordination of stand
assessment, field operation scheduling, forest-health monitoring, and supply
procurement.

A same-day prior attempt scaffolded the `cloud-itonami-isic-0210` repo
(`operation.cljc`/`phase.cljc`/`sim.cljc`/a test file/docs, commit `82cd8fa`)
but left it referencing four namespaces that did not exist —
`forestry.governor`, `forestry.store`, `forestry.advisor`,
`forestry.registry` — and shipped no `deps.edn`. The safety-critical
governor logic (the independent censor that is the entire point of the
governed-actor pattern) was completely absent, and the checked-in demo/test
code invoked the compiled graph via `.invoke` (JVM-only interop, not
cljs-portable). This was caught in audit and reverted; this ADR and the
underlying implementation replace that attempt.

Unlike manufacturing actors which often wrap pre-existing domain libraries
(e.g., `cloud-itonami-isic-4920` wraps `kotoba-lang/logistics`), this
silviculture vertical is SELF-CONTAINED — no `kotoba-lang/forestry` library
exists, so domain logic (stand maturity, health-status validation, supply
budget verification) lives as pure functions in `forestry.registry` and is
re-verified independently by the governor, mirroring the discipline
established by prior actors (verified against `cloud-itonami-isic-0891`
(Mining) and `cloud-itonami-isic-1010` (Meat processing) as reference
implementations).

## Decision

Complete `cloud-itonami-isic-0210` as a governed-actor implementation of the
silviculture blueprint, following the langgraph StateGraph + independent
Governor + Phase 0->3 rollout architecture established across the fleet:

1. **ForestryAdvisor** (`forestry.advisor`, sealed intelligence node):
   proposes coordination actions only, never commits
   - `:log-stand-record` — forest stand inventory/growth data logging
   - `:schedule-field-operation` — planting/thinning/harvest scheduling proposal
   - `:flag-forest-health-concern` — surface pest/disease/wildfire risk (always escalates)
   - `:order-supplies` — seedling/equipment procurement proposal

2. **Forest Coordination Governor** (`forestry.governor`, independent
   validation layer, never trusts the advisor's own self-report):
   - HARD checks (no override, evaluated unconditionally): the request's
     own `:effect` must be `:propose`; `:op` must be in the closed
     four-op allowlist; the proposal's own `:effect` must be one of the
     four propose-shaped effects (no direct logging-equipment control);
     `:finalize? true` on a harvest schedule is a PERMANENT block; a
     field operation may only be scheduled against a stand independently
     verified in the SSoT; a harvest may only be scheduled against a
     stand independently confirmed mature; no double-scheduling the same
     field-operation record; no fabricated `:health-status` value; a
     supply order's claimed total must independently recompute correctly
   - ESCALATE (human sign-off, overridable): forest-health concerns
     always escalate regardless of confidence; supply orders whose
     independently-recomputed total exceeds a cost threshold; low
     confidence

3. **Scope boundary** (critical): back-office COORDINATION only
   - Does NOT control equipment or execute field operations
   - Does NOT make forest-management decisions (exclusive to human forester)
   - Does NOT authorize/finalize harvest plans (permanently blocked, not a
     rollout milestone still to come — see `forestry.phase`:
     `:schedule-field-operation` is never a member of any phase's `:auto`
     set)
   - All proposals are `:effect :propose`; actuation is human-approval-gated

4. **Self-contained domain logic**: `forestry.registry` pure functions
   (`stand-immature-for-harvest?`, `health-status-valid?`,
   `order-total-matches-claim?`/`order-exceeds-threshold?`) are
   re-verified independently by the governor, following the "ground
   truth, not self-report" discipline established by prior actors.

5. **Store** (`forestry.store`): a single `MemStore` backend behind a
   `Store` protocol. Unlike some sibling actors this build does NOT ship a
   second Datomic-backed store — this vertical's SSoT needs no
   jurisdiction-scoped parity requirement driving one, and a second
   backend can be added later behind the same protocol without changing
   any caller.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked via
   `langgraph.graph/run*`, not `.invoke`), append-only audit ledger, full
   test coverage, demo driver. All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-0210` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Wave 3 forestry coverage is now genuinely implemented and tested (not
merely scaffolded). ISIC 0210 moves from `:blueprint`/reverted to
`:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation or harvest-plan finalization, independently corroborated by
`forestry.phase`'s permanent exclusion of `:schedule-field-operation` from
every phase's `:auto` set. Future forest-ops projects can reference this
actor's "coordination, not control" discipline.

(+) The repo is standalone (forkable outside the workspace), matching the
pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are now present and exercised by 64 tests / 160 assertions
across 5 test namespaces (`forestry.operation-test`,
`forestry.governor-contract-test`, `forestry.phase-test`,
`forestry.store-contract-test`, `forestry.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real GIS/growth
models/regulatory databases — scope is deliberately bounded to back-office
coordination.

(-) Forest-health escalation and the supply-order cost threshold are
simplified placeholders; a real deployment would tie these to
domain-specific thresholds (pest identification confidence, equipment
cost tiers, etc.).

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-0210` repo: governor/store/advisor/registry modules
  and `deps.edn` completed and pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-0210`, commit `980e651`
  (parent `82cd8fa`, the prior attempt's scaffold-only commit).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 64 tests containing 160 assertions. 0 failures, 0 errors.`** —
  verified both immediately after push and again from an independent
  fresh clone.
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  stand-not-verified, stand-immature-for-harvest,
  harvest-finalize-blocked, already-scheduled, invalid-health-status,
  order-total-mismatch), and the ESCALATE-not-HOLD over-threshold
  order-supplies case, with no exceptions.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `kotoba-lang/industry` registry entry for `"0210"` updated in place from
  its reverted/`:spec` state to `:maturity :implemented`.
