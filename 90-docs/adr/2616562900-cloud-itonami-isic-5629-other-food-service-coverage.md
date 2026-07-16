# ADR-2616562900: cloud-itonami-isic-5629 — Institutional Food-Service Operations Coordination

## Status

Accepted. `cloud-itonami-isic-5629` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-I5629` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before
any work began) to `:implemented` in the registry.

## Context

ISIC Rev.4 5629 (Other food service activities) is a Wave 4
(human-facing/personal-services, ISIC sections I/P/Q/R/S/T) target
under ADR-2607121000's reverse-toposort rollout plan and
ADR-2607152500's Wave 4 rollout amendment (Wave 4 authorized to
proceed in parallel with Wave 3, with an explicit person-facing-service
safety guardrail). Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began, per this
fleet's ID/name-mismatch caution: the live `{:id "5629" ...}` entry's
`:name` is exactly "Other food service activities" — a residual
food-service category (e.g. cafeterias/canteens, food-court vendors,
catering under institutional contracts), distinct from stand-alone
restaurants and mobile food service (ISIC 5610) and from stand-alone
event catering (ISIC 5621). No truncated-name seed-data bug on this
entry.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-873` (Residential care for elderly/disabled)'s
verified Wave 4 module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed facility directory, append-only audit ledger). Domain-adapted
for institutional/contract food-service operations (hospital
cafeterias, school/workplace canteens, food-court vendor bays under
institutional contracts): meal-count/menu/allergen-flag service-record
logging, prep/service-operation scheduling, ingredient/equipment
supply-order coordination, and food-safety-concern flagging (allergen
mismatch, temperature abuse, suspected contamination) — never finalizing
a food-safety-clearance decision, overriding an allergen-exclusion
requirement, directly actuating kitchen equipment, or performing
food-safety-authority enforcement.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — meal-count/menu/allergen-flag data logging
- `:schedule-service-operation` — prep/service scheduling proposal
- `:coordinate-supply-order` — ingredient/equipment procurement proposal
- `:flag-food-safety-concern` — surface an allergen-mismatch/temperature-abuse/contamination concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the Wave 4 person-facing-service safety guardrail
(ADR-2607152500): food service has a direct food-safety/allergen-exposure
dimension, so the closed op allowlist NEVER includes any op that
directly finalizes a food-safety-authority decision — those are always
either a hard permanent block (see check 3) or, for the one "flag a
concern" op, an always-escalate op, never an auto-commit-eligible op in
any phase's `:auto` set.

1. **Facility unverified** — the target facility's service-contract
   record must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the facility's own store record every
   time, never from the proposal's own `:facility-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches finalizing a food-safety-clearance decision, overriding an
   allergen-exclusion requirement, directly actuating kitchen equipment,
   or food-safety-authority enforcement (health-department clearance,
   inspection sign-off, license/permit actions), is a permanent,
   un-overridable block. Evaluated **unconditionally** on every proposal
   via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own
   framing. The scope-excluded term list is deliberately phrased as
   finalization/override/actuation phrases (e.g. "食品安全認可の確定"
   [finalize the food-safety clearance], "allergen exclusion override",
   not the bare noun "食品安全認可" [food-safety clearance] alone) so
   this HARD block never collides with the actor's own core valid use
   case — legitimately flagging an observed allergen mismatch or
   temperature excursion via `:flag-food-safety-concern`, or logging a
   routine service record whose own rationale text explains it performed
   *no* clearance judgement. This exact collision (a legitimate
   `:log-service-record` proposal's own rationale — "...食品安全認可の
   判断なし" / "...no food-safety-clearance judgement" — self-tripping
   an earlier, over-broad bare-noun term) was caught by this ADR's own
   test suite during development and fixed by narrowing the term to the
   finalization phrase; the fix is exercised directly by
   `legitimate-food-safety-concern-is-not-scope-excluded` and by every
   passing `:log-service-record` test.

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-food-safety-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $500 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`foodserviceops.phase`'s 0→3 rollout table independently agrees:
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

`foodserviceops.store` (MemStore, string-keyed facility directory),
`foodserviceops.advisor` (FoodServiceAdvisor, mock + a real-LLM seam,
plus an `:out-of-scope?` test hook that deliberately drafts
food-safety-clearance-finalization/allergen-exclusion-override-scope
content so the governor's scope scan can be exercised end to end),
`foodserviceops.governor` (FoodServiceGovernor), `foodserviceops.phase`
(0→3 rollout), `foodserviceops.operation` (the `langgraph-clj`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval), `foodserviceops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5629" ...}` block only, not appended, not touching any other
  entry): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-I5629` / `cloud-itonami-I5629`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-5629` /
  `cloud-itonami-isic-5629`, `:maturity` `:spec`→`:implemented`,
  `:required-technologies` trimmed from the stale placeholder set
  (`:robotics` removed — coordination-only) to `[:identity :forms :dmn
  :bpmn :audit-ledger]`, `:operating-states` updated to match the
  actor state machine.
- Actor repo `cloud-itonami/cloud-itonami-isic-5629` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 45 tests containing 126 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`foodserviceops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating food-safety-concern flag,
  always-escalating over-threshold supply order, and all four HARD-hold
  scenarios: unregistered facility, unverified facility, non-`:propose`
  effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-873/` (module-shape mirror, ADR-2607152700 —
  Wave 4 flagship precedent)
- `cloud-itonami-isic-5520/` (sibling Wave 4 precedent with an
  analogous cost-threshold escalate gate on a coordination op)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"5629"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan)
- ADR-2607152700 (ISIC-873 eldercare coordination, Wave 4 module-shape precedent)
