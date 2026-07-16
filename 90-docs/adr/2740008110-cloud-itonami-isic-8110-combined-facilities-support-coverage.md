# ADR-2740008110: cloud-itonami-isic-8110 — Combined Facilities Support Activities Operations Coordination

## Status

Accepted. `cloud-itonami-isic-8110` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-N8110` repo) to `:implemented` in
the registry.

## Context

ISIC Rev.5 8110 (Combined facilities support activities) is the FINAL
batch of Wave 2's (coordination/logistics/trade, ADR-2607121000)
remaining 4-digit gaps. Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "8110" ...}` entry's `:name` is
exactly "Combined facilities support activities" — no truncation. A
separate, coarser-granularity 3-digit group registry entry
(`{:id "811" ...}`, same name, `:repo nil`) already exists and was
deliberately left untouched — it is a redundant registry artifact, not
this actor's target. Sibling ISIC 8129 ("Other building and industrial
cleaning activities") is a distinct class built concurrently in this same
batch, a separate actor with its own separate governor. `gh api
repos/cloud-itonami/cloud-itonami-isic-8110` confirmed 404 before any
work began — a fresh scaffold, no prior repository.

**New sub-sector for this Wave 2 batch**: facility/support services, not
transport, not retail. ISIC 8110 covers a bundled facility-management
service — a single contractor providing combined cleaning, security,
maintenance and grounds-keeping for a building/campus under ONE contract.
No physical-safety-critical dimension as severe as this wave's earlier
pipeline/aviation-adjacent classes, but building-security and access-
control coordination requires care: this actor's closed op allowlist
never includes any op that directly finalizes a building-access-
credential grant/revocation or an emergency-response override — those
are structurally absent from the allowlist, and any proposal content
that drifts toward them is a HARD, permanent, un-overridable block (never
merely an escalate-eligible or auto-commit-eligible op).

**Scope**: combined-facilities-support OPERATIONS COORDINATION, NOT
direct building-access-control authority and NOT emergency-response
authority. Mirrored on the closest already-`:implemented` structural
precedent for a pure coordination actor with a two-entity
verified-counterparty pattern, `cloud-itonami-isic-4719` (general-
merchandise retail: store + vendor two-entity `Store` protocol,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed directories, append-only audit ledger) — domain-adapted
substantially: `merchandiseops.store`'s `stores`/`vendors` two-entity
shape becomes `facilitiesops.store`'s `facilities` (the building/
campus's combined facility-services contract record) and `suppliers`
(facility-supplies/equipment procurement counterparties, the same
"ground truth, not self-report" verification discipline as 4719's vendor
check, reapplied to a facility-supply-chain counterparty). Every op is
`:effect :propose` only, never a direct actuation.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — cleaning/maintenance/security-round completion data logging
- `:schedule-service-operation` — combined-service (cleaning/security/maintenance) crew scheduling proposal
- `:coordinate-supply-order` — facility-supplies/equipment procurement proposal
- `:flag-facility-concern` — surface a security-incident/maintenance-hazard/access-control concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out. Per this batch's own access-control/
emergency-response guardrail: no op in this allowlist directly finalizes
a building-access-credential grant/revocation or an emergency-response
override — the allowlist itself is structurally incapable of that, on
top of the independent scope-exclusion defense-in-depth check below.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Facility unverified** — the target facility's combined-services
   contract record must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the facility's own store record every
   time, never from the proposal's own `:facility-id` claim.
2. **Supplier unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:supplier-id` that
   resolves to an independently `:registered?`/`:verified?` supplier
   record in the store. A missing supplier-id, or one that resolves to an
   unregistered/unverified supplier, is a HARD block — the same "ground
   truth, not self-report" discipline as `cloud-itonami-isic-4719`'s
   vendor-verification check, reapplied to a facility-supply-chain
   counterparty.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a building-access-credential grant or
   revocation (issuing, activating, revoking or deactivating a
   keycard/badge/door-code or any other access credential), or directly
   overriding/bypassing/disabling an emergency-response protocol
   (fire-alarm override, emergency-lockdown bypass, life-safety-system
   disablement), is a permanent, un-overridable block. Evaluated
   **unconditionally** on every proposal via a lower-cased substring scan
   of the proposal's own content (English + Japanese term list) — never
   trusting the advisor's own framing. This is this vertical's own
   access-control/emergency-response guardrail: the actor coordinates
   facility-support OPERATIONS ONLY, never grants/revokes building
   access, and never overrides emergency response.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "granted building access", "revoked the access credential",
   "overrode the emergency response protocol"), never as a bare noun
   (bare "access", "credential", "security" or "emergency") that could
   accidentally match inside this same namespace's own default
   mock-advisor text. This mattered concretely here:
   `facilitiesops.advisor`'s own legitimate `:flag-facility-concern`
   default rationale discusses security-incident/maintenance-hazard/
   access-control concerns — a bare-noun term list would have
   self-tripped this actor's own core happy path on every single run. A
   dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done. A companion sanity test,
   `out-of-scope-injection-still-trips-scope-exclusion`, confirms the
   term list is not vacuously non-matching.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-facility-concern` — always, regardless of confidence. A "flag a
  concern" op must always escalate and never auto-commit — enforced by
  two independent layers (the governor's own `always-escalate-ops` AND
  `facilitiesops.phase`'s own phase table, which never puts
  `:flag-facility-concern` in any phase's `:auto` set, exercised directly
  by a dedicated `facility-concern-never-in-any-phase-auto-set`
  structural test).
- `:coordinate-supply-order` above a $2000 estimated-cost threshold —
  always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

The high-cost supply-order escalate gate requires no extra phase-layer
code: the governor's own `high-stakes?` already turns the base
disposition into `:escalate` before the phase gate runs, so phase 3's
`:auto` membership for `:coordinate-supply-order` never applies to an
over-threshold order (exercised by `high-cost-supply-order-always-
escalates` / `low-cost-supply-order-does-not-force-escalate` and the
integration-level `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`facilitiesops.store` (MemStore, string-keyed `facilities` — the
building/campus's combined facility-services contract record — AND
`suppliers` directories — two distinct registries, not one),
`facilitiesops.advisor` (FacilitiesSupportAdvisor, mock + a real-LLM
seam, plus an `:out-of-scope?` test hook that deliberately drafts
access-credential-grant/emergency-response-override-scope content so the
governor's scope scan can be exercised end to end), `facilitiesops.governor`
(FacilitiesSupportGovernor), `facilitiesops.phase` (0→3 rollout),
`facilitiesops.operation` (the `langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | request-approval),
`facilitiesops.sim` (demo driver, `clojure -M:run`).

Before finalizing, the governor keyword `:facilities-support-governor`
and namespace `facilitiesops` were checked for collision against the
fleet via `gh api search/code` (`facilities-support-governor` and
`facilitiesops`, both zero results), including specifically against
sibling ISIC 8129 (other cleaning services, built concurrently in this
same batch) — no collision found.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "8110" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "811" ...}` group entry which was
  left untouched): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-N8110` / `cloud-itonami-N8110`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-8110` /
  `cloud-itonami-isic-8110`, `:maturity` `:spec`→`:implemented`;
  `:required-technologies`/`:optional-technologies` left unchanged
  (already domain-appropriate:
  `[:robotics :identity :forms :dmn :bpmn :audit-ledger :labor]` / `[]`);
  `:operating-states` updated from the stale intake/register/match/
  dispatch/follow-up/audit placeholder shape to match this actor's real
  langgraph-clj node sequence.
- Actor repo `cloud-itonami/cloud-itonami-isic-8110` scaffolded (fresh —
  no prior repository existed, 404 confirmed before any work began) and
  pushed to `main`, commit `d8eb912b0923e99a8faaeecb9aa7c20265978afb`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 57 tests containing 168 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`facilitiesops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating facility-concern flag,
  always-escalating over-threshold supply order, and five HARD-hold
  scenarios: unregistered facility, unverified facility, unverified
  supplier, non-`:propose` effect, scope-excluded content) without error.
- Governor-keyword/namespace collision check against the fleet completed
  clean (see Module shape above) — no follow-up rename needed.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern with a two-entity
  verified-counterparty shape this actor follows, substantially
  domain-adapted for facility-support scope)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"8110"` entry (and the separate, untouched `"811"` group entry)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
