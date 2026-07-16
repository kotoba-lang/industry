# ADR-2607081700: `cloud-itonami-isic-8569` (community learning support) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607081300 (`cloud-itonami-isic-2610`, semiconductor fab)
- ADR-2607081400 (`cloud-itonami-isic-3512`, community renewable energy)
- ADR-2607081500 (`cloud-itonami-isic-8810`, community care coordination)
- ADR-2607081600 (`cloud-itonami-isic-8691`, health access navigation)
- `cloud-itonami-isic-8569/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-8569` publishes an OSS business blueprint for
community learning support: tutoring operations, attendance follow-up,
scholarship guidance and school-community case management. Like every
prior vertical in this fleet, the blueprint text alone is not an
implementation — this ADR records the governed-actor build that
promotes `cloud-itonami-isic-8569` from `:blueprint` to `:implemented`
in the `kotoba-lang/industry` registry, the thirty-eighth vertical
built outside ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-8569`'s blueprint described a real-world
   learning-support operating model (learner intake, study-plan
   assessment, dropout-risk screening, support-plan finalization,
   guardian contact) but had no governed-actor implementation: no
   Store, no Governor, no rollout phasing, no tests.
2. The blueprint's own Core Contract and Trust Controls name concrete
   hold conditions (cannot expose learner data, cannot make high-
   stakes placement decisions, cannot contact guardians without policy
   approval) and two high-stakes acts (finalizing a support plan,
   contacting a guardian) — these needed a concrete, testable HARD-
   check mapping, not just prose.
3. `blueprint.edn` carried a stale pre-rename `:itonami.blueprint/id`
   (`"cloud-itonami-8569"`, missing the `isic-` infix) and was
   entirely missing `:required-technologies`/`:optional-technologies`,
   both needing reconciliation against the `kotoba-lang/industry`
   registry's own stated values before the promotion could be
   considered internally consistent.
4. The blueprint's operator-guide mentions an "escalation path for
   safeguarding concerns," but this fleet already has two distinct
   "safeguarding"-shaped unconditional-evaluation checks (`congregation`'s
   matter-level allegation, `care`'s check-in-surfaced signal) — this
   build needed to decide whether to add a third or design a genuinely
   distinct, well-grounded concept instead.

## Decision

1. **Entity and op shape.** Primary entity `learner`. Five ops:
   `:learner/intake`, `:studyplan/verify`, `:dropout-risk/screen`,
   `:actuation/finalize-support-plan` (high-stakes), and `:actuation/
   contact-guardian` (high-stakes) — a dual-actuation-on-one-entity
   shape grounded directly in the blueprint's own Core Contract and
   Offer ("study-plan generation with human review", "guardian
   communication queue").
2. **`learner-to-tutor-ratio-exceeds-maximum?` — 5th ratio-based
   sufficiency check, MAXIMUM direction.** Following `leasing` (1st,
   MINIMUM-floor), `behavioral` (2nd, MAXIMUM-ceiling), `union` (3rd,
   MINIMUM-floor) and `fab` (4th, MINIMUM-floor), this applies the
   same quotient-comparison shape — MAXIMUM direction, like
   `behavioral`'s — to a learner's own cohort's learner count divided
   by its own tutor count against a fixed policy ceiling (12 learners
   per tutor), gating only `:actuation/finalize-support-plan`.
3. **`dropout-risk-unresolved-violations` — 36th unconditional-
   evaluation screening grounding, deliberately NOT named
   "safeguarding."** Every prior sibling's `governor.cljc` was grepped
   for "dropout" before this claim was finalized — zero hits,
   confirming genuine novelty. Rather than reuse the "safeguarding"
   word for a third, semantically distinct concept, this check is
   named and grounded directly in the blueprint's own
   `:itonami.blueprint/social-impact` tag `:dropout-prevention`,
   keeping three genuinely different real-world concerns (a
   congregation's matter-level allegation, a care case's check-in-
   surfaced neglect signal, a learner's academic dropout risk) from
   blurring under one label. Gates both `:dropout-risk/screen` and
   `:actuation/finalize-support-plan`.
4. **Dedicated double-actuation-guard booleans.** `:support-plan-
   finalized?`/`:guardian-contacted?` on the `learner` record, never a
   single `:status` value.
5. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/learning/store_contract_test.clj`. The per-entity accessor is
   safely named `learner` directly — not a Clojure special form,
   confirmed before writing `store.cljc` (the same non-collision
   pattern `navigator`'s `seeker` had, unlike `care`'s `case` entity
   which needed a `case-of` rename).
6. **Phase 0→3 rollout** — Phase 3 `:auto` set is `{:learner/intake}`
   only; both actuations are permanently excluded from every phase's
   `:auto` set.
7. **No bespoke domain capability lib** — runs on the generic
   robotics/identity/forms/dmn/bpmn/audit-ledger stack.
8. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
9. **`blueprint.edn` field-sync fixes** — corrected the stale
   `:itonami.blueprint/id` and added the missing `:required-
   technologies`/`:optional-technologies` fields to match the
   `kotoba-lang/industry` registry's own entry for `"8569"`.

## Consequences

- Fifty-second actor in this fleet (51 implemented before this build).
- Confirms the ratio-based sufficiency check family generalizes to a
  fifth instance, genuinely distinct domain (tutor-load capacity).
- Establishes a genuinely new unconditional-evaluation-screening
  concept (dropout-risk), grep-verified absent from every prior
  sibling and deliberately distinguished, by naming choice, from two
  existing "safeguarding"-shaped concepts.
- `MemStore` ‖ `DatomicStore` parity proven by contract test.
- Two pre-existing `blueprint.edn` inconsistencies fixed as in-scope
  minor consistency work.
- Fleet maturity: `:implemented` 51 → 52, `:blueprint` 33 → 32,
  `:spec` 546 unchanged, total 643.
- Test status: 36 tests / 174 assertions, lint clean, demo verified
  end-to-end.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Name the screening check "safeguarding-concern-unresolved" (echoing the operator-guide's own phrasing) | A third distinct real-world concern under the same label would blur `congregation`'s and `care`'s already-distinct concepts; grounding it in the blueprint's own `:dropout-prevention` tag keeps it both novel and honestly-named |
| A single "learner-safety" check merging tutor-ratio and dropout-risk concerns | Tutor-load ratio is a ground-truth numeric recompute; dropout-risk status is an unconditionally-evaluated flag needing the screening op to self-hold — merging loses that property |
| Model scholarship/support-service matching as a third actuation | The blueprint's Offer names it as a matching/recommendation function, not clearly a standalone real-world commitment act — deferred to a follow-up op |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8569/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8569/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8569/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"8569"`)
