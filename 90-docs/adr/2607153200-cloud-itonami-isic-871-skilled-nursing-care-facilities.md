# ADR-2607153200: cloud-itonami-isic-871 — Skilled Nursing Care Facilities Coordination

## Status

Accepted. `cloud-itonami-isic-871` promoted from `:spec` (stale placeholder in `kotoba-lang/industry` registry) to `:implemented` in the registry.

## Context

ISIC Rev.4 871 (Residential nursing care facilities — post-acute care, rehabilitation nursing, chronic care) is Wave 4's clinically adjacent specialist target. Unlike ISIC-873 (elderly and disabled care, which is coordination-only routine elderly care), ISIC-871 handles skilled nursing contexts: post-surgical care, complex medication regimens, wound care, IV therapy, catheter management, mobility assistance for rehabilitation. The clinical adjacency is higher, so scope exclusions are **extra-conservative**.

Existing registry entry was a bare placeholder with no actor implementation or repo. This is a fresh, from-scratch scaffold built as the Wave 4 second skilled-care actor under the verified-redo discipline established by ADR-2607152500 (Wave 4 rollout amendment) and exemplified by ADR-2607152300 (ISIC-0520) and ADR-2607152700 (ISIC-873). Module shape is mirrored directly on ISIC-873, domain-adapted for skilled nursing: less permissive scope, more aggressive medication/clinical/nursing-assessment exclusions.

**Scope**: COORDINATION ONLY, clinically conservative. Mirrored on `cloud-itonami-isic-873`'s module shape (advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout, string-keyed resident directory, append-only audit ledger). Absolutely excludes all clinical decision-making, medication/pharmaceutical handling, nursing assessment, care-plan changes, wound care, IV/catheter management, vital signs monitoring, physical restraint, end-of-life decisions, or safety-authority actions — stronger exclusion set than ISIC-873 due to skilled nursing's clinical adjacency.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

The five operations, COORDINATION ONLY:

- `:log-resident-note` — routine daily observation logging (meals, mood, activity, hygiene). **NEVER** a nursing assessment or clinical judgment.
- `:schedule-family-or-guardian-visit` — family/guardian visit scheduling coordination.
- `:coordinate-supply-request` — non-medication consumables **ONLY** (linens, mobility aids, food, incontinence supplies, grooming items). Absolutely no pharmaceuticals, medical devices, dressings, catheters, IVs, or any clinical supplies.
- `:schedule-staff-shift-proposal` — a shift-roster PROPOSAL only, never a final binding assignment.
- `:flag-safety-concern` — falls, wellbeing concerns, incidents — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a proposal that drifts into forbidden scope (see check 3 below), not a separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Resident unverified** — the target resident record must exist in the store AND be independently `:registered?`/`:verified?` before any proposal for it may commit or even escalate. Re-derived from the resident's own store record every time, never from the proposal's own `:resident-id` claim.

2. **Effect not `:propose`** — any proposal whose `:effect` is not `:propose` is, by construction, a claim to directly actuate outside governance. HARD, not merely low-confidence.

3. **Scope exclusion — EXTRA-CONSERVATIVE FOR SKILLED NURSING** — any proposal (regardless of op) outside the closed allowlist, or whose rationale/summary/citations/draft value touches ANY of the following is a permanent, un-overridable block:
   - Medication/pharmaceutical administration, dosing, prescribing, pharmacokinetics
   - Nursing assessment, clinical diagnosis, clinical judgment
   - Care-plan changes or modifications
   - Wound care, dressing changes, wound assessment
   - IV therapy, infusions, intravenous/subcutaneous injections
   - Catheter care (urinary, central line, feeding tube, ostomy management)
   - Vital signs monitoring, blood pressure, heart rate, oxygen saturation, temperature, blood glucose
   - Physical restraint, seclusion, mobility restrictions
   - End-of-life decisions, DNR, advance directives, code status, palliative care
   - Safety-authority overrides (complaint investigation, license enforcement)

Evaluated **unconditionally** on every proposal via case-insensitive substring scan of the proposal's own content (English + Japanese terms) — never trusting the advisor's own framing. The scope-excluded term list is qualified (e.g. "medication dosing", "nursing assessment", not bare keywords) to avoid self-blocking legitimate `:log-resident-note` (e.g. "resident had a fall") — failure mode exercised end-to-end in the governor test suite (`legitimate-safety-concern-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`nursingcareops.phase`'s 0→3 rollout table independently agrees: `:flag-safety-concern` is never a member of any phase's `:auto` set, at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`nursingcareops.store` (MemStore, string-keyed resident directory), `nursingcareops.advisor` (SkilledNursingAdvisor, mock + a real-LLM seam via `langchain.model`, plus an `:out-of-scope?` test hook that deliberately drafts medication/nursing-assessment/clinical scope content so the governor's scope scan can be exercised end to end), `nursingcareops.governor` (SkilledNursingGovernor with extra-conservative scope exclusions), `nursingcareops.phase` (0→3 rollout), `nursingcareops.operation` (the `langgraph-clj` StateGraph: intake → advise → govern → decide → commit | hold | request-approval), `nursingcareops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing `{:id "871" ...}` block, not appended): `:repo`/`:business-id` updated from nil to `"https://github.com/cloud-itonami/cloud-itonami-isic-871"`, `:maturity` `:spec`→`:implemented`, `:required-technologies` trimmed (`:robotics` removed — coordination-only), `:operating-states` updated to match actor state machine (intake/advise/govern/approve/commit/audit).
- Actor repo `cloud-itonami/cloud-itonami-isic-871` scaffolded and pushed to `main`.
- Test suite, run directly by this session (not agent self-report): **`Ran 41 tests containing 121 assertions, 0 failures, 0 errors.`** (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0 warnings. `clojure -M:run` (`nursingcareops.sim` demo) walked all scenarios (phase-1 approval-gated commit, phase-3 auto-commit for all four non-safety ops, always-escalating safety-concern flag, and all HARD-hold scenarios: unregistered resident, unverified resident, non-`:propose` effect, extra-conservative scope-excluded content including medication/nursing-assessment/wound-care/IV/catheter/vital-signs terms) without error.

## References

- `cloud-itonami-isic-873/` (module-shape mirror, ADR-2607152700 — Wave 4 first skilled-care actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"871"` entry
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (Wave definition, ISIC 871 as Wave 4)
- ADR-2607152300 (0520 lignite, verified-redo pattern reference)
- ADR-2607152700 (873 elderly care, parallel skilled-care actor)
