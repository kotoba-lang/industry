# ADR-2691004722: cloud-itonami-isic-4722 — Specialized Beverage Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4722` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4722` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.4 4722 (Retail sale of beverages in specialized stores) is a
Wave 2 (coordination/logistics/trade, ADR-2607121000) target. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began, per this fleet's ID/name-mismatch caution: the
live `{:id "4722" ...}` entry's `:name` is exactly "Retail sale of
beverages in specialized stores" — distinct from specialized food
retail (ISIC 4721, already implemented) and tobacco retail (ISIC 4723,
a separate stale `:spec` placeholder, both expected to be built by
sibling agents in this same batch). No mismatch found. The redundant
3-digit group entry `{:id "472" ...}` was left untouched.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-4721` (Specialized food retail)'s verified module
shape (advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout, string-keyed store
directory, append-only audit ledger). Domain-adapted for specialized
beverage retail stores — liquor stores, wine shops, and craft-beer
shops: inventory/sale/return sales-record logging, floor-staff
scheduling, inventory-procurement supply-order coordination, and
compliance-concern flagging (suspected age-verification failure or
over-service) — never finalizing an age-verification override,
finalizing a responsible-service-of-alcohol decision, directly actuating
point-of-sale age-verification/ID-scanner hardware, or performing
alcohol-licensing-authority enforcement. Beverage retail carries a
direct age-verification/responsible-service-of-alcohol dimension
analogous to the food-safety/allergen dimension the sibling food-retail
and food-service actors in this fleet are held to, so it receives the
same guardrail treatment.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/return data logging
- `:schedule-staffing-operation` — floor-staff scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-compliance-concern` — surface a suspected age-verification-failure/over-service concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the alcohol-age-verification guardrail this domain requires: the
closed op allowlist NEVER includes any op that directly finalizes an
age-verification override or a responsible-service-of-alcohol decision
— those are always either a hard permanent block (see check 3) or, for
the one "flag a concern" op, an always-escalate op, never an
auto-commit-eligible op in any phase's `:auto` set.

1. **Store unverified** — the target store's record (business
   registration AND liquor retail license) must exist in the store AND
   be independently `:registered?`/`:verified?` before any proposal for
   it may commit or even escalate. Re-derived from the store's own store
   record every time, never from the proposal's own `:store-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches finalizing an age-verification override, finalizing a
   responsible-service-of-alcohol decision, directly actuating
   point-of-sale age-verification/ID-scanner hardware, or
   alcohol-licensing-authority enforcement (liquor-control-board
   clearance, license issuance/suspension, compliance enforcement), is a
   permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing. A STRUCTURED-FIELD companion check
   (`structured-age-violations`) also inspects the proposal's `:value`
   for explicit finalization-intent booleans
   (`:finalizes-age-verification-override?`,
   `:finalizes-responsible-service-decision?`, etc.) — belt-and-
   suspenders alongside the free-text scan, per this fleet's most recent
   best practice of preferring structured-field checks over free-text
   scanning where possible, to avoid the self-trip bug class more
   structurally.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, most recently
   `cloud-itonami-isic-4721`): every scope-excluded term is phrased as
   the finalization/execution ACTION (e.g. "finalize the age
   verification", "control the id scanner"), never as a bare noun (bare
   "age" or bare "verification") that could accidentally match inside
   this same namespace's own default mock-advisor disclaimer text for a
   legitimate, allowed proposal. Concretely, the point-of-sale
   age-verification/ID-scanner-actuation terms are kept English-only (no
   Japanese equivalent), because `beverageretailops.advisor`'s own
   default `:schedule-staffing-operation` rationale legitimately says
   (in Japanese) that it does NOT touch such hardware
   ("...年齢確認端末の直接操作は行わない"). A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` absent
   from its violations, before this build was considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-compliance-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $500 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`beverageretailops.phase`'s 0→3 rollout table independently agrees:
`:flag-compliance-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant
(exercised directly by `compliance-concern-holds-when-not-enabled` /
`compliance-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`beverageretailops.store` (MemStore, string-keyed store directory),
`beverageretailops.advisor` (BeverageRetailAdvisor, mock + a real-LLM
seam, plus an `:out-of-scope?` test hook that deliberately drafts
age-verification-override-finalization/responsible-service-decision-
scope content so the governor's scope scan can be exercised end to end),
`beverageretailops.governor` (BeverageRetailGovernor, with the
structured-field companion check described above),
`beverageretailops.phase` (0→3 rollout), `beverageretailops.operation`
(the `langgraph-clj` StateGraph: intake → advise → govern → decide →
commit | hold | request-approval), `beverageretailops.sim` (demo driver,
`clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4722" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "472" ...}` group entry which was
  left untouched): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4722` / `cloud-itonami-G4722`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4722` /
  `cloud-itonami-isic-4722`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling ISIC 4721
  pattern of `:robotics true` in `blueprint.edn` for retail-adjacent
  domains).
- Actor repo `cloud-itonami/cloud-itonami-isic-4722` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`
  (commit `a7aaffd64ac57c167f9a05b81b02bc1731d32b51`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 48 tests containing 134 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`beverageretailops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating compliance-concern flag,
  always-escalating over-threshold supply order, and all four HARD-hold
  scenarios: unregistered store, unverified store, non-`:propose`
  effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-4721/` (module-shape mirror, ADR-2670004721 —
  verified working reference for the specialized-food-retail actor
  pattern this actor closely adapted)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4722"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2670004721 (ISIC-4721 specialized food retail coordination — the
  module shape and scope-exclusion self-trip fix precedent this actor
  also applies)
