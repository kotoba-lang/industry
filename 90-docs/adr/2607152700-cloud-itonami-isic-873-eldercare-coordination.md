# ADR-2607152700: cloud-itonami-isic-873 — Residential Care Operations Coordination

## Status

Accepted. `cloud-itonami-isic-873` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry) to `:implemented` in the registry.

## Context

ISIC Rev.4 873 (Residential care activities for the elderly and disabled)
is Wave 4's flagship target — the demographic anchor ("日本の高齢化くさび",
Japan's aging population wedge) that ADR-2607121000 identified as long-term
TAM-maximum for human-services verticals. Existing registry entry was a
bare placeholder with no actor implementation or repo. This is a fresh,
from-scratch scaffold built as the first Wave 4 actor under the
verified-redo discipline established by ADR-2607152500 (Wave 4 rollout
amendment) and exemplified by ADR-2607152300 (ISIC-0520 lignite mining).

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-0520` (Mining of lignite)'s module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed resident directory, append-only audit ledger). Domain-adapted for
elderly/disabled residential care: daily care-note logging, family visit
scheduling, consumable supply coordination (linens, mobility aids, food),
staff shift proposals, and safety-concern flagging (falls, wellbeing
incidents) — never medication administration, clinical diagnosis, care-plan
changes, physical restraint decisions, end-of-life determinations, or
safety-authority overrides.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-care-note` — routine daily observation logging (meals, mood, activity)
- `:schedule-family-visit` — family/visitor visit scheduling coordination
- `:coordinate-supply-request` — non-medication consumable resupply (linens, mobility aids, food stock)
- `:schedule-staff-shift-proposal` — a shift-roster PROPOSAL only, never a final binding assignment
- `:flag-safety-concern` — falls, wellbeing concerns, incidents — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Resident unverified** — the target resident record must exist in the
   store AND be independently `:registered?`/`:verified?` before any
   proposal for it may commit or even escalate. Re-derived from the
   resident's own store record every time, never from the proposal's own
   `:resident-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches medication/dosing/clinical-diagnosis/care-plan-changes/
   physical-restraint/end-of-life/safety-authority territory, is a
   permanent, un-overridable block. Evaluated **unconditionally** on every
   proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing. The scope-excluded term list is deliberately
   qualified (e.g. "medication dosing", "clinical diagnosis", "care plan
   change", not bare keywords) rather than over-broad, so this HARD block
   never collides with the actor's own core valid use case — legitimately
   flagging an observed fall or wellbeing concern via `:flag-safety-concern`
   — a failure mode this ADR's own governor test suite exercises directly
   (`legitimate-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`eldercareops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`eldercareops.store` (MemStore, string-keyed resident directory),
`eldercareops.advisor` (ElderCareAdvisor, mock + a real-LLM seam via
`langchain.model`, plus an `:out-of-scope?` test hook that
deliberately drafts medication/clinical-scope content so the
governor's scope scan can be exercised end to end),
`eldercareops.governor` (ElderCareGovernor), `eldercareops.phase` (0→3
rollout), `eldercareops.operation` (the `langgraph-clj` StateGraph:
intake → advise → govern → decide → commit | hold | request-approval),
`eldercareops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "873" ...}` block, not appended): `:repo`/`:business-id`
  updated from nil to `"cloud-itonami/cloud-itonami-isic-873"`, `:maturity` `:spec`→`:implemented`,
  `:required-technologies` trimmed (`:robotics` removed — coordination-only),
  `:operating-states` updated to match actor state machine (intake/advise/govern/approve/commit/audit).
- Actor repo `cloud-itonami/cloud-itonami-isic-873` scaffolded and
  pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 41 tests containing 121 assertions, 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`eldercareops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for all four
  non-safety ops, always-escalating safety-concern flag, and all four
  HARD-hold scenarios: unregistered resident, unverified resident, non-
  `:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-0520/` (module-shape mirror, ADR-2607152300 —
  verified-redo pattern precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"873"` entry
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition, ISIC 873 as Wave 4 flagship target)
- ADR-2607152300 (0520 lignite, verified-redo pattern, 61% defect incident recovery)
