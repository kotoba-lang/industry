# ADR-2670004721: cloud-itonami-isic-4721 — Specialized Food Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4721` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4721` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.4 4721 (Retail sale of food in specialized stores) is a Wave 2
(coordination/logistics/trade, ADR-2607121000) target — Wave 3 and Wave 4
class-level scope having already reached completion in this fleet, Wave 2
is the next phase of the reverse-toposort rollout plan. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "4721" ...}` entry's `:name` is exactly "Retail sale of food
in specialized stores" — distinct from stand-alone beverage retail (ISIC
4722) and tobacco retail (ISIC 4723), both expected to be built by
sibling agents in a later batch. No mismatch found.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-5629` (Other food service activities)'s verified
module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed store directory, append-only audit ledger). Domain-adapted
for specialized food retail stores — butcher shops, bakeries,
fishmongers, and greengrocers: inventory/sale/spoilage sales-record
logging, floor-staff/delivery staffing scheduling, inventory-procurement
supply-order coordination, and food-safety-concern flagging (spoilage,
allergen mismatch, suspected contamination) — never finalizing a
food-safety-clearance decision, overriding an allergen-exclusion
requirement, directly actuating refrigeration/slicing/processing
equipment, or performing food-safety-authority enforcement. This
consumer-facing retail domain carries the same direct food-safety/
allergen-exposure dimension as the Wave 4 food-service actors, so it is
held to the same guardrail even though it lands in Wave 2.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/spoilage data logging
- `:schedule-staffing-operation` — floor-staff/delivery scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-food-safety-concern` — surface a spoilage/allergen-mismatch/contamination concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the food-safety guardrail this fleet has applied to every
consumer-facing food domain regardless of wave: specialized food retail
has a direct food-safety/allergen dimension, so the closed op allowlist
NEVER includes any op that directly finalizes a food-safety-clearance
decision — those are always either a hard permanent block (see check 3)
or, for the one "flag a concern" op, an always-escalate op, never an
auto-commit-eligible op in any phase's `:auto` set.

1. **Store unverified** — the target store's record (business
   registration AND health permit) must exist in the store AND be
   independently `:registered?`/`:verified?` before any proposal for it
   may commit or even escalate. Re-derived from the store's own store
   record every time, never from the proposal's own `:store-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches finalizing a food-safety-clearance decision, overriding an
   allergen-exclusion requirement, directly actuating refrigeration/
   slicing/processing equipment, or food-safety-authority enforcement
   (health-department clearance, inspection sign-off, license/permit
   actions), is a permanent, un-overridable block. Evaluated
   **unconditionally** on every proposal via a lower-cased substring
   scan of the proposal's own content (English + Japanese term list) —
   never trusting the advisor's own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution
   ACTION (e.g. "food safety clearance", "finalize clearance",
   "control the slicer"), never as a bare noun (bare "safety" or bare
   "equipment") that could accidentally match inside this same
   namespace's own default mock-advisor disclaimer text for a
   legitimate, allowed proposal. Concretely, the refrigeration/
   processing-equipment-actuation terms are kept English-only (no
   Japanese equivalent), because `foodretailops.advisor`'s own default
   `:schedule-staffing-operation` rationale legitimately says (in
   Japanese) that it does NOT touch such equipment
   ("...加工設備の直接操作は行わない"). A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` absent
   from its violations, before this build was considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-food-safety-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $500 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`foodretailops.phase`'s 0→3 rollout table independently agrees:
`:flag-food-safety-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant
(exercised directly by `food-safety-concern-holds-when-not-enabled` /
`food-safety-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`foodretailops.store` (MemStore, string-keyed store directory),
`foodretailops.advisor` (FoodRetailAdvisor, mock + a real-LLM seam, plus
an `:out-of-scope?` test hook that deliberately drafts
food-safety-clearance-finalization/allergen-exclusion-override-scope
content so the governor's scope scan can be exercised end to end),
`foodretailops.governor` (FoodRetailGovernor), `foodretailops.phase`
(0→3 rollout), `foodretailops.operation` (the `langgraph-clj`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval), `foodretailops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4721" ...}` block only, not appended, not touching any other
  entry): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4721` / `cloud-itonami-G4721`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4721` /
  `cloud-itonami-isic-4721`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling ISIC 5610
  pattern of `:robotics true` in `blueprint.edn` for retail-adjacent
  domains).
- Actor repo `cloud-itonami/cloud-itonami-isic-4721` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`
  (commit `8e222d7bff3be0d59c44dcd8eedc261089f43b07`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 46 tests containing 130 assertions. 0 failures, 0 errors.`**
  (`clojure -M:dev:test`), independently re-verified against a fresh
  clone with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`foodretailops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating food-safety-concern flag,
  always-escalating over-threshold supply order, and all four HARD-hold
  scenarios: unregistered store, unverified store, non-`:propose`
  effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-5629/` (module-shape mirror, ADR-2616562900 —
  verified working reference for the food-service-actor pattern)
- `cloud-itonami-isic-5610/` (sibling precedent for `:robotics true` /
  `[:robotics ...]` in a food-retail-adjacent `blueprint.edn`)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4721"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2616562900 (ISIC-5629 institutional food-service coordination —
  scope-exclusion self-trip fix precedent this actor also applies)
