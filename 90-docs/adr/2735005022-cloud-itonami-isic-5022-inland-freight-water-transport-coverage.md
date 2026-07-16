# ADR-2735005022: cloud-itonami-isic-5022 — Inland Freight Water Transport Port/Logistics Scheduling Coordination

## Status

Accepted. `cloud-itonami-isic-5022` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-H5022` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 5022 (Inland freight water transport) is a Wave 2
(coordination/logistics/trade, ADR-2607121000) target — the final batch
of Wave 2's 4-digit gaps (8 classes dispatched together). Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "5022" ...}` entry's `:name` is exactly "Inland freight water
transport" (not truncated, no de-truncation needed). No separate
3-digit group entry `{:id "502" ...}` exists in the registry (unlike
some sibling classes) — nothing to avoid touching there.

**Scope**: inland-waterway-freight PORT/LOGISTICS SCHEDULING
COORDINATION ONLY (river barges, canal cargo vessels, towboat/pushboat
operations) — NOT direct barge/towboat navigation, NOT direct
dispatch/rerouting, NOT direct lock-gate operation, and NOT authority to
finalize a vessel-seaworthiness clearance or a cargo-load-safety
clearance, or to override a tow captain's or lockmaster's safety
judgment. Mirrored closely, module-for-module, on sibling
`cloud-itonami-isic-5012` (Sea and coastal freight water transport,
already `:implemented`) — the closest structural and domain precedent
(the same four-op coordination shape: advisor/governor/phase/operation/
store/sim, `langgraph-clj` StateGraph, independent Governor, phase 0→3
rollout, string-keyed registry directories, append-only audit ledger).
Domain-adapted for inland/river/canal freight: cargo/manifest/voyage
record logging, dock/lock-scheduling and voyage scheduling coordination,
barge/tow-vessel-maintenance procurement coordination with a
registered/verified contractor, and cargo-safety/river-worthiness-concern
flagging (hazmat placarding discrepancy, load-securement anomaly,
hull/draft/trim observation) — never navigating, dispatching or
rerouting a barge/towboat, never directly operating lock gates or other
inland-waterway control infrastructure, and never finalizing a
vessel-seaworthiness or cargo-load-safety clearance or overriding a tow
captain's/lockmaster's safety judgment.

Also read sibling `cloud-itonami-isic-5021` (Inland passenger water
transport, `FerryDispatchAdvisor ⊣ FerryDispatchGovernor`, already
`:implemented`) for domain/terminology context, since it is the closest
already-`:implemented` inland-waterway sibling — but this actor mirrors
`cloud-itonami-isic-5012`'s structure, not 5021's, since 5012 is a
freight (cargo) coordination actor of the same shape this class needs,
while 5021 is a passenger-dispatch actor with a different op vocabulary.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-shipment-record` — cargo/manifest/voyage record data logging
- `:schedule-berth-operation` — dock/lock-scheduling and voyage scheduling proposal
- `:coordinate-maintenance-order` — barge/tow-vessel-maintenance procurement proposal
- `:flag-safety-concern` — surface a cargo-load-safety/river-worthiness/hazmat concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out. None of these four ops navigate a
barge/towboat, operate lock infrastructure, or finalize a
vessel-seaworthiness/cargo-load-safety clearance.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Vessel unverified** — the target vessel/carrier's registration
   record must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the vessel's own record every time,
   never from the proposal's own `:vessel-id` claim.
2. **Contractor unverified** — for `:coordinate-maintenance-order` ONLY,
   the proposal's own drafted `:value` must name a `:contractor-id` that
   resolves to an independently `:registered?`/`:verified?`
   maintenance-contractor record in the store. A missing contractor-id,
   or one that resolves to an unregistered/unverified contractor, is a
   HARD block — a maintenance-supply-chain counterparty-verification
   gate, mirrored from sibling ISIC 5012.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a vessel-seaworthiness clearance, a
   cargo-load-safety clearance, overriding a tow captain's/lockmaster's
   safety judgment, bypassing an inland waterway safety protocol, or
   directly navigating/dispatching/rerouting a barge/towboat or directly
   operating lock gates, is a permanent, un-overridable block. Evaluated
   **unconditionally** on every proposal via a lower-cased substring scan
   of the proposal's own content (English + Japanese term list) — never
   trusting the advisor's own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, including the
   sibling this actor mirrors): every scope-excluded term is phrased as
   the finalization/execution ACTION (e.g. "finalize the seaworthiness
   clearance", "override the tow captain's safety judgment", "directly
   operate the lock gates"), never as a bare noun (bare "seaworthy",
   "river-worthy", "cargo load safety" or "hazmat") that could
   accidentally match inside this same namespace's own default
   mock-advisor text. This mattered concretely here:
   `inlandbargeops.advisor`'s own default rationale for the legitimate
   concern-flagging proposal legitimately discusses "積付安全・堪航性・危険物
   表示" (cargo-load-safety, river-worthiness, hazmat placarding) as bare
   nouns — a bare-noun term list would have self-tripped this actor's
   own core happy path on every single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence. Never a
  member of any phase's `:auto` set, at any phase.
- `:coordinate-maintenance-order` above a $5000 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`inlandbargeops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set —
two layers, not one, enforce the same invariant (exercised directly by
`safety-concern-holds-when-not-enabled` /
`safety-concern-escalates-when-enabled` /
`safety-concern-never-in-any-phase-auto-set`). The high-cost
maintenance-order escalate gate requires no extra phase-layer code: the
governor's own `high-stakes?` already turns the base disposition into
`:escalate` before the phase gate runs, so phase 3's `:auto` membership
for `:coordinate-maintenance-order` never applies to an over-threshold
order (exercised by `high-cost-maintenance-order-always-escalates` /
`low-cost-maintenance-order-auto-commits`).

### 3. Module shape

`inlandbargeops.store` (MemStore, string-keyed vessel AND contractor
directories — two distinct registries, not one), `inlandbargeops.advisor`
(InlandBargeAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts barge-navigation/lock-operation/
safety-clearance-finalization-scope content so the governor's scope scan
can be exercised end to end), `inlandbargeops.governor`
(InlandWaterwayFreightGovernor), `inlandbargeops.phase` (0→3 rollout),
`inlandbargeops.operation` (the `langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | request-approval),
`inlandbargeops.sim` (demo driver, `clojure -M:run`).

`:itonami.blueprint/governor` keyword `:inland-waterway-freight-governor`
checked for fleet-wide uniqueness via `gh api search/code` against
`org:cloud-itonami` before finalizing (0 hits — distinct from sibling
ISIC 5012's `:maritime-freight-governor` and sibling ISIC 5021's
`FerryDispatchGovernor`). Namespace `inlandbargeops` and the governor
keyword's variants (`:inland-freight-governor`, `:barge-freight-governor`)
were also checked and confirmed collision-free before this one was
picked — no naming-collision precedent question.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5022" ...}` block only, not appended, not touching any other
  entry): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-H5022` / `cloud-itonami-H5022`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-5022` /
  `cloud-itonami-isic-5022`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated from the spec placeholder
  `[:intake :book :transit :deliver :reconcile :audit]` to match the
  actual actor state machine `[:intake :advise :govern :approve :commit
  :audit]` (the same convention already used by `:implemented` sibling
  actors 5012 and 6310), `:required-technologies` left as-is:
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics]`.
- Actor repo `cloud-itonami/cloud-itonami-isic-5022` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 60 tests containing 174 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`inlandbargeops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating safety-concern flag,
  always-escalating over-threshold maintenance order, and five HARD-hold
  scenarios: unregistered vessel, unverified vessel, unverified
  maintenance-order contractor, non-`:propose` effect, scope-excluded
  content) without error.

## References

- `cloud-itonami-isic-5012/` (module-shape mirror — verified working
  reference for the inland/coastal freight port-logistics-scheduling
  actor pattern this actor follows, module-for-module)
- `cloud-itonami-isic-5021/` (sibling inland-waterway actor, read for
  domain/terminology context but NOT mirrored structurally — 5021 is a
  passenger-dispatch actor with a different op vocabulary)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"5022"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
