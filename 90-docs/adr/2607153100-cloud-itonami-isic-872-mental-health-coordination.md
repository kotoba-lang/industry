# ADR-2607153100: cloud-itonami-isic-872 — Mental Health/SUD Residential Care Coordination

## Status

Accepted. `cloud-itonami-isic-872` promoted from `:spec` to `:implemented` in the registry.

## Context

ISIC Rev.4 872 (Residential care activities for mental retardation, mental health and substance abuse) is Wave 4's second implementation — a coordination-only actor for residential care facilities serving individuals with intellectual disabilities, mental health conditions, and substance use disorders, following the verified-redo discipline established by ADR-2607152500 and exemplified by isic-879.

**Scope**: COORDINATION ONLY, mirrored on the sibling `cloud-itonami-isic-879` module shape (advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout, string-keyed resident directory, append-only audit ledger). Domain-adapted for mental health/substance abuse: daily care-note logging, family/guardian visit scheduling, consumable supply coordination (non-medication), staff shift proposals, and safety-concern flagging (behavioral incidents, wellbeing observations) — never medication administration, clinical diagnosis, care-plan changes, physical restraint decisions, guardianship/custody decisions, disciplinary actions, end-of-life determinations, or safety-authority overrides.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-resident-note` — routine daily observation logging (activities, mood, participation)
- `:schedule-family-or-guardian-visit` — family/visitor visit scheduling coordination
- `:coordinate-supply-request` — non-medication consumable resupply (linens, mobility aids, food stock)
- `:schedule-staff-shift-proposal` — a shift-roster PROPOSAL only, never a final binding assignment
- `:flag-safety-concern` — behavioral incidents, wellbeing concerns, including self-harm risk signals — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a proposal that drifts into forbidden scope (see check 3 below), not a separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Resident unverified** — the target resident record must exist in the store AND be independently `:registered?`/`:verified?` before any proposal for it may commit or even escalate. Re-derived from the resident's own store record every time, never from the proposal's own `:resident-id` claim.

2. **Effect not `:propose`** — any proposal whose `:effect` is not `:propose` is, by construction, a claim to directly actuate outside governance. HARD, not merely low-confidence.

3. **Scope exclusion** — any proposal (regardless of op) outside the closed allowlist, or whose rationale/summary/citations/draft value touches medication/dosing/clinical-diagnosis/care-plan-changes/physical-restraint/guardianship/disciplinary/end-of-life/safety-authority territory, is a permanent, un-overridable block. Evaluated **unconditionally** on every proposal via a lower-cased substring scan of the proposal's own content (English + Japanese term list) — never trusting the advisor's own framing. The scope-excluded term list is deliberately qualified (e.g. "medication dosing", "clinical diagnosis", "care plan change", not bare keywords) rather than over-broad, so this HARD block never collides with the actor's own core valid use case — legitimately flagging an observed behavioral concern or self-harm risk via `:flag-safety-concern` — a failure mode this ADR's own governor test suite exercises directly.

Escalation (SOFT, always human sign-off, only reached when the governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`mhscare.phase`'s 0→3 rollout table independently agrees: `:flag-safety-concern` is never a member of any phase's `:auto` set, at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`mhscare.store` (MemStore, string-keyed resident directory), `mhscare.advisor` (MhsCareAdvisor, mock + real-LLM seam), `mhscare.governor` (MhsCareGovernor), `mhscare.phase` (0→3 rollout), `mhscare.operation` (the langgraph-clj StateGraph: intake → advise → govern → decide → commit | hold | escalate), `mhscare.sim` (demo driver).

## Consequences

- Registry entry 872 updated in place (exact-text edit via GitHub Contents API sha-guard): `:repo` updated from nil to `"cloud-itonami/cloud-itonami-isic-872"`, `:maturity` `:spec`→`:implemented`, `:robotics` removed from `:required-technologies`.
- Actor repo `cloud-itonami/cloud-itonami-isic-872` scaffolded and pushed to `main`.
- Test suite, run directly: **`Ran 47 tests containing 92 assertions, 0 failures, 0 errors.`** (`clojure -M test_runner.clj`). Tests cover: store protocol, all 5 advisor ops, all 3 HARD governor checks, scope-exclusion violations (medication, clinical diagnosis, care-plan, restraint, end-of-life, guardianship, disciplinary, safety-authority), phase 0→3 auto-commit rules, operation workflow (intake→advise→govern→decide→commit/hold/escalate), ledger append.
- Registry update independently verified via fresh GitHub Contents API fetch: entry 872 now shows `:maturity :implemented` and `:repo "cloud-itonami/cloud-itonami-isic-872"`.

## References

- `cloud-itonami-isic-872` GitHub repo: https://github.com/cloud-itonami/cloud-itonami-isic-872
- `kotoba-lang/industry` registry entry 872 (updated)
- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607152700 (isic-873 eldercare, module-shape reference)
- ADR-2607121000 (Wave definition)
