# ADR-2700004781: cloud-itonami-isic-4781 — Stall/Market Food, Beverage and Tobacco Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4781` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 4781 (Retail sale via stalls and markets of food, beverages
and tobacco products) is a Wave 2 (coordination/logistics/trade,
ADR-2607121000) target. Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "4781" ...}` entry's `:name`
was found genuinely truncated with a literal `"..."` ("Retail sale via
stalls and markets of food, beverages and t...", a known pre-existing
seed-data bug affecting roughly 10% of entries) and was de-truncated in
place, as part of the normal exact-block edit, to the full ISIC class
name "Retail sale via stalls and markets of food, beverages and tobacco
products". No class mismatch found. The redundant 3-digit group entry
`{:id "478" ...}` was left untouched.

`gh api repos/cloud-itonami/cloud-itonami-isic-4781` returned 404 before
any work began — this is a fresh scaffold, not a promotion of prior
work. The registry's pre-existing `:repo`/`:business-id` values
(`https://github.com/gftdcojp/cloud-itonami-G4781` /
`cloud-itonami-G4781`) were themselves stale placeholders pointing at a
never-created repo, matching the same pattern this fleet has repeatedly
found and corrected on sibling ISIC-47xx entries (4719, 4721, 4722,
4723).

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-4722` (specialized beverage retail, age-verification
pattern) and `cloud-itonami-isic-4719` (non-specialized retail)'s
verified module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed store directory, append-only audit ledger). Domain-adapted
for mobile/temporary market-stall and street-market vendors selling
food, beverages, AND tobacco products: inventory/sale/return
sales-record logging, stall placement/staffing scheduling,
inventory-procurement supply-order coordination, and compliance-concern
flagging (suspected food-safety-clearance failure, age-verification
failure for tobacco sales, or stall/market-permit lapse) — never
finalizing a food-safety clearance, finalizing an age-verification
override, directly actuating point-of-sale age-verification/ID
hardware, or performing market/health/tobacco-licensing-authority
enforcement. This class combines TWO guardrail dimensions the sibling
fleet has separately established (food-safety, from specialized food
retail/food service actors, and age-verification, from beverage/tobacco
retail actors) in a single mobile-vendor actor, since a market stall may
sell all three of food, beverages, and tobacco.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/return data logging
- `:schedule-stall-operation` — stall placement/staffing scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-compliance-concern` — surface a suspected food-safety-clearance-failure/age-verification-failure/permit concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the combined food-safety + age-verification guardrail this domain
requires: the closed op allowlist NEVER includes any op that directly
finalizes a food-safety clearance or an age-verification override — both
are always either a hard permanent block (see check 3) or, for the one
"flag a concern" op, an always-escalate op, never an auto-commit-eligible
op in any phase's `:auto` set.

1. **Stall unverified** — the target stall's record (vendor/business
   registration AND stall/market food-safety-and-tobacco-retail permit)
   must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the stall's own store record every
   time, never from the proposal's own `:stall-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches finalizing a food-safety clearance, finalizing an
   age-verification override, directly actuating point-of-sale
   age-verification/ID hardware, or market/health/tobacco-licensing-
   authority enforcement (stall/market permit issuance/suspension,
   health-department enforcement, compliance enforcement), is a
   permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing. A STRUCTURED-FIELD companion check
   (`structured-scope-violations`) also inspects the proposal's `:value`
   for explicit finalization-intent booleans
   (`:finalizes-food-safety-clearance?`,
   `:finalizes-age-verification-override?`, etc.) — belt-and-suspenders
   alongside the free-text scan, per this fleet's most recent best
   practice of preferring structured-field checks over free-text
   scanning where possible, to avoid the self-trip bug class more
   structurally.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors, most recently
   `cloud-itonami-isic-4722`): every scope-excluded term is phrased as
   the finalization/execution ACTION (e.g. "finalize the food safety
   clearance", "control the id scanner"), never as a bare noun (bare
   "food" or bare "verification") that could accidentally match inside
   this same namespace's own default mock-advisor disclaimer text for a
   legitimate, allowed proposal. Concretely, the point-of-sale
   age-verification/ID-hardware-actuation terms are kept English-only (no
   Japanese equivalent), because `marketstallops.advisor`'s own default
   `:schedule-stall-operation` rationale legitimately says (in Japanese)
   that it does NOT touch such hardware
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

`marketstallops.phase`'s 0→3 rollout table independently agrees:
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

`marketstallops.store` (MemStore, string-keyed stall directory),
`marketstallops.advisor` (MarketStallRetailAdvisor, mock + a real-LLM
seam, plus an `:out-of-scope?` test hook that deliberately drafts
food-safety-clearance-finalization/age-verification-override-scope
content so the governor's scope scan can be exercised end to end),
`marketstallops.governor` (MarketStallRetailGovernor, with the
structured-field companion check described above),
`marketstallops.phase` (0→3 rollout), `marketstallops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `marketstallops.sim` (demo driver, `clojure
-M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4781" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "478" ...}` group entry which was
  left untouched): `:name` de-truncated from the literal `"...t..."`
  seed-data bug to the full class name, `:repo`/`:business-id`
  de-placeholdered from `https://github.com/gftdcojp/cloud-itonami-G4781`
  / `cloud-itonami-G4781` to
  `https://github.com/cloud-itonami/cloud-itonami-isic-4781` /
  `cloud-itonami-isic-4781`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling ISIC-47xx
  retail pattern).
- Actor repo `cloud-itonami/cloud-itonami-isic-4781` scaffolded (fresh —
  no prior repository existed, 404 confirmed before any work began) and
  pushed to `main` (commit `9173ae8a5b8e5709ba2611b6d1f9e9db8287f345`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 48 tests containing 134 assertions. 0 failures, 0 errors.`**
  (`clojure -M:dev:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:dev:run` (`marketstallops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating compliance-concern flag,
  always-escalating over-threshold supply order, and all four HARD-hold
  scenarios: unregistered stall, unverified stall, non-`:propose`
  effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-4722/` (module-shape mirror, ADR-2691004722 —
  verified working reference for the specialized-beverage-retail actor
  pattern, in particular the age-verification-guardrail dimension this
  actor also applies)
- `cloud-itonami-isic-4719/` (secondary module-shape reference,
  non-specialized retail)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4781"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2691004722 (ISIC-4722 specialized beverage retail coordination —
  the module shape and scope-exclusion self-trip fix precedent this
  actor also applies)
