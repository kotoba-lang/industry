# ADR-2620773000: cloud-itonami-isic-7730 — Machinery / Equipment Rental Operations Coordination

## Status

Accepted. `cloud-itonami-isic-7730` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 7730 (Renting and leasing of other machinery, equipment and
tangible goods n.e.c.) is a Wave 4 (human-facing/personal-services)
target under ADR-2607121000's reverse-toposort rollout plan and
ADR-2607152500's Wave 4 rollout amendment (Wave 4 authorized to
proceed in parallel with Wave 3, with an explicit person-facing-service
safety guardrail). Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began, per this
fleet's ID/name-mismatch caution: the live `{:id "7730" ...}` entry's
`:name` was "Renting and leasing of other machinery, equipment and
tangi..." (genuinely truncated), matching (once un-truncated) "Renting
and leasing of other machinery, equipment and tangible goods n.e.c." —
the residual equipment-rental category (construction/event/agricultural
equipment), distinct from sibling classes 7710 (motor vehicles), 7721
(recreational sports goods), and 7722 (video tapes and disks). The
truncated `:name` is fixed as part of this ADR's exact-block registry
edit. A separate, redundant 3-digit group entry `{:id "773" ...}`
exists at `:maturity :spec` with a similarly truncated name — an
earlier data-seeding-pass artifact, deliberately NOT touched by this
ADR (only the `"7730"` class-level block was edited). No repository
existed at either `cloud-itonami/cloud-itonami-isic-7730` or the stale
`gftdcojp/cloud-itonami-N7730` placeholder before this work (404
confirmed for both).

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-5520` (Camping grounds/RV parks/trailer parks,
ADR-2615100000)'s verified Wave 4 module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout, string-keyed
asset directory, append-only audit ledger). Domain-adapted for
construction/event/agricultural equipment rental back-office
operations: rental-record logging (checkout/return/inspection-note
data), post-return maintenance-inspection scheduling, rental-fleet
restock/replacement coordination, and equipment-safety-concern
flagging (defect, damage, malfunction) — never directly finalizing an
equipment-safety-clearance decision (e.g. certifying a returned unit
as safe to re-rent without inspection) or overriding an
equipment-safety-authority decision.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-rental-record` — checkout/return/inspection-note data logging
- `:schedule-maintenance-inspection` — post-return inspection/maintenance scheduling proposal
- `:coordinate-fleet-restock` — rental-fleet procurement/replacement coordination proposal
- `:flag-equipment-safety-concern` — surface a defect/damage/malfunction concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the Wave 4 person-facing-service safety guardrail
(ADR-2607152500): equipment rental (power tools, construction/event
equipment) has a direct end-user-safety dimension — defective or
uninspected equipment causing injury — so the closed op allowlist
NEVER includes any op that directly finalizes an
equipment-safety-clearance decision. Every op above is `:effect
:propose` only, and the one "flag a concern" op
(`:flag-equipment-safety-concern`) always escalates to human sign-off
and is never a member of any phase's `:auto` set.

1. **Asset unverified** — the target rental-asset (equipment unit)
   record must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit
   or even escalate. Re-derived from the asset's own store record
   every time, never from the proposal's own `:asset-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing an equipment-safety-clearance decision
   (e.g. certifying a returned unit safe to re-rent without
   inspection) or overriding an equipment-safety-authority decision,
   is a permanent, un-overridable block. Evaluated **unconditionally**
   on every proposal via a lower-cased substring scan of the
   proposal's own content (English + Japanese term list) — never
   trusting the advisor's own framing. The scope-excluded term list is
   deliberately phrased as finalization/execution verb phrases (e.g.
   "certify safe to re-rent without inspection", "finalize the safety
   clearance", not the bare noun "safety" alone), so this HARD block
   never collides with the actor's own core valid use case or with the
   other three ops' own legitimate disclaimer copy (which mentions
   concepts like re-rent eligibility and human sign-off without
   claiming to finalize them) — a failure mode this ADR's own test
   suite exercises directly with two dedicated regression guards:
   `equiprentalops.governor-test/legitimate-equipment-safety-concern-is-not-scope-excluded`
   and `equiprentalops.governor-test/guest-facing-default-advisor-proposals-never-scope-excluded-end-to-end`,
   plus `equiprentalops.advisor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
   (parameterized over all four ops and six realistic patches, asserting
   the default mock advisor never trips its own governor's scope-exclusion
   scan — the exact self-tripping bug class multiple sibling agents in
   this fleet have independently hit and fixed).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-equipment-safety-concern` — always, regardless of confidence.
- `:coordinate-fleet-restock` above a $2000 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`equiprentalops.phase`'s 0→3 rollout table independently agrees:
`:flag-equipment-safety-concern` is never a member of any phase's
`:auto` set, at any phase — two layers, not one, enforce the same
invariant (exercised directly by
`equipment-safety-concern-never-in-any-auto-set`).

### 3. Module shape

`equiprentalops.store` (MemStore, string-keyed rental-asset
directory), `equiprentalops.advisor` (EquipRentalOpsAdvisor, mock + a
real-LLM seam, plus an `:out-of-scope?` test hook that deliberately
drafts re-rent-without-inspection-scope content so the governor's
scope scan can be exercised end to end), `equiprentalops.governor`
(EquipRentalGovernor), `equiprentalops.phase` (0→3 rollout),
`equiprentalops.operation` (the `langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | request-approval),
`equiprentalops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "7730" ...}` block only, not appended, not touching the
  separate `"773"` group entry): `:name` un-truncated to "Renting and
  leasing of other machinery, equipment and tangible goods n.e.c.",
  `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-N7730` /
  `cloud-itonami-N7730` to
  `https://github.com/cloud-itonami/cloud-itonami-isic-7730` /
  `cloud-itonami-isic-7730`, `:maturity` `:spec`→`:implemented`,
  `:required-technologies` trimmed from the stale placeholder set
  (`:robotics`/`:labor` removed — coordination-only) to `[:identity
  :forms :dmn :bpmn :audit-ledger]`, `:operating-states` updated to
  match the actor state machine.
- Actor repo `cloud-itonami/cloud-itonami-isic-7730` scaffolded (fresh
  — no prior repository existed at either the stale `gftdcojp`
  placeholder or the real `cloud-itonami` org target) and pushed to
  `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 49 tests containing 160 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`equiprentalops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety ops, always-escalating equipment-safety-concern flag,
  always-escalating over-threshold fleet-restock, and all four
  HARD-hold scenarios: unregistered asset, unverified asset, non-
  `:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-5520/` (module-shape mirror, ADR-2615100000)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"7730"` entry
- ADR-2607152500 (Wave 4 rollout amendment, person-facing-service safety guardrail)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan)
