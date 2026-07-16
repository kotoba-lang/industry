# ADR-2622000000: cloud-itonami-isic-9810 — Undifferentiated Household-Goods Own-Account-Production Tracking-Programme Operations Coordination

## Status

Accepted. `cloud-itonami-isic-9810` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 9810 (Undifferentiated goods-producing activities of private
households for own use) is a Wave 4 (human-facing/personal-services)
target under ADR-2607121000's reverse-toposort rollout plan and
ADR-2607152500's Wave 4 rollout amendment (Wave 4 authorized to proceed
in parallel with Wave 3, with an explicit person-facing-service safety
guardrail). Identity independently verified against a fresh clone of
`kotoba-lang/industry` before any work began, per this fleet's ID/name-
mismatch caution: the live `{:id "9810" ...}` entry's `:name` was
"Undifferentiated goods-producing activities of private hous..."
(genuinely truncated, one of the ~10%-of-entries truncated-name
seed-data bugs), matching (once un-truncated) "Undifferentiated
goods-producing activities of private households for own use" — the
truncated `:name` is fixed as part of this ADR's exact-block registry
edit. A separate, redundant 3-digit group entry `{:id "981" ...}`
exists at `:maturity :spec` with a similarly truncated name — an
earlier data-seeding-pass artifact, deliberately NOT touched by this
ADR (only the `"9810"` class-level block was edited). No repository
existed at `cloud-itonami/cloud-itonami-isic-9810` before this work
(404 confirmed); the registry's own pre-existing `:repo` value pointed
at a stale, never-created placeholder
(`https://github.com/gftdcojp/cloud-itonami-T9810`), also 404-confirmed.

**This class is an unusual, residual/statistical registry entry, not a
normal business, and this ADR records that framing explicitly.** ISIC
9810 is a UN System of National Accounts BOOKKEEPING category for
own-account, non-market household production — subsistence farming,
home food processing, household construction/repair done for the
household's OWN consumption, not for sale. It exists so that GDP /
national production statistics can account for non-market household
production; it is not a market activity and there is no real-world
"business" that IS a 9810 entity the way a restaurant (ISIC 5610) or a
publisher (ISIC 5811) is. Inventing a fictitious commercial business for
this class (e.g. a marketplace that buys/sells household-produced
goods) would misrepresent the ISIC definition. Instead, this build
models the actor as an **OPERATIONS COORDINATION actor for a
structured own-account-production TRACKING PROGRAMME** — the genuine,
precedented real-world activity pattern in which a rural-development or
agricultural-extension programme (in the shape of an FAO / national-
statistics-office household-production survey) registers and tracks
participating households' own-account production for statistical and
food-security purposes. This is honestly a **data-coordination /
statistical-support use case, not a marketplace or production
business** — documented plainly in the actor repo's own README.md Scope
section so this framing is not lost to a future reader who only sees
the registry's `:name` field.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-5629` (Other food service activities,
ADR-2616562900) and `cloud-itonami-isic-873` (Residential care
activities for the elderly and disabled, ADR-2607152700)'s verified
Wave 4 module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed household directory, append-only audit ledger).
Domain-adapted for a household own-account-production tracking
programme's back-office operations: participating-household
production-quantity/type record logging, extension-worker/enumerator
household-visit scheduling, in-kind programme-support coordination
(seed/tool/training), and food-security-concern flagging — never
directly performing field/production work on a household's behalf, and
critically, NEVER finalizing a welfare/aid-eligibility determination.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-household-production-record` — participating-household own-account production-quantity/type data logging
- `:schedule-survey-visit` — extension-worker/enumerator household-visit scheduling proposal
- `:coordinate-programme-support` — seed/tool/training-support coordination proposal
- `:flag-food-security-concern` — surface a food-insecurity/production-shortfall concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the Wave 4 person-facing-service safety guardrail
(ADR-2607152500): this actor touches household-level data and, being
food-security-adjacent, could touch welfare/eligibility decisions — so
the closed op allowlist NEVER includes any op that directly finalizes a
welfare/aid-eligibility determination; that territory is always a hard
permanent block, never an auto-commit-eligible op. Every op above is
`:effect :propose` only, and the one "flag a concern" op
(`:flag-food-security-concern`) always escalates to human sign-off and
is never a member of any phase's `:auto` set.

1. **Household unverified** — the target household (programme-
   enrollment) record must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the household's own store record
   every time, never from the proposal's own `:household-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches (a) finalizing a welfare/aid-eligibility determination, (b)
   directly executing field/production work on a household's behalf,
   or (c) suspending/terminating a household's programme enrollment, is
   a permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's
   own content (English + Japanese term list) — never trusting the
   advisor's own framing.

   **Known fleet bug class deliberately avoided from the start**:
   multiple sibling agents in this fleet independently discovered that
   a scope-exclusion term phrased as a bare noun can accidentally match
   inside the mock advisor's own DEFAULT rationale text for a
   legitimate, allowed proposal, causing the actor to self-block on its
   own happy path. Every term in `hhproductionops.governor/scope-
   excluded-terms` is therefore phrased as the finalization/execution
   ACTION ("finalize the eligibility determination", "directly perform
   the harvest", "suspend programme enrollment"), never as a bare noun
   ("eligibility", "harvest", "enrollment"). This build actually HIT
   this bug during its own development, not merely guarded against it
   in the abstract: the initial draft of the
   `:coordinate-programme-support` proposal's rationale literally
   contained the substring "給付資格の確定" ("finalization of aid
   eligibility"), which was also one of the governor's own
   scope-excluded terms — every default proposal was re-scanned
   programmatically against the term list before the test suite was
   finalized, the collision was found, and the rationale text was
   reworded (to "支給の可否は人間が判断する", "a human decides whether to
   provide the support") without weakening the scope-exclusion term
   itself. `default-mock-advisor-proposals-never-self-trip-scope-
   exclusion` in `hhproductionops.governor-test` is a dedicated
   regression test, parameterized over all four allowlisted ops, that
   guards against recurrence.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-food-security-concern` — always, regardless of confidence.
- `:coordinate-programme-support` above a $250 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`hhproductionops.phase`'s 0→3 rollout table independently agrees:
`:flag-food-security-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`hhproductionops.store` (MemStore, string-keyed household directory),
`hhproductionops.advisor` (HouseholdProductionAdvisor, mock + a
real-LLM seam, plus an `:out-of-scope?` test hook that deliberately
drafts eligibility-determination/field-work-execution-scope content so
the governor's scope scan can be exercised end to end),
`hhproductionops.governor` (HouseholdProductionGovernor),
`hhproductionops.phase` (0→3 rollout), `hhproductionops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit
| hold | request-approval), `hhproductionops.sim` (demo driver,
`clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "9810" ...}` block only, not appended, not touching the
  separate `"981"` group entry): `:name` un-truncated to
  "Undifferentiated goods-producing activities of private households
  for own use", `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-T9810` /
  `cloud-itonami-T9810` to
  `https://github.com/cloud-itonami/cloud-itonami-isic-9810` /
  `cloud-itonami-isic-9810`, `:maturity` `:spec`→`:implemented`,
  `:required-technologies` trimmed from the stale 7-item placeholder
  set (`:robotics`/`:labor` removed — coordination-only) to
  `[:identity :forms :dmn :bpmn :audit-ledger]`, `:operating-states`
  updated to match the actor state machine (`[:intake :advise :govern
  :approve :commit :audit]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-9810` scaffolded (fresh
  — no prior repository existed at either the stale `gftdcojp`
  placeholder or the real `cloud-itonami` org target) and pushed to
  `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 45 tests containing 128 assertions. 0 failures, 0 errors.`**
  (both `clojure -M:test` and `clojure -M:dev:test`, identical result),
  independently re-verified against a fresh clone with the same
  result. `clojure -M:lint`: 0 errors, 0 warnings. `clojure -M:run`
  (`hhproductionops.sim` demo) walked all scenarios (phase-1
  approval-gated commit, phase-3 auto-commit for the three non-safety
  ops, always-escalating food-security-concern flag, always-escalating
  over-threshold programme-support coordination, and all four HARD-hold
  scenarios: unregistered household, unverified household, non-
  `:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-5629/` and `cloud-itonami-isic-873/` (module-shape mirrors, ADR-2616562900 / ADR-2607152700)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"9810"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan)
