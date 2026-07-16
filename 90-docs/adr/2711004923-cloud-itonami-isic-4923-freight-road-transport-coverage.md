# ADR-2711004923: cloud-itonami ISIC 4923 (Freight transport by road) coverage

- Status: Accepted
- Date: 2026-07-16
- Wave: Wave 2 (coordination/logistics/trade, ADR-2607121000) -- first
  TRANSPORT-class build of this batch (transition from retail-class
  siblings 47xx to transport-class 49xx)

## Context

`cloud-itonami` publishes each ISIC industry class as an autonomous
"actor" repo: an LLM/advisor node sealed behind an independent Governor,
a `langgraph-clj` StateGraph, and an append-only audit ledger. ISIC
Rev.5 class `4923` ("Freight transport by road") was registered in
`kotoba-lang/industry`'s `registry.edn` at `:maturity :spec` with a
placeholder `:repo`/`:business-id`
(`https://github.com/gftdcojp/cloud-itonami-H4923` /
`cloud-itonami-H4923`) and no implementation.

Identity was independently verified against a fresh clone of
`kotoba-lang/industry` before any work began: the live `{:id "4923" ...}`
block's `:name` is exactly `"Freight transport by road"`, unambiguous, no
truncation. This is distinct from two other, unrelated registry entries
that were deliberately left untouched:
- `{:id "492" ...}` -- a separate, coarser 3-digit GROUP-level entry
  (`:name "Other land transport"`, `:superseded-by ["4920"]`, `:repo
  nil`, `:maturity :spec`) -- a different registry granularity, not this
  class.
- `{:id "4920" ...}` -- `"Community Freight Transport"`, already
  `:implemented` with its own distinct `:repo`/`:business-id`
  (`cloud-itonami/cloud-itonami-isic-4920`) -- a different registry
  entry entirely (an earlier ISIC Rev.4-shaped freight-transport
  registration under a different code and name), not a duplicate of
  `4923`.

A fresh `gh api repos/cloud-itonami/cloud-itonami-isic-4923` confirmed
404 -- no pre-existing repository -- so this is a fresh scaffold, not an
addition to prior boilerplate.

Road freight (trucking) carries a cargo-safety dimension (load
securement, weight limits, hazmat placarding) and a driver
hours-of-service compliance dimension not present in retail-class
siblings. Per this fleet's own standing guardrail, the closed op
allowlist must never include an op that directly finalizes a
load-safety-clearance, hazmat-transport-authorization, or
hours-of-service-waiver decision -- always a hard permanent block or an
always-escalate op, never auto-commit-eligible. This actor coordinates
DISPATCH/LOGISTICS SCHEDULING ONLY -- it never directly operates a
vehicle or overrides a driver's/dispatcher's safety judgment.

## Decision

Scaffold and ship `cloud-itonami/cloud-itonami-isic-4923` as a
`RoadFreightDispatchAdvisor ⊣ RoadFreightDispatchGovernor` road-freight
dispatch/logistics-coordination actor, mirroring the verified
`cloud-itonami-isic-4719` (Other retail sale in non-specialized stores)
module shape module-for-module: `roadfreightops.store` /
`roadfreightops.advisor` / `roadfreightops.governor` /
`roadfreightops.phase` / `roadfreightops.operation` / `roadfreightops.sim`,
with `carriers`/`vendors` in place of `stores`/`vendors` and
`dispatch-log` in place of `coordination-log`.

### Closed op allowlist (all `:effect :propose`)

- `:log-shipment-record` -- cargo/manifest/delivery data logging
- `:schedule-dispatch-operation` -- truck/route/load dispatch scheduling
  proposal
- `:coordinate-maintenance-order` -- fleet-maintenance procurement
  proposal, HARD-gated on a registered/verified maintenance vendor
- `:flag-safety-concern` -- surfaces a load-securement/hazmat/
  hours-of-service concern; ALWAYS escalates, never a member of any
  phase's `:auto` set

By design, no op in this allowlist directly finalizes a load-safety
clearance, a hazmat-transport authorization, or an hours-of-service
waiver -- those decisions are permanently excluded via governor check 4
below, never merely un-implemented.

### Four HARD governor checks (permanent, un-overridable by human approval)

1. `carrier-unverified` -- the target carrier's motor-carrier operating
   authority must exist AND be independently `:registered?`/`:verified?`
   in the store before ANY proposal for it may commit or escalate,
   re-derived from the store every time, never from the proposal's own
   claim.
2. `vendor-unverified` -- for `:coordinate-maintenance-order` ONLY, the
   proposal's drafted `:value` must name a `:vendor-id` resolving to an
   independently `:registered?`/`:verified?` maintenance-vendor record.
3. `effect-not-propose` -- every proposal's `:effect` must be
   `:propose`; any other value is a claim to directly actuate outside
   governance.
4. `scope-excluded` (folds in `op-not-allowed`) -- ANY proposal whose
   op/summary/rationale/cites/value touches directly finalizing a
   load-safety-clearance decision, granting/issuing a hazmat-transport
   authorization, granting an hours-of-service waiver, or overriding a
   driver's/dispatcher's safety judgment is a HARD, PERMANENT block,
   evaluated unconditionally on every proposal, regardless of op or
   confidence.

### Two ESCALATE (SOFT) gates

- `:flag-safety-concern` always escalates, at any phase/confidence --
  never auto-commit-eligible, matching this fleet's "a 'flag a concern'
  op must always escalate and never be in any phase's `:auto` set"
  guardrail. Two independent layers agree:
  `roadfreightops.governor/always-escalate-ops` AND
  `roadfreightops.phase/phases`' own `:auto` sets (verified directly by
  `safety-concern-never-in-any-phase-auto-set` in `phase_test.clj`).
- `:coordinate-maintenance-order` above a 5,000.0 USD-equivalent
  domain-illustrative threshold always escalates.
- (LLM confidence below the 0.6 floor also escalates, as with every
  sibling actor.)

### Self-trip regression (known bug class in this actor fleet)

Multiple sibling agents in this fleet independently discovered and
fixed the same bug class: a governor's scope-exclusion term list phrased
as a bare noun (e.g. "safety", "hazmat") accidentally matches inside the
mock advisor's own default rationale text for a legitimate proposal,
causing the actor to self-block its own happy path.
`roadfreightops.governor/scope-excluded-terms` is deliberately phrased
as the finalization/execution ACTION (e.g. "finalize the load safety
clearance", "grant the hours-of-service waiver", "authorize the hazmat
transport"), never a bare noun, with matching Japanese action phrases
(e.g. "危険物輸送許可を発行", "労働時間規制の免除を承認"). A dedicated
regression test,
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
`governor_test.clj`, asserts every op the default mock advisor can
generate never trips `:scope-excluded` or `:op-not-allowed`; a
complementary `out-of-scope-hook-does-trip-scope-exclusion` test proves
the check is not a no-op. Before finalizing, the chosen governor keyword
`:road-freight-dispatch-governor` was checked via `gh search code
"road-freight-dispatch-governor" --owner cloud-itonami` and `gh search
code "dispatch-governor" --owner cloud-itonami` (both zero results) for
collision against this same batch's concurrent siblings ISIC 4921/4922
(passenger transport, `:name`s "Urban and suburban passenger land
transport" / "Other passenger land transport" -- confirmed a different
domain, still `:spec` at verification time).

### Architecture

Real `langgraph-clj` StateGraph
(`intake -> advise -> govern -> decide -> commit | hold |
request-approval`) with `interrupt-before #{:request-approval}` for
human-in-the-loop resume -- not a stub. Fully portable `.cljc` in `src/`
with no JVM-only interop anywhere (cljs-first mandate); `test/` uses
`.clj` (JVM `cognitect.test-runner`), matching the verified reference
repo's own convention.

## Verification

- `clj-kondo` via `clojure -M:lint`: 0 errors / 0 warnings.
- `clojure -M:test` (and `clojure -M:dev:test` with local
  `kotoba-lang/langgraph` + `kotoba-lang/langchain` sibling overrides):

  ```
  Ran 59 tests containing 171 assertions.
  0 failures, 0 errors.
  ```

  Independently re-verified against a fresh clone after merge (see
  addendum below).
- `clojure -M:dev:run` demo driver exercises all 11 scenarios end-to-end
  (approval-gated logging at phase 1; auto-commit at phase 3 for
  shipment-record/dispatch-operation/low-cost maintenance-order;
  always-escalate high-cost maintenance-order and safety-concern flags;
  HARD holds for an unregistered carrier, an unverified carrier, an
  unverified maintenance vendor, a non-`:propose` effect, and
  scope-excluded content) plus the audit ledger and committed
  dispatch-log dump.
- Build performed entirely in a uniquely-named scratch directory
  (`/private/tmp/.../scratchpad/4923-work/`), never inside the shared
  `orgs/kotoba-lang/industry` or `orgs/cloud-itonami/*` checkouts.

## Consequences

- `kotoba-lang/industry`'s `registry.edn` `"4923"` entry moves from
  `:spec` to `:implemented`, with `:repo`/`:business-id` corrected to
  point at `cloud-itonami/cloud-itonami-isic-4923` and this ADR
  referenced (tracked in a companion registry PR/merge, landed via the
  git-trees/blobs API against freshly-fetched content per this fleet's
  hot-contention discipline for `test/kotoba/industry_test.clj`).
- `{:id "492" ...}` (group-level, `:superseded-by ["4920"]`) and
  `{:id "4920" ...}` ("Community Freight Transport") are left completely
  untouched -- distinct registry entries, out of this ADR's scope.
- Extending coverage (e.g. a proof-of-delivery op, a detention-time-
  billing check) is additive: add the next op as its own governed op
  with its own HARD checks and tests, following the SAME "an independent
  governor re-verifies against the actor's own records before any
  real-world act" pattern this repo's flagship checks already establish.

## References

- Reference/mirror structure: `cloud-itonami/cloud-itonami-isic-4719`
  (Other retail sale in non-specialized stores).
- Wave 2 plan: ADR-2607121000 (cloud-itonami-global-isic-isco reverse
  toposort plan).
- Known self-tripping bug class and its fix pattern: prior sibling ADRs
  for `cloud-itonami-isic-4661`/`5610`/`6020`/`6010`/`4912` etc, all
  citing the same `scope-excluded-terms` phrasing discipline.
