# ADR-2730004922: cloud-itonami-isic-4922 — Other Passenger Land Transport (Intercity/Chartered Coach) Dispatch Logistics Coordination

## Status

Accepted. `cloud-itonami-isic-4922` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-H4922` repo) to `:implemented` in
the registry.

## Context

ISIC Rev.5 4922 (Other passenger land transport) is a Wave 2
(coordination/logistics/trade, ADR-2607121000) target. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "4922" ...}` entry's `:name` is exactly "Other passenger land
transport" — long-distance/intercity coach, chartered bus and similar
passenger services, distinct from sibling ISIC 4921 ("Urban and
suburban passenger land transport", built concurrently in this same
batch, a separate actor with its own separate governor). A separate,
coarser-granularity 3-digit group registry entry (`{:id "492" ...}`,
"Other land transport", `:superseded-by ["4920"]`) already exists and was
deliberately left untouched — it is a redundant registry artifact, not
this actor's target. `gh api repos/cloud-itonami/cloud-itonami-isic-4922`
confirmed 404 before any work began — a fresh scaffold, no prior
repository.

**Scope**: intercity/chartered-coach SCHEDULING/DISPATCH LOGISTICS
COORDINATION, NOT direct vehicle operation and NOT dispatch-safety or
driver-fitness authority. This actor never directly operates a vehicle
and never overrides a safety judgment — a passenger-safety dimension
distinct from the retail/commerce verticals this fleet has built to
date. Mirrored on the closest already-`:implemented` structural
precedent for a pure coordination actor with a two-entity
verified-counterparty pattern, `cloud-itonami-isic-4719` (general-
merchandise retail: store + vendor two-entity `Store` protocol,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed directories, append-only audit ledger) — domain-adapted
substantially: `merchandiseops.store`'s `stores`/`vendors` two-entity
shape becomes `intercitycoachops.store`'s `vehicles` (bundling the
vehicle, its assigned route and its operator-license record into one
verified entity, per this vertical's own hard invariant that "vehicle/
route/operator-license record must be independently verified/registered
before any action") and `providers` (fleet-maintenance counterparties,
the same "ground truth, not self-report" verification discipline as
4719's vendor check, reapplied to a maintenance-supply-chain
counterparty). Every op is `:effect :propose` only, never a direct
vehicle-dispatch or vehicle-operation actuation.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — trip/ridership/incident-report data logging
- `:schedule-dispatch-operation` — vehicle/route/timetable dispatch scheduling proposal
- `:coordinate-maintenance-order` — fleet maintenance procurement proposal
- `:flag-safety-concern` — surface a vehicle-defect/driver-fitness/route-hazard concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out. Per this batch's own passenger-safety
requirement: no op in this allowlist directly finalizes a
dispatch-safety-clearance or driver-fitness-to-drive determination — the
allowlist itself is structurally incapable of that, on top of the
independent scope-exclusion defense-in-depth check below.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Vehicle unverified** — the target vehicle's record (bundling
   vehicle + assigned route + operator-license into one entity for this
   vertical) must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the vehicle's own store record every
   time, never from the proposal's own `:vehicle-id` claim.
2. **Provider unverified** — for `:coordinate-maintenance-order` ONLY,
   the proposal's own drafted `:value` must name a `:provider-id` that
   resolves to an independently `:registered?`/`:verified?` maintenance-
   provider record in the store. A missing provider-id, or one that
   resolves to an unregistered/unverified provider, is a HARD block —
   the same "ground truth, not self-report" discipline as
   `cloud-itonami-isic-4719`'s vendor-verification check, reapplied to a
   fleet-maintenance counterparty.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a dispatch-safety-clearance determination
   (clearing a vehicle to depart despite a known defect or open safety
   hold) or a driver-fitness-to-drive determination (certifying a driver
   fit to drive, overriding a fatigue/fitness concern), is a permanent,
   un-overridable block. Evaluated **unconditionally** on every proposal
   via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own
   framing. This is this vertical's own passenger-safety guardrail: the
   actor coordinates SCHEDULING/DISPATCH LOGISTICS ONLY, never directly
   operates a vehicle, and never overrides a safety judgment.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "cleared the vehicle to dispatch despite the defect", "certified
   the driver as fit to drive", "overrode the safety hold"), never as a
   bare noun (bare "safety", "fitness", "dispatch" or "clearance") that
   could accidentally match inside this same namespace's own default
   mock-advisor text. This mattered concretely here:
   `intercitycoachops.advisor`'s own printed `:op` keyword for the
   legitimate concern-flagging proposal literally contains the substring
   "safety" (`:flag-safety-concern`), and its default rationale
   legitimately discusses vehicle-defect/driver-fitness/route-hazard
   concerns — a bare-noun term list would have self-tripped this actor's
   own core happy path on every single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done. A companion sanity test,
   `out-of-scope-test-hook-does-trip-scope-exclusion`, confirms the term
   list is not vacuously non-matching.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence. A "flag a
  concern" op must always escalate and never auto-commit — enforced by
  two independent layers (the governor's own `always-escalate-ops` AND
  `intercitycoachops.phase`'s own phase table, which never puts
  `:flag-safety-concern` in any phase's `:auto` set, exercised directly
  by a dedicated `safety-concern-never-in-any-phase-auto-set` structural
  test).
- `:coordinate-maintenance-order` above a $1500 estimated-cost threshold
  — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

The high-cost maintenance-order escalate gate requires no extra
phase-layer code: the governor's own `high-stakes?` already turns the
base disposition into `:escalate` before the phase gate runs, so phase
3's `:auto` membership for `:coordinate-maintenance-order` never applies
to an over-threshold order (exercised by
`high-cost-maintenance-order-always-escalates` /
`low-cost-maintenance-order-auto-commits`).

### 3. Module shape

`intercitycoachops.store` (MemStore, string-keyed `vehicles` — bundling
vehicle + route + operator-license — AND `providers` directories — two
distinct registries, not one), `intercitycoachops.advisor`
(IntercityCoachAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts dispatch-safety-clearance/driver-
fitness-determination-scope content so the governor's scope scan can be
exercised end to end), `intercitycoachops.governor`
(IntercityCoachGovernor), `intercitycoachops.phase` (0→3 rollout),
`intercitycoachops.operation` (the `langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | request-approval),
`intercitycoachops.sim` (demo driver, `clojure -M:run`).

Before finalizing, the governor keyword `:intercity-coach-governor` and
namespace `intercitycoachops` were checked for collision against the
fleet via `gh api search/code` (`intercity-coach-governor` and
`intercitycoachops`, both zero results) — the GitHub Search API was
initially exhausted by heavy concurrent-fleet activity but recovered
before this build finished. Sibling ISIC 4921 (built concurrently in
this same batch) was independently confirmed distinct by inspecting its
already-pushed repo description: `TransitDispatchAdvisor ⊣
UrbanTransitDispatchGovernor` — a different advisor/governor name pair
in a different namespace family, confirming no collision with this
actor's `IntercityCoachAdvisor ⊣ IntercityCoachGovernor` /
`:intercity-coach-governor` / `intercitycoachops`.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4922" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "492" ...}` group entry which was
  left untouched): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-H4922` / `cloud-itonami-H4922`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4922` /
  `cloud-itonami-isic-4922`, `:maturity` `:spec`→`:implemented`;
  `:required-technologies`/`:optional-technologies`/`:operating-states`
  left unchanged (already domain-appropriate:
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics]` /
  `[:intake :book :transit :deliver :reconcile :audit]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-4922` scaffolded (fresh —
  no prior repository existed, 404 confirmed before any work began) and
  pushed to `main`, commit `291547ab61657cee15812e27e6de6f4b1c06eb39`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 58 tests containing 172 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`intercitycoachops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating safety-concern flag,
  always-escalating over-threshold maintenance order, and five HARD-hold
  scenarios: unregistered vehicle, unverified vehicle, unverified
  maintenance provider, non-`:propose` effect, scope-excluded content)
  without error.
- Governor-keyword/namespace collision check against the fleet completed
  clean (see Module shape above) — no follow-up rename needed.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern with a two-entity
  verified-counterparty shape this actor follows, substantially
  domain-adapted for passenger-safety scope)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4922"` entry (and the separate, untouched `"492"` group entry)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
