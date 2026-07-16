# ADR-2645772100: cloud-itonami-isic-7721 — Recreational and Sports Goods Rental Operations Coordination

## Status

Accepted. `cloud-itonami-isic-7721` promoted from (no `:maturity` key,
fallback `:blueprint`) to `:implemented` in the `kotoba-lang/industry`
registry.

## Context

ISIC Rev.5 7721 (Renting and leasing of recreational and sports goods)
is a Wave 4 (human-facing/personal-services) target under
ADR-2607121000's reverse-toposort rollout plan and ADR-2607152500's
Wave 4 rollout amendment (Wave 4 authorized to proceed in parallel with
Wave 3, with an explicit person-facing-service safety guardrail).
Identity independently verified against a fresh clone of
`kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "7721" ...}` entry's `:name`
is exactly "Renting and leasing of recreational and sports goods"
(skis, bicycles, watersports and camping equipment) — distinct from
sibling classes 7710 (motor vehicles), 7722 (video tapes and disks,
already landed), and 7729 (other personal and household goods, built
in the same batch by a sibling agent).

**Pre-existing repo, not a fresh scaffold**: `cloud-itonami/cloud-itonami-isic-7721`
already existed (created 2026-07-10) as a `:blueprint`-tier registry
entry from an earlier bulk-scaffolding pass — `CODE_OF_CONDUCT.md` /
`CONTRIBUTING.md` / `GOVERNANCE.md` / `LICENSE` / `README.md` /
`SECURITY.md` / `blueprint.edn` / `docs/business-model.md` /
`docs/operator-guide.md` were already present and describe the
"Recreational Rental Advisor" / "Recreational Rental Governor"
contract in detail, but no `deps.edn`, `src`, or `test` existed. This
ADR fills in the missing implementation on top of the existing
boilerplate (kept unchanged), rather than recreating the repo.

**Scope**: COORDINATION ONLY, mirrored closely on the verified sibling
`cloud-itonami-isic-7730` (Machinery/equipment rental,
ADR-2620773000)'s module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed asset directory, append-only audit ledger). Domain-adapted
for recreational/sports-equipment rental back-office operations (skis,
bicycles, watersports and camping gear): rental-record logging
(checkout/return/inspection-note data), equipment-availability/
maintenance scheduling, rental-fleet restock/replacement coordination,
and equipment-safety-concern flagging (defect, damage, malfunction —
e.g. a ski-binding release-force fault, a bicycle brake defect, a
kayak hull puncture) — never directly finalizing an
equipment-safety-clearance decision (e.g. certifying a returned unit
as safe to re-rent without inspection) or overriding an
equipment-safety-authority decision.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-rental-record` — checkout/return/inspection-note data logging
- `:schedule-fleet-operation` — equipment-availability/maintenance scheduling proposal
- `:coordinate-fleet-restock` — equipment procurement/replacement coordination proposal
- `:flag-equipment-safety-concern` — surface a defect/damage/malfunction concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the Wave 4 person-facing-service safety guardrail
(ADR-2607152500): recreational/sports-equipment rental has a direct
end-user-safety dimension — defective or uninspected gear causing
injury — so the closed op allowlist NEVER includes any op that
directly finalizes an equipment-safety-clearance decision. Every op
above is `:effect :propose` only, and the one "flag a concern" op
(`:flag-equipment-safety-concern`) always escalates to human sign-off
and is never a member of any phase's `:auto` set.

1. **Asset unverified** — the target rental-asset (equipment unit)
   record must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit
   or even escalate. Re-derived from the asset's own store record
   every time, never from the proposal's own `:asset-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing an equipment-safety-clearance decision
   (e.g. certifying a returned ski set/bike/kayak safe to re-rent
   without inspection) or overriding an equipment-safety-authority
   decision, is a permanent, un-overridable block. Evaluated
   **unconditionally** on every proposal via a lower-cased substring
   scan of the proposal's own content (English + Japanese term list) —
   never trusting the advisor's own framing. The scope-excluded term
   list is deliberately phrased as finalization/execution verb phrases
   (e.g. "certify safe to re-rent without inspection", "finalize the
   safety clearance", not the bare noun "safety" alone), so this HARD
   block never collides with the actor's own core valid use case or
   with the other three ops' own legitimate disclaimer copy (which
   mentions concepts like re-rent eligibility and human sign-off
   without claiming to finalize them) — a failure mode this ADR's own
   test suite exercises directly with two dedicated regression guards:
   `recreationalrentalops.governor-test/legitimate-equipment-safety-concern-is-not-scope-excluded`
   and `recreationalrentalops.governor-test/guest-facing-default-advisor-proposals-never-scope-excluded-end-to-end`,
   plus `recreationalrentalops.advisor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
   (parameterized over all four ops and six realistic patches, asserting
   the default mock advisor never trips its own governor's scope-exclusion
   scan — the exact self-tripping bug class multiple sibling agents in
   this fleet have independently hit and fixed).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-equipment-safety-concern` — always, regardless of confidence.
- `:coordinate-fleet-restock` above a $2000 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`recreationalrentalops.phase`'s 0→3 rollout table independently
agrees: `:flag-equipment-safety-concern` is never a member of any
phase's `:auto` set, at any phase — two layers, not one, enforce the
same invariant (exercised directly by
`equipment-safety-concern-never-in-any-auto-set`).

### 3. Module shape

`recreationalrentalops.store` (MemStore, string-keyed rental-asset
directory — a ski set, a trail bike, and an inflatable kayak awaiting
inspection as demo data), `recreationalrentalops.advisor`
(RecreationalRentalOpsAdvisor, mock + a real-LLM seam, plus an
`:out-of-scope?` test hook that deliberately drafts
re-rent-without-inspection-scope content so the governor's scope scan
can be exercised end to end), `recreationalrentalops.governor`
(RecreationalRentalGovernor), `recreationalrentalops.phase` (0→3
rollout), `recreationalrentalops.operation` (the `langgraph-clj`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval), `recreationalrentalops.sim` (demo driver, `clojure
-M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "7721" ...}` block only, not appended, not touching any other
  entry's block): `:maturity` added (`:blueprint` fallback →
  `:implemented`); `:repo` (`https://github.com/cloud-itonami/cloud-itonami-isic-7721`)
  and `:business-id` (`cloud-itonami-7721`) were already correct
  (verified against the pre-existing repo's own `blueprint.edn`
  `:itonami.blueprint/id`, unchanged).
- Actor repo `cloud-itonami/cloud-itonami-isic-7721` — pre-existing
  (blueprint-tier, boilerplate docs only) — filled in with
  `deps.edn`/`src`/`test`, committed and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 49 tests containing 160 assertions. 0 failures, 0 errors.`**
  (`clojure -M:dev:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:dev:run` (`recreationalrentalops.sim` demo) walked all
  scenarios (phase-1 approval-gated commit, phase-3 auto-commit for
  the three non-safety ops, always-escalating equipment-safety-concern
  flag, always-escalating over-threshold fleet-restock, and all four
  HARD-hold scenarios: unregistered asset, unverified asset, non-
  `:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-7730/` (module-shape mirror, ADR-2620773000)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"7721"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan)
