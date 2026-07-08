# ADR-2607085800: `cloud-itonami-isic-9329` (other amusement/recreation n.e.c.) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607071922 (`cloud-itonami-isic-9321`, amusement parks -- structurally closest sibling)
- ADR-2607085700 (`cloud-itonami-isic-9319`, sports-event officiating/timing)
- `cloud-itonami-isic-9329/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-9329` publishes an OSS business blueprint for
other amusement and recreation activities not elsewhere classified:
arcades, escape rooms, recreational fishing/hunting operations. Like
every prior vertical in this fleet, the blueprint text alone is not
an implementation -- this ADR records the governed-actor build that
promotes `cloud-itonami-isic-9329` from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry, the fifty-
eighth vertical built outside ADR-2607032000's original insurance/
real-estate batch.

## Problem

1. `cloud-itonami-isic-9329`'s blueprint described a real-world
   recreation-venue operating model (booking/admission intake,
   jurisdiction safety verification, operation resumption after a
   safety hold) but had no governed-actor implementation: no Store,
   no Governor, no rollout phasing, no tests.
2. The blueprint's own text names only ONE real-world act ("resuming
   operation after a safety-flagged condition") -- this build needed
   the single-actuation shape.
3. This vertical is structurally the closest sibling to
   `cloud-itonami-isic-9321` (`parksafety`, amusement parks/rides),
   which already established a "resume after safety hold" actuation
   shape -- but ISIC 9329's own named example activities (arcades,
   escape rooms, recreational fishing/hunting) have no mechanical
   ride-inspection regime, so this build needed to re-derive its own
   domain-appropriate check family rather than clone `parksafety`'s
   checks verbatim.
4. Real-world escape-room fire incidents have made emergency-egress
   obstruction a genuine, load-bearing regulatory concern for this
   exact venue category -- no dedicated check for this concept
   existed anywhere in this fleet, and it needed to be verified
   absent via grep before being claimed as new.
5. `facility`/9311 already established a real "occupancy exceeds
   capacity" MAXIMUM-ceiling check for sports-facility venues -- this
   build's own occupancy check needed to honestly characterize its
   reuse of that exact concept rather than overclaim novelty.

## Decision

1. **Single-actuation shape.** The blueprint's own README/business-
   model.md/operator-guide.md text consistently names only ONE
   real-world act. Matching `leasing`/`underwriting`/`testlab`/
   `clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`/`polling`/
   `research`/`design`/`sports`/`alliedhealth`/`photo`/
   `personalservice`/`edsupport`/`cultural`/`proserv`/`sportsevent`'s
   single-actuation shape, `high-stakes` is the one-member set
   `#{:actuation/resume-operation}` -- a POSITIVE actuation (committing
   a real resumption record), matching this fleet's majority shape
   (`3600`/`6190` are the two NEGATIVE-actuation exceptions).
2. **Entity and op shape.** Primary entity `venue`. Four ops:
   `:venue/intake`, `:venue/verify`, `:egress/screen`,
   `:actuation/resume-operation` (high-stakes).
3. **`emergency-egress-obstructed-violations` -- the 57th
   unconditional-evaluation grounding, a genuinely new concept.**
   Grep-verified absent as a standalone check (the fleet's one prior
   "egress" reference, `facility.facts`'s own required-EVIDENCE
   checklist item, is a document-on-file requirement, not a live
   obstruction-status check). Grounded in NFPA 101 Life Safety Code
   (Means of Egress) and Germany's Versammlungsstättenverordnung.
   Gates `:egress/screen` and the actuation.
4. **`occupancy-exceeds-capacity-violations` -- an honest, LITERAL
   reuse, not claimed as new.** This exact function (`facility.
   registry/occupancy-exceeds-capacity?`, ISIC 9311's own FIRST
   non-temporal MAXIMUM-ceiling instance) is re-applied here to a
   second, genuinely different venue type -- the 13th instance of
   that family overall, and the first literal re-application of
   `facility.registry`'s own specific check since it was established.
   Gates only the actuation.
5. **Dedicated double-actuation-guard boolean.** `:resumed?` on the
   `venue` record, never a single `:status` value -- the same
   discipline `parksafety.governor`'s own `:reopened?` guard (the
   structurally closest sibling) establishes.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/recreation/store_contract_test.clj`. The per-entity accessor
   is safely named `venue` directly.
7. **Phase 0→3 rollout** -- Phase 3's `:auto` set is
   `{:venue/intake}` only; the actuation is permanently excluded from
   every phase's `:auto` set.
8. **No bespoke domain capability lib** -- this blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-
   specific capability beyond the generic stack.
9. **Mock + LLM advisor pair** -- `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed** -- the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Seventy-third implemented actor in this fleet's registry (72
  implemented immediately before this build).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (emergency-egress-obstructed), grep-verified absent from
  every prior sibling before the claim was finalized.
- Documents an honest, literal reuse of `facility.registry`'s own
  specific MAXIMUM-ceiling check (occupancy-exceeds-capacity), the
  13th instance of that family, applied to a second venue type for
  the first time -- not claimed as new.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/recreation/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 72 → 73, `:blueprint` 13 → 12,
  `:spec` 545 unchanged (sum 630; see Scope note below on the
  `:total` figure).
- Test status: 30 tests / 132 assertions, lint clean, demo verified
  end-to-end (one clean single-actuation lifecycle plus four
  HARD-hold cases).
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing -- 7 tests / 120 assertions,
  all green.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Scope note

This ADR's `:fleet-maturity-before`/`:fleet-maturity-after` `:total`
field (630) reflects the actual observed sum of `:implemented` +
`:blueprint` + `:spec` in `registry.edn` at build time, rather than
`docs/cloud-itonami.md`'s "Total entries: 643" figure -- the same
clarification ADR-2607085700 (`sportsevent`/9319) already recorded:
that 643 figure tracks a separate, pre-existing, wider ISIC-class/
group count, not per-vertical maturity accounting. Not introduced or
worsened by this build; not in scope to reconcile here.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Cloning `parksafety.governor`'s checks verbatim (inspection-not-passed, operators-insufficient) | ISIC 9329's named example activities have no mechanical ride-inspection regime and no per-ride staffing minimum -- the real concerns are assembly-venue fire/life-safety, warranting a re-derived check family |
| Framing `occupancy-exceeds-capacity?` as a new concept for this venue type | It is the exact same real-world concept and field comparison `facility.registry` already established; honest reuse characterization matches this fleet's precedent-verification discipline |
| A dual-actuation shape (splitting "resuming operation" by hazard type) | The blueprint's own text consistently names only ONE real-world act |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-9329/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9329/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9329/docs/business-model.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9321/src/parksafety/governor.cljc` (structurally closest sibling)
- `orgs/cloud-itonami/cloud-itonami-isic-9311/src/facility/registry.cljc` (`occupancy-exceeds-capacity?` origin)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"9329"`)
