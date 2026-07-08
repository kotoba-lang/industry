# ADR-2607086100: `cloud-itonami-isic-9499` (other membership organizations n.e.c.) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607072715 (`cloud-itonami-isic-8730`, eldercare -- `care-plan-review-overdue?` origin)
- ADR-2607085900 (`cloud-itonami-isic-9312`, sports clubs -- governor-name-collision survey origin)
- ADR-2607086000 (`cloud-itonami-isic-9492`, political organizations -- structurally closest sibling; survey re-confirmed and narrowed)
- `cloud-itonami-isic-9499/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-9499` publishes an OSS business blueprint for
activities of other membership organizations not elsewhere
classified: civic/social clubs, consumer organizations, environmental
advocacy groups. Like every prior vertical in this fleet, the
blueprint text alone is not an implementation -- this ADR records the
governed-actor build that promotes `cloud-itonami-isic-9499` from
`:blueprint` to `:implemented` in the `kotoba-lang/industry` registry,
the sixty-first vertical built outside ADR-2607032000's original
insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-9499`'s blueprint described a real-world
   nonprofit-membership-organization operating model (member
   enrollment intake, tax-exempt-status/political-activity-
   restriction verification, position publication) but had no
   governed-actor implementation: no Store, no Governor, no rollout
   phasing, no tests.
2. The blueprint's own text names only ONE real-world act
   ("publishing a public position on the organization's behalf") --
   this build needed the single-actuation shape.
3. This is the LAST genuinely clean `:blueprint`-tier candidate
   identified by `sportsclub`/9312's own governor-name-collision
   survey (re-confirmed and narrowed by `partyops`/9492's own ADR).
   This build needed to re-verify that finding was still accurate
   before proceeding, and to record the survey's exhaustion for the
   next vertical-selection turn.
4. `partyops`/9492 (the structurally closest sibling, sharing the
   same "publish a position" actuation verb) had just established a
   campaign-finance-disclosure concern for political parties -- this
   build needed a genuinely DIFFERENT regulatory hook for civic/
   consumer/advocacy nonprofits, not a relabeled copy.

## Decision

1. **Single-actuation shape.** The blueprint's own README/business-
   model.md/operator-guide.md text consistently names only ONE
   real-world act. Matching every prior single-actuation sibling's
   shape, `high-stakes` is the one-member set `#{:actuation/publish-
   position}` -- a POSITIVE actuation, matching this fleet's majority
   shape (`3600`/`6190` are the two NEGATIVE-actuation exceptions).
2. **Candidate confirmation.** Re-verified `sportsclub`/9312's and
   `partyops`/9492's survey: ISIC 9499's own `:membership-governance-
   governor` has no collision with any already-implemented sibling.
3. **Entity and op shape.** Primary entity `position`. Four ops:
   `:member/intake`, `:position/verify`, `:taxstatus/screen`,
   `:actuation/publish-position` (high-stakes). "Member-benefit
   administration" (also named in the Offer) deliberately excluded
   from this R0's governed op surface.
4. **`tax-exempt-status-risk-unresolved-violations` -- the 60th
   unconditional-evaluation grounding, a genuinely new concept.**
   Grep-verified absent (zero hits for "tax-exempt"/"501(c)"/
   "lobbying"/"charitable-status" across every prior sibling,
   INCLUDING against `partyops.facts` itself). Grounded in US IRC
   §501(c)(3)/(c)(4), UK Charity Commission CC9, Japan's public-
   interest-corporation political-activity restrictions, Germany's
   Abgabenordnung §52 Gemeinnützigkeit. Gates `:taxstatus/screen` and
   the actuation.
5. **`position-review-overdue?` -- an honest reuse of `eldercare`/
   8730's own periodic-review-overdue temporal shape, not claimed as
   new.** The 14th instance of this fleet's MAXIMUM-ceiling family
   overall (`recreation`/9329 was the 13th). Gates only the actuation.
6. **Dedicated double-actuation-guard boolean.** `:published?` on the
   `position` record, never a single `:status` value.
7. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/memberorg/store_contract_test.clj`. The per-entity accessor
   is safely named `position` directly.
8. **Phase 0→3 rollout** -- Phase 3's `:auto` set is
   `{:member/intake}` only; the actuation is permanently excluded
   from every phase's `:auto` set.
9. **No bespoke domain capability lib** -- this blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-
   specific capability beyond the generic stack.
10. **No `blueprint.edn` field-sync fixes needed** -- the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Seventy-sixth implemented actor in this fleet's registry (75
  implemented immediately before this build).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (tax-exempt-status-risk-unresolved), grep-verified absent
  from every prior sibling (including `partyops`/9492) before the
  claim was finalized.
- Documents an honest reuse of `eldercare`/8730's own periodic-
  review-overdue temporal shape (position-review-overdue, the 14th
  MAXIMUM-ceiling instance), not claimed as new.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/memberorg/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 75 → 76, `:blueprint` 10 → 9,
  `:spec` 545 unchanged (sum 630; see Scope note below on the
  `:total` figure).
- Test status: 30 tests / 130 assertions, lint clean, demo verified
  end-to-end (one clean single-actuation lifecycle plus four
  HARD-hold cases).
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing -- 7 tests / 123 assertions,
  all green.
- **This build EXHAUSTS the fleet-wide governor-name-collision
  survey.** As of this promotion, ALL remaining `:blueprint`-tier
  candidates (`7220`, `8522`, `8549`, `9411`, `9512`, `9522`, `9523`,
  `9524`, `9529`) are confirmed blocked by an exact governor-name
  collision with an already-implemented sibling
  (`research-integrity-governor`/`curriculum-safeguarding-governor`/
  `instruction-integrity-governor`/`association-governance-governor`/
  `repair-shop-governor` ×5). **The next vertical-selection turn
  needs a different strategy**: either a fresh, deliberate look at
  whether a legitimately distinct governor name could still be
  justified for one of the blocked candidates (a more invasive
  judgment call than this fleet has made so far -- diverging from the
  blueprint's own published `:itonami.blueprint/governor` field is
  not something any prior build in this fleet has done), or waiting
  for newly-registered `:blueprint`-tier entries to appear in the
  registry from other concurrent activity.

## Scope note

This ADR's `:fleet-maturity-before`/`:fleet-maturity-after` `:total`
field (630) reflects the actual observed sum of `:implemented` +
`:blueprint` + `:spec` in `registry.edn` at build time, rather than
`docs/cloud-itonami.md`'s "Total entries: 643" figure -- the same
clarification ADR-2607085700's, ADR-2607085800's, ADR-2607085900's
and ADR-2607086000's own scope notes already recorded: that 643
figure tracks a separate, pre-existing, wider ISIC-class/group count,
not per-vertical maturity accounting. Not introduced or worsened by
this build; not in scope to reconcile here.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Framing `tax-exempt-status-risk-unresolved?` as a variant of `partyops`/9492's campaign-finance-disclaimer-missing concept | Genuinely distinct real-world concerns (tax-exempt-status risk vs. election-communication disclosure); grep-verified `partyops.facts` has zero mention of tax-exempt status |
| Framing `position-review-overdue?` as a new concept | Structurally identical to `eldercare.registry/care-plan-review-overdue?`'s own shape; honest reuse characterization matches this fleet's precedent-verification discipline |
| A dual-actuation shape | The blueprint's own text consistently names only ONE real-world act |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-9499/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9499/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9499/docs/business-model.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8730/src/eldercare/registry.cljc` (`care-plan-review-overdue?` origin)
- `orgs/cloud-itonami/cloud-itonami-isic-9492/src/partyops/governor.cljc` (structurally closest sibling)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"9499"`)
