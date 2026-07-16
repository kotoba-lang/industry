# ADR-2650005610: cloud-itonami-isic-5610 — Restaurants and Mobile Food Service Operations Coordination

## Status

Accepted. `cloud-itonami-isic-5610` promoted from no `:maturity` key
(resolves to `:blueprint` via the `kotoba.industry/maturity-of` fallback,
since the entry already carries a `:repo`) to `:implemented` in the
`kotoba-lang/industry` registry.

## Context

ISIC Rev.4/5 5610 (Restaurants and mobile food service activities) is a
Wave 4 (human-facing/personal-services) target under ADR-2607121000's
reverse-toposort rollout plan and ADR-2607152500's Wave 4 rollout
amendment (Wave 4 authorized to proceed in parallel with Wave 3, with an
explicit person-facing-service safety guardrail). Identity independently
verified against a fresh clone of `kotoba-lang/industry` before any work
began: the live `{:id "5610" ...}` entry's `:name` is exactly
"Restaurants and mobile food service activities" — no mismatch. A
separate, coarser-granularity `{:id "561" ...}` (3-digit group) entry
also exists and was already `:maturity :implemented` from a different
registry-granularity pass; it is untouched by this ADR (only the
`"5610"` class-level block was edited).

**Not a fresh scaffold.** `cloud-itonami/cloud-itonami-isic-5610` already
existed as a legitimate `:blueprint`-tier repo (published by an earlier
bulk-scaffolding pass, commit `f378cb2`: `CODE_OF_CONDUCT.md`/
`CONTRIBUTING.md`/`GOVERNANCE.md`/`LICENSE`/`README.md`/`SECURITY.md`/
`blueprint.edn`/`docs/business-model.md`/`docs/operator-guide.md` — no
`deps.edn`, no `src`, no `test`). That blueprint frames the vertical on
cloud-itonami's general robotics-premised physical-work model (a robot
performs food-prep/plating/delivery, gated by a "Food Service Governor")
and is preserved as-is in this ADR's changes — none of the existing
boilerplate docs, `blueprint.edn`, or `docs/` were rewritten or removed,
only added to.

**Scope of the actor implemented here**: COORDINATION ONLY, mirrored
closely on the verified, independently-re-tested sibling
`cloud-itonami-isic-5629` (Institutional Food-Service Operations
Coordination, ADR-2616562900) module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed directory,
append-only audit ledger). Domain-adapted for stand-alone restaurants and
mobile food service units (food trucks, carts, pop-up kitchens — distinct
from 5629's institutional/contract food-service sites and from 5621's
stand-alone event catering): order/table-turn/menu-item service-record
logging, shift/prep staffing-operation scheduling, ingredient/equipment
supply-order coordination, and food-safety-concern flagging (allergen
mismatch, temperature abuse, suspected contamination) — never finalizing
a food-safety-clearance decision, overriding an allergen-exclusion
requirement, directly actuating kitchen equipment, or performing
food-safety-authority enforcement. The robotics-dispatch capability layer
described in the pre-existing blueprint is a separate, not-yet-wired
concern this actor's coordination proposals could eventually feed into,
always gated the same way — this ADR does not implement robot dispatch.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — order/table-turn/menu-item data logging
- `:schedule-staffing-operation` — shift/prep scheduling proposal
- `:coordinate-supply-order` — ingredient/equipment procurement proposal
- `:flag-food-safety-concern` — surface an allergen-mismatch/temperature-abuse/contamination concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the Wave 4 person-facing-service safety guardrail (ADR-2607152500):
restaurants have a direct food-safety/allergen-exposure dimension, so the
closed op allowlist NEVER includes any op that directly finalizes a
food-safety-clearance decision — that is always either a hard permanent
block (see check 3) or, for the one "flag a concern" op, an
always-escalate op, never an auto-commit-eligible op in any phase's
`:auto` set.

1. **Location unverified** — the target location's business
   registration + health permit must exist in the store AND be
   independently `:registered?`/`:verified?` before any proposal for it
   may commit or even escalate. Re-derived from the location's own store
   record every time, never from the proposal's own `:location-id` claim.
   ("Location" deliberately covers both a fixed restaurant address and a
   mobile food-service unit's registered operating base, since ISIC 5610
   spans both.)
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
   framing.

   Per this fleet's known self-tripping bug class — a governor's own
   scope-exclusion term list phrased as a bare noun (e.g. "safety")
   accidentally matching inside the mock advisor's own DEFAULT
   rationale/disclaimer text for a legitimate, allowed proposal — every
   term in `restaurantops.governor/scope-excluded-terms` is phrased as
   the finalization/execution ACTION (e.g. "finalize the food safety
   clearance", "食品安全認可の確定"), never a bare noun. A dedicated
   regression test,
   `restaurantops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`,
   runs the default mock advisor's own proposal for every allowed op
   (including `:flag-food-safety-concern`, whose entire job is to talk
   about food-safety concerns) through the governor and asserts none of
   them ever trip `:scope-excluded` or `:op-not-allowed`.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-food-safety-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $500 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`restaurantops.phase`'s 0→3 rollout table independently agrees:
`:flag-food-safety-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant (exercised
directly by `food-safety-concern-holds-when-not-enabled` /
`food-safety-concern-escalates-when-enabled` /
`food-safety-concern-never-in-any-phase-auto-set`). The high-cost
supply-order escalate gate requires no extra phase-layer code: the
governor's own `high-stakes?` already turns the base disposition into
`:escalate` before the phase gate runs, so phase 3's `:auto` membership
for `:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`restaurantops.store` (MemStore, string-keyed location directory),
`restaurantops.advisor` (RestaurantAdvisor, mock + a real-LLM seam, plus
an `:out-of-scope?` test hook that deliberately drafts
food-safety-clearance-finalization/allergen-exclusion-override-scope
content so the governor's scope scan can be exercised end to end),
`restaurantops.governor` (RestaurantGovernor), `restaurantops.phase`
(0→3 rollout), `restaurantops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`restaurantops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Actor repo `cloud-itonami/cloud-itonami-isic-5610` (pre-existing
  `:blueprint`-tier repo, NOT freshly scaffolded) filled in: `deps.edn`,
  `.gitignore`, full `src/restaurantops/*.cljc` + `test/restaurantops/*.clj`
  module set added on top of the existing boilerplate docs/blueprint.edn,
  which are preserved unchanged. README.md extended (not replaced) with a
  new "Operations-coordination actor" section documenting the actual
  implementation, module list, and test suite, while keeping the
  pre-existing robotics-premise narrative intact. Committed and pushed
  directly to `main` (fast-forward from `f378cb2`, no divergence):
  `9380dd64e8ae97b6b6b3508edb44dc3ca99f35f4`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 47 tests containing 138 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result.
- Registry entry updated in place (exact-text edit of the existing
  `{:id "5610" ...}` block only, not appended, not touching any other
  entry including the separate `{:id "561" ...}` group-level entry):
  `:maturity :implemented` added (previously absent — the entry resolved
  to `:blueprint` only via the `:repo`-present fallback in
  `kotoba.industry/maturity-of`). `:repo` and `:business-id` were already
  correct (`https://github.com/cloud-itonami/cloud-itonami-isic-5610` /
  `cloud-itonami-5610`, matching the pre-existing `blueprint.edn`'s own
  `:itonami.blueprint/id`) and were left unchanged, as were
  `:required-technologies`/`:optional-technologies`/`:operating-states`
  (the `:robotics` entry in `:required-technologies` reflects this
  vertical's genuine robotics-premised blueprint, unlike 5629's residual
  category where `:robotics` was a stale placeholder — left as-is, not a
  stale artifact to correct).

## References

- `cloud-itonami-isic-5629/` (module-shape mirror, ADR-2616562900 —
  verified working, independently re-tested Wave 4 food-service
  precedent)
- `cloud-itonami-isic-5621/` (sibling Wave 4 food-service precedent,
  ADR-2616562100)
- `cloud-itonami-isic-5630/` (sibling Wave 4 food-service precedent,
  ADR-2616005630)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"5610"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan)
- `90-docs/adr/2607100600-cloud-itonami-foodservice-5610-blueprint.md` (original blueprint ADR this ADR builds on)
