# ADR-2607153000: cloud-itonami-isic-879 — Other Residential Care Operations Coordination

## Status

Accepted. `cloud-itonami-isic-879` promoted from `:spec` (placeholder in `kotoba-lang/industry` registry) to `:implemented` in the registry.

## Context

ISIC Rev.4 879 (Other residential care activities not elsewhere classified — children's homes, shelters, halfway houses, non-medical residential rehabilitation facilities) is Wave 4's **second vertical actor** in the residential-care family. Unlike 873's focus on elderly/disabled care, 879 spans broader populations potentially including minors in state custody or protective settings. This is the third Wave-4 actor scaffold (following isic-873 and isic-889) built under the verified-redo discipline established by ADR-2607152500 (Wave 4 rollout amendment) and exemplified by ADR-2607152300 (ISIC-0520 lignite mining).

**Scope**: COORDINATION ONLY, mirrored closely on the sibling `cloud-itonami-isic-873` (Residential care for elderly and disabled)'s module shape (advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout, string-keyed resident directory, append-only audit ledger). Domain-adapted for broader residential care: daily resident-note logging, family/guardian visit scheduling (accounting for minors in state custody), consumable supply coordination (linens, mobility aids, food, clothing), staff shift proposals, and safety-concern flagging (falls, wellbeing incidents, child protection concerns) — never medication administration, clinical diagnosis, care-plan changes, physical restraint decisions, guardianship/custody decisions, disciplinary actions, end-of-life determinations, or safety-authority overrides.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-resident-note` — routine daily observation logging (meals, mood, activity, hygiene)
- `:schedule-family-or-guardian-visit` — family/visitor OR guardian visit scheduling coordination (adapted for minors in state care)
- `:coordinate-supply-request` — non-medication consumable resupply (linens, mobility aids, food stock, clothing, hygiene supplies)
- `:schedule-staff-shift-proposal` — a shift-roster PROPOSAL only, never a final binding assignment
- `:flag-safety-concern` — falls, wellbeing concerns, child protection incidents — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a proposal that drifts into forbidden scope (see check 3 below), not a separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Resident unverified** — the target resident record must exist in the store AND be independently `:registered?`/`:verified?` before any proposal for it may commit or even escalate. Re-derived from the resident's own store record every time, never from the proposal's own `:resident-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not `:propose` is, by construction, a claim to directly actuate outside governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the closed allowlist, or whose rationale/summary/citations/draft value touches medication/clinical-diagnosis/care-plan-changes/physical-restraint/guardianship-custody/disciplinary-action/end-of-life/safety-authority territory, is a permanent, un-overridable block. Evaluated **unconditionally** on every proposal via a lower-cased substring scan of the proposal's own content (English + Japanese term list, **carefully qualified to avoid false positives on legitimate safety-concern observations**) — never trusting the advisor's own framing. The scope-excluded term list is deliberately qualified (e.g. "medication dosing", not bare "med", "physical restraint" not bare "restraint") so this HARD block never collides with the actor's own core valid use case — legitimately flagging a child protection concern or observed fall via `:flag-safety-concern` — a failure mode this ADR's own governor test suite exercises directly (`test-legitimate-safety-concern-not-blocked`).

Escalation (SOFT, always human sign-off, only reached when the governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`rescare.phase`'s 0→3 rollout table independently agrees: `:flag-safety-concern` is never a member of any phase's `:auto` set, at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`rescare.store` (MemStore, string-keyed resident directory), `rescare.advisor` (ResCareAdvisor, mock + a real-LLM seam via `langchain.model`, plus an `:out-of-scope?` test hook that deliberately drafts medication/clinical-scope content so the governor's scope scan can be exercised end to end), `rescare.governor` (ResCareGovernor), `rescare.phase` (0→3 rollout), `rescare.operation` (the `langgraph-clj` StateGraph: intake → advise → govern → decide → commit | hold | request-approval), `rescare.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing `{:id "879" ...}` block, not appended): `:repo`/`:business-id` updated from nil to `"https://github.com/cloud-itonami/cloud-itonami-isic-879"`, `:maturity` `:spec`→`:implemented`, `:required-technologies` trimmed (`:robotics` removed — coordination-only), `:operating-states` updated to match actor state machine (intake/advise/govern/decide/commit/escalate/audit).
- Actor repo `cloud-itonami/cloud-itonami-isic-879` scaffolded and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 32 tests containing 109 assertions, 0 failures, 0 errors.`**
  (`clojure -M:dev:test`). `clojure -M:lint`: 0 errors, 0 warnings (info-level string-wrapping notes only).
  `clojure -M:run` (`rescare.sim` demo) walked all scenarios (phase-1 approval-gated commit, phase-3 auto-commit for all four non-safety ops, always-escalating safety-concern flag, and all four HARD-hold scenarios: unregistered resident, unverified resident, non-`:propose` effect, scope-excluded content — including child-protection-concern observations and guardianship-related terminology — WITHOUT incorrectly blocking legitimate safety flagging) without error.

## References

- `cloud-itonami-isic-873/` (module-shape mirror, ADR-2607152700 — first Wave-4 landing, residential elderly/disabled care)
- `cloud-itonami-isic-889/` (parallel Wave-4 landing, non-residential social work)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"879"` entry
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition, ISIC 879 as Wave 4 target)
- ADR-2607152300 (0520 lignite, verified-redo pattern, 61% defect incident recovery)
