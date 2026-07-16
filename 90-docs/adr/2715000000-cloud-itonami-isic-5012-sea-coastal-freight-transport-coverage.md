# ADR-2715000000: cloud-itonami-isic-5012 — Sea and Coastal Freight Water Transport Port/Logistics Scheduling Coordination

## Status

Accepted. `cloud-itonami-isic-5012` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-H5012` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 5012 (Sea and coastal freight water transport) is a Wave 2
(coordination/logistics/trade, ADR-2607121000) target, transitioning
this wave from retail (47xx) classes into transport classes. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "5012" ...}` entry's `:name` is exactly "Sea and coastal
freight water transport" (not truncated, no de-truncation needed). A
separate, redundant 3-digit group entry `{:id "501" ...}` also exists in
the registry (`:name` "Sea and coastal water transport", `:repo` nil,
`:maturity :spec`) — deliberately NOT touched or promoted; only the
`"5012"` class-level block was edited.

**Scope**: maritime-freight PORT/LOGISTICS SCHEDULING COORDINATION ONLY —
NOT direct vessel navigation, NOT direct vessel dispatch/rerouting, and
NOT authority to finalize a vessel-seaworthiness clearance or a
cargo-load-safety clearance, or to override a captain's or harbor
master's safety judgment. Mirrored structurally on the sibling,
already-`:implemented` `cloud-itonami-isic-4719` (non-specialized-store
retail operations coordination)'s verified coordination-only module
shape (advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout, string-keyed
registry directories, append-only audit ledger) — the closest
already-`:implemented` structural precedent for a pure coordination
actor. Domain-adapted for maritime freight: cargo/manifest/voyage record
logging, port berth/voyage scheduling coordination, vessel-maintenance
procurement coordination with a registered/verified contractor, and
cargo-safety/seaworthiness-concern flagging (hazmat placarding
discrepancy, load-securement anomaly, hull/stability observation) —
never navigating, dispatching or rerouting a vessel, and never finalizing
a vessel-seaworthiness or cargo-load-safety clearance or overriding a
captain's/harbor-master's safety judgment.

This actor deliberately does NOT replicate sibling ISIC 5020 (Water
freight transport / tanker)'s much more elaborate domain-specific HSE
checks (IMO-number check-digit validation, SOLAS inert-gas O2 ceiling,
bill-of-lading/cargo-grade/DWT verification, double-dispatch/
double-discharge guards) — 5020 models a real dispatch/discharge
actuation authority with jurisdiction-specific physical-safety facts,
while this 5012 actor is deliberately narrower: a generic four-op
port/logistics-scheduling COORDINATION actor with no dispatch/discharge
authority at all, following the SAME lighter-weight coordination-only
shape as 4719/5610/4721, not 5020's actuation-authority shape.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-shipment-record` — cargo/manifest/voyage record data logging
- `:schedule-berth-operation` — port berth/voyage scheduling proposal
- `:coordinate-maintenance-order` — vessel-maintenance procurement proposal
- `:flag-safety-concern` — surface a cargo-load-safety/seaworthiness/hazmat concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out. None of these four ops navigate a
vessel or finalize a vessel-seaworthiness/cargo-load-safety clearance.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Vessel unverified** — the target vessel/carrier's registration
   record must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the vessel's own record every time,
   never from the proposal's own `:vessel-id` claim.
2. **Contractor unverified** (FLAGSHIP NEW, grep-verified absent
   fleet-wide as a check name in this exact phrasing) — for
   `:coordinate-maintenance-order` ONLY, the proposal's own drafted
   `:value` must name a `:contractor-id` that resolves to an
   independently `:registered?`/`:verified?` maintenance-contractor
   record in the store. A missing contractor-id, or one that resolves to
   an unregistered/unverified contractor, is a HARD block — the flagship
   genuinely new check this vertical adds, a maintenance-supply-chain
   counterparty-verification gate no sibling retail/commerce actor has
   had reason to add.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a vessel-seaworthiness clearance, a
   cargo-load-safety clearance, overriding a captain's/harbor-master's
   safety judgment, bypassing a maritime safety protocol, or directly
   navigating/dispatching/rerouting a vessel, is a permanent,
   un-overridable block. Evaluated **unconditionally** on every proposal
   via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own
   framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "finalize the seaworthiness clearance", "override the captain's
   safety judgment", "directly navigate the vessel"), never as a bare
   noun (bare "seaworthy", "seaworthiness", "cargo load safety" or
   "hazmat") that could accidentally match inside this same namespace's
   own default mock-advisor text. This mattered concretely here:
   `seafreightops.advisor`'s own default rationale for the legitimate
   concern-flagging proposal legitimately discusses "積付安全・耐空性・危険物
   (IMDG)表示等" (cargo-load-safety, seaworthiness, hazmat/IMDG placarding)
   — a bare-noun term list would have self-tripped this actor's own core
   happy path on every single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- `:coordinate-maintenance-order` above a $5000 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`seafreightops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `safety-concern-holds-when-not-enabled` /
`safety-concern-escalates-when-enabled`). The high-cost maintenance-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-maintenance-order` never applies to an over-threshold order
(exercised by `high-cost-maintenance-order-always-escalates` /
`low-cost-maintenance-order-auto-commits`).

### 3. Module shape

`seafreightops.store` (MemStore, string-keyed vessel AND contractor
directories — two distinct registries, not one), `seafreightops.advisor`
(SeaFreightAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?` test
hook that deliberately drafts vessel-navigation/safety-clearance-
finalization-scope content so the governor's scope scan can be exercised
end to end), `seafreightops.governor` (MaritimeFreightGovernor),
`seafreightops.phase` (0→3 rollout), `seafreightops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `seafreightops.sim` (demo driver, `clojure
-M:run`).

`:itonami.blueprint/governor` keyword `:maritime-freight-governor`
checked for fleet-wide uniqueness via `gh api search/code` against
`org:cloud-itonami` before finalizing (0 hits, distinct from sibling ISIC
5020's own `:marine-cargo-governor`) — no naming-collision precedent
question.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5012" ...}` block only, not appended, not touching any other
  entry, including the separate `"501"` group-level entry which was left
  untouched): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-H5012` / `cloud-itonami-H5012`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-5012` /
  `cloud-itonami-isic-5012`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is:
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-5012` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 57 tests containing 168 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`seafreightops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating safety-concern flag,
  always-escalating over-threshold maintenance order, and five HARD-hold
  scenarios: unregistered vessel, unverified vessel, unverified
  maintenance-order contractor, non-`:propose` effect, scope-excluded
  content) without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows)
- `cloud-itonami-isic-5020/` (sibling ISIC 5xxx transport actor, read for
  terminology/context but NOT mirrored structurally — 5020 models a real
  dispatch/discharge actuation authority with jurisdiction-specific
  physical-safety facts; this actor is deliberately narrower,
  coordination-only, no dispatch/discharge authority)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"5012"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
