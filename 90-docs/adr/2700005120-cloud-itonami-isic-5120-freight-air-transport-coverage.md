# ADR-2700005120: cloud-itonami ISIC 5120 (Freight air transport) coverage

- Status: Accepted
- Date: 2026-07-16
- Wave: Wave 2 (coordination/logistics/trade, ADR-2607121000) — final
  batch of Wave 2's 4-digit gaps

## Context

`kotoba-lang/industry`'s registry carried `{:id "5120" :name "Freight air
transport" ...}` at `:maturity :spec` with placeholder `:repo`/
`:business-id` values (`gftdcojp/cloud-itonami-H5120`). Identity was
independently verified against a fresh clone before any work began: the
live `:name` field reads exactly "Freight air transport", unambiguous,
no truncation. A separate, coarser-granularity `{:id "512" ...}` 3-digit
group entry also exists in the registry and was deliberately left
untouched (a redundant registry artifact at a different granularity, out
of scope for this promotion).

`gh api repos/cloud-itonami/cloud-itonami-isic-5120` returned 404 before
any work began — this was a fresh scaffold, not an addition to a
pre-existing blueprint-tier repo.

## Decision

Publish `cloud-itonami/cloud-itonami-isic-5120` implementing
**AirFreightAdvisor ⊣ AirCargoGroundOpsGovernor**, an air-cargo GROUND
LOGISTICS SCHEDULING coordination actor (`airfreightops.*` namespace),
mirroring `cloud-itonami-isic-5012`'s (Sea and coastal freight water
transport) verified advisor/governor/phase/operation/store/sim module
shape module-for-module (`airfreightops.*` in place of
`seafreightops.*`, a facility/contractor entity pair in place of a
vessel/contractor entity pair — `facility-id` represents the ground-
handling warehouse/ramp facility's own carrier/warehouse-license
registration record, in place of `vessel-id`).

### Scope: ground logistics scheduling only — the flight-operations exclusion

Air freight transport carries the strictest safety regime of any
transport vertical addressed in this batch: aircraft airworthiness,
cargo weight-and-balance, and hazmat/dangerous-goods (IATA DGR)
restrictions are all directly life-safety-critical. This actor is
**ground logistics scheduling coordination only** and structurally
excludes flight-operations authority:

- it coordinates ground-side scheduling ONLY (warehouse/ramp/loading-
  dock operations, cargo/manifest/AWB logging, ground-equipment
  maintenance procurement); it never touches flight operations,
  piloting, or air-traffic-control functions in any way, and has no op
  in its closed allowlist that could be construed as authorizing a
  flight to depart
- the closed proposal-op allowlist (`:log-shipment-record`,
  `:schedule-ground-operation`, `:coordinate-maintenance-order`,
  `:flag-safety-concern`) contains no op that directly finalizes an
  airworthiness clearance, a weight-and-balance sign-off, or a
  dangerous-goods acceptance determination — these are HARD, permanent,
  un-overridable blocks via `airfreightops.governor`'s
  `scope-exclusion-violations` check, never merely a rollout milestone
  and never something a human approval can waive
- `:flag-safety-concern` (the op that surfaces a weight-and-balance/
  dangerous-goods/airworthiness concern for human triage) ALWAYS
  escalates to a human IMMEDIATELY, regardless of confidence, and is
  never a member of any phase's `:auto` set at any phase — two
  independent layers agree (the governor's own `always-escalate-ops`
  AND the phase table itself, the latter exercised directly by a
  dedicated `safety-concern-never-in-any-phase-auto-set` structural
  test)

Scope-excluded terms (`airfreightops.governor/scope-excluded-terms`) are
deliberately phrased as the finalization/execution ACTION ("finalize the
airworthiness clearance", "sign off on the weight and balance", "accept
the dangerous goods shipment", "authorize the flight to depart"), never
a bare noun ("airworthiness", "weight and balance", "dangerous goods")
— this fleet's own known self-tripping bug class (a bare-noun scope-
exclusion term accidentally matching inside the mock advisor's own
legitimate `:flag-safety-concern` disclaimer text, since that op's whole
job is to talk about exactly those concerns) avoided from the start. A
dedicated regression test
(`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
`governor_test.clj`) asserts all four default proposal generators never
trip `:scope-excluded` or `:op-not-allowed`.

### Governor-keyword collision check

`:air-cargo-ground-ops-governor` was checked via `gh api search/code`
across `org:cloud-itonami` before landing — zero hits at the time of the
check, and distinct from sibling ISIC 5110's (Scheduled passenger air
transport) own `:aviation-safety-governor`.

### HARD governor checks (four, all permanent, un-overridable by any human approval)

1. **Facility unverified** — the target ground-handling facility's own
   carrier/warehouse-license registration record must exist AND be
   independently `:registered?`/`:verified?` in the store, checked
   unconditionally on all four ops, re-derived from the store every
   time, never from the proposal's own self-report.
2. **Contractor unverified** — for `:coordinate-maintenance-order` only,
   the named ground-equipment (GSE) maintenance contractor must
   independently resolve to a `:registered?`/`:verified?` contractor
   record.
3. **Effect not `:propose`** — any other `:effect` value is a HARD
   block.
4. **Scope exclusion** (folds in op-not-allowed) — see above.

### Escalation (SOFT, human sign-off)

- `:flag-safety-concern` always escalates immediately.
- `:coordinate-maintenance-order` above a $5,000 USD-equivalent
  estimated-cost threshold always escalates.
- Low advisor confidence (< 0.6) also escalates.

### Architecture

Real `langgraph-clj` StateGraph
(intake→advise→govern→decide→commit|hold|request-approval) with
`interrupt-before #{:request-approval}` for human-in-the-loop resume,
not a stub. Fully portable `.cljc`, no JVM-only interop anywhere in
`src/` (mock-only advisor; the real-LLM seam is the `Advisor` protocol).

### Verification

- 61 tests / 176 assertions green (`clojure -M:test`), independently
  re-verified against a fresh clone.
- `clj-kondo` (`clojure -M:lint`): 0 errors, 0 warnings.
- `clojure -M:run` (`airfreightops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/clean ops, always-escalating safety-concern flag,
  always-escalating over-threshold maintenance order, and five
  HARD-hold scenarios: unregistered facility, unverified facility,
  unverified-contractor maintenance order, non-`:propose` effect, and
  scope-excluded content) with zero exceptions.

### Registry

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn` `"5120"`
entry updated in place: `:maturity :spec` → `:implemented`; `:repo`/
`:business-id` corrected from the stale `gftdcojp/cloud-itonami-H5120`
placeholder to `https://github.com/cloud-itonami/cloud-itonami-isic-5120`
/ `cloud-itonami-isic-5120`; `:operating-states` updated to match the
actor's own state-machine node sequence
(`:intake :advise :govern :decide :commit :hold :request-approval`);
`:required-technologies`/`:optional-technologies` left unchanged. The
coarser `{:id "512" ...}` group entry was left untouched. `test/kotoba/
industry_test.clj`'s `maturity-summary-counts-tiers` assertion bumped to
match the true, freshly recomputed fleet-wide `:implemented` count via
`kotoba.industry/maturity-summary`.

## Consequences

- ISIC 5120 (Freight air transport) is now `:implemented` in the
  registry, closing this class's Wave 2 gap.
- Any downstream integration attempting to wire this actor to a
  flight-operations, piloting, air-traffic-control, airworthiness-
  clearance, weight-and-balance-sign-off, or dangerous-goods-acceptance
  system as a finalizing authority is a security-relevant design defect
  per this actor's own `SECURITY.md`, not a feature gap to be filled —
  this exclusion is structural, not a rollout milestone still to come.

## Alternatives considered

- A single combined "carrier" entity instead of the facility/contractor
  pair — rejected in favor of mirroring the reference repo's
  vessel/contractor two-entity shape, which cleanly maps
  facility-registration (carrier/warehouse-license) onto the
  vessel-registration check and ground-equipment-maintenance-contractor
  verification onto the reference's own maintenance-contractor check,
  keeping the same "ground truth, not self-report" discipline at both
  points.
