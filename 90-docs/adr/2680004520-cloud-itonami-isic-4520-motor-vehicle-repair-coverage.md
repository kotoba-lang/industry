# ADR-2680004520: cloud-itonami-isic-4520 — Motor Vehicle Repair Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4520` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 4520 (Maintenance and repair of motor vehicles) is a Wave 2
(coordination/logistics/trade, ADR-2607121000) target — Wave 3 and Wave 4
class-level scope having already reached completion in this fleet, Wave 2
is the next phase of the reverse-toposort rollout plan.

Identity independently verified against a fresh clone of
`kotoba-lang/industry` before any work began: the live `{:id "4520"
...}` entry's `:name` is exactly "Maintenance and repair of motor
vehicles" — distinct from the sibling `{:id "452" ...}` 3-digit group
entry (a separate, redundant registry artifact left untouched) and from
sibling 4510 (sale of motor vehicles, already `:implemented`), 4530
(motor-vehicle parts sale) and 4540 (motorcycle), the latter two expected
to be built by sibling agents in this same batch. No mismatch found. No
prior repository existed for this class under either the `cloud-itonami`
org or any placeholder org — 404 confirmed before any work began.

**Scope**: this is an auto-repair-shop **OPERATIONS COORDINATION** actor
only — never direct roadworthiness-clearance authority and never
repair-shop equipment control. Vehicle repair/maintenance carries a
direct road-safety dimension, so the closed op allowlist NEVER includes
any op that directly finalizes a roadworthiness-clearance decision (e.g.
certifying a repaired vehicle as safe to drive): that is always a hard,
permanent block, never an auto-commit-eligible op in any phase's `:auto`
set. Module shape mirrors the sibling motor-vehicle actor
`cloud-itonami-isic-4510` (VehicleSale-LLM ⊣ VehicleSaleGovernor pattern:
independent advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, phase 0→3 rollout, append-only audit ledger), domain-adapted
from vehicle *sale* (title/lien/odometer) to repair-shop *operations*
(service-record logging, bay/technician scheduling, safety-concern
flagging, parts-order coordination).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — repair-order/parts-used/labor-hours data logging
- `:schedule-service-operation` — bay/technician scheduling proposal
- `:flag-safety-concern` — surface a defect/recall/unsafe-repair concern — **ALWAYS escalates**
- `:coordinate-parts-order` — parts procurement proposal

Every proposal this actor can ever commit carries `:effect :propose` by
construction — `autorepair.store/commit-record!` dispatches on the
request's `:op`, never on `:effect`, so `:effect` itself never varies and
cannot be repurposed as a side-channel execution signal. What lands in
the SSoT is always a *coordination log entry*, never an executed
real-world repair action.

### 2. AutoRepairGovernor: five HARD checks (all permanent, un-overridable), plus escalation

1. **`:rbac`** — does actor-role (`:service-writer` /`:technician` /
   `:shop-manager`) have permission for the op?
2. **`:registration-gate`** — does the referenced repair-order exist in
   the store, AND does its shop carry an ACTIVE shop-license? Neither may
   be invented or assumed — re-derived from the store on every proposal.
3. **`:effect-propose-only`** — is the proposal's `:effect` literally
   `:propose`?
4. **`:closed-op-allowlist`** — is the op one of the four allowed ops?
5. **`:roadworthiness-clearance-scope-exclusion`** — does the proposal
   (by a structured `:value` key such as `:roadworthiness-status`, or by
   an explicit finalize/certify/confirm/clear/sign-off ACTION phrase in
   its summary/rationale) attempt to finalize a roadworthiness clearance?
   This check runs on EVERY proposal, regardless of which of the four ops
   it arrived on — a compromised or off-spec advisor cannot smuggle a
   roadworthiness-clearance finalization through an otherwise-legitimate
   `:log-service-record`/etc. proposal. Hard violations route straight to
   `:hold` in the langgraph-clj graph and never reach the
   `:request-approval` human-review node — there is no override path, by
   construction, not by policy convention.

Per this fleet's known self-tripping bug class (independently discovered
and fixed by multiple sibling actors): every scope-excluded term is
phrased as a compound finalization-ACTION phrase ("finalize the
roadworthiness clearance", "certify as safe to drive"), never as a bare
noun like "safety"/"roadworthy". `:flag-safety-concern`'s own legitimate
default rationale necessarily contains the bare word "安全"/"safety" — a
bare-noun-phrased exclusion term would self-block this actor's own happy
path. A dedicated regression test,
`default-advisor-proposals-never-self-trip-scope-exclusion` in
`autorepair.governor-contract-test`, asserts every default mock-advisor
proposal for all four ops — including one whose `:flag-safety-concern`
rationale deliberately mentions "安全性"/"走行可否" as bare descriptive
nouns (a near-miss shape, not a finalize/certify action) — clears the
governor with `:roadworthiness-clearance-scope-exclusion` absent from its
violations. A second test,
`roadworthiness-finalize-attempt-blocked-on-every-op`, red-teams all four
ops with an explicit finalize-attempt flag and confirms every one hard
holds.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence, at every
  rollout phase. `autorepair.phase`'s 0→3 rollout table independently
  agrees: `:flag-safety-concern` is never a member of any phase's `:auto`
  set (verified directly by
  `flag-safety-concern-not-in-any-phases-auto-set`) — two layers, not
  one, enforce the same invariant.
- `:coordinate-parts-order` above a 1500.00 (fictitious-currency-unit)
  cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

### 3. Module shape

`autorepair.store` (MemStore + langchain.db-backed DatomicStore, both via
the shared `kotoba-lang/langchain-store` EDN-blob codec / identity-schema
/ event-log helpers per ADR-2607141600 — this is a NEW store and does not
hand-roll its own `enc`/`dec*` copy), `autorepair.llm` (AutoRepair-LLM
mock advisor + a real-LLM seam, plus a `:attempt-roadworthiness-finalize?`
red-team test hook), `autorepair.governor` (AutoRepairGovernor),
`autorepair.phase` (0→3 rollout), `autorepair.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `autorepair.sim` (demo driver, `clojure
-M:dev:run`). All source `.cljc` (cljs-first, no JVM-only interop).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4520" ...}` block only, not appended, not touching any other
  entry — including the separate `{:id "452" ...}` 3-digit group entry,
  left untouched): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4520` /
  `cloud-itonami-G4520` to `https://github.com/cloud-itonami/cloud-itonami-isic-4520`
  / `cloud-itonami-isic-4520`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to `[:intake :advise :govern :approve
  :commit :audit]` to match the actor state machine (was the generic
  retail-template `[:intake :stock :sell :reconcile :reorder :audit]`,
  which never fit a repair-operations actor).
- Actor repo `cloud-itonami/cloud-itonami-isic-4520` scaffolded (fresh —
  404 confirmed before any work began) and pushed to `main` (commit
  `ebdc4f729131518d57e52ebaa9d4c868973c3de3`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 24 tests containing 103 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test` and `clojure -M:dev:test`, identical result),
  independently re-verified against a fresh clone with the same result.
  `clojure -M:lint`: 0 errors, 0 warnings. `clojure -M:dev:run`
  (`autorepair.sim` demo) walked all eight scenarios (clean log/schedule
  auto-commit, always-escalating safety-concern flag, under/over-
  threshold parts order, and the three HARD-hold scenarios: unregistered
  repair-order, inactive shop-license, unauthorized role) without error.

## References

- `cloud-itonami-isic-4510/` (same motor-vehicle domain family, verified
  working reference for the advisor/governor/phase/operation/store/sim
  module shape)
- `kotoba-lang/langchain-store` (ADR-2607141600 — shared store seam used
  for this NEW store rather than hand-rolling `enc`/`dec*`)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4520"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
