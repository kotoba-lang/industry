# ADR-2607084000: `cloud-itonami-isic-7010` (activities of head offices) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607083600 (`cloud-itonami-isic-9609`, other personal service activities n.e.c.)
- ADR-2607083800 (`cloud-itonami-isic-8550`, educational support activities)
- `cloud-itonami-isic-7010/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-7010` publishes an OSS business blueprint for
activities of head offices: overseeing and administering other units
of the same enterprise or group (strategic planning, budgeting,
group-wide policy). Like every prior vertical in this fleet, the
blueprint text alone is not an implementation — this ADR records the
governed-actor build that promotes `cloud-itonami-isic-7010` from
`:blueprint` to `:implemented` in the `kotoba-lang/industry`
registry, the fifty-second vertical built outside ADR-2607032000's
original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-7010`'s blueprint described a real-world head-
   office operating model (unit intake, group-report verification,
   group budget/policy allocation finalization) but had no governed-
   actor implementation: no Store, no Governor, no rollout phasing,
   no tests.
2. The blueprint's own text names only ONE real-world act
   ("finalizing a group budget/policy allocation") — this build
   needed the single-actuation shape.
3. Head-office/group-oversight activities carry a genuine, real-world
   compliance concern distinct from every prior sibling: inter-unit
   transfer pricing must fall WITHIN an arm's-length range (both an
   upper AND lower bound), not merely under a single ceiling like
   this fleet's existing MAXIMUM-ceiling check family, nor merely
   above a single floor like its MINIMUM-threshold family.
4. This fleet already has a "spend/allocation exceeds authorized
   ceiling" concept (`advertising`/7310's `media-spend-exceeds-
   authorized-budget?`, itself the 7th MAXIMUM-ceiling instance) —
   this build's own budget-allocation check needed to honestly
   characterize its own reuse rather than overclaim novelty.

## Decision

1. **Single-actuation shape.** Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`/`polling`/
   `research`/`design`/`sports`/`alliedhealth`/`photo`/
   `personalservice`/`edsupport`'s single-actuation shape, `high-
   stakes` is the one-member set `#{:actuation/finalize-allocation}`.
2. **Entity and op shape.** Primary entity `unit` (subsidiary/
   business unit). Three ops: `:unit/intake`, `:report/verify`,
   `:actuation/finalize-allocation` (high-stakes). No dedicated
   screening op — both distinctive checks are ground-truth numeric
   recomputes scoped directly to the actuation, matching
   `advertising`/7310's and `navigator`/8691's precedent.
3. **`transfer-price-outside-arms-length-range?` — the first
   instance of a new range-bound check shape.** Grep-verified absent
   (zero hits for "transfer-pric"/"arms-length"/"arm's-length" across
   every prior sibling). Recomputes `(or (< price range-min) (> price
   range-max))` directly from the unit's own recorded fields — a
   two-sided check, distinct from this fleet's existing single-bound
   families. Grounded in OECD Transfer Pricing Guidelines' arm's-
   length principle, US IRC §482, Japan's 租税特別措置法第66条の4, and
   Germany's AStG §1. Gates only `:actuation/finalize-allocation`.
4. **`budget-allocation-exceeds-authorized-limit?` — an honest tenth
   MAXIMUM-ceiling instance, not claimed as new.** `facility`/
   `school`/`card`/`recovery`/`care`/`navigator`/`advertising`/
   `nursing`/`holdco` established the first nine instances; this
   build's reuse is the tenth, closely analogous to `advertising.
   registry/media-spend-exceeds-authorized-budget?`'s own shape.
   Gates only `:actuation/finalize-allocation`.
5. **Dedicated double-actuation-guard boolean.** `:allocation-
   finalized?` on the `unit` record, never a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/headoffice/store_contract_test.clj`. The per-entity accessor
   is safely named `unit` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:unit/intake}`
   only; the actuation is permanently excluded from every phase's
   `:auto` set.
8. **No bespoke domain capability lib** — this blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-
   specific capability beyond the generic stack.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed** — the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Sixty-sixth actor in this fleet (65 implemented before this
  build).
- Introduces a genuinely NEW range-bound check shape (first instance,
  grep-verified absent from every prior sibling), distinct from both
  existing single-bound check families in this fleet.
- Documents an honest TENTH instance of the MAXIMUM-ceiling check
  family, not claimed as new.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/headoffice/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 65 → 66, `:blueprint` 19 → 18,
  `:spec` 546 unchanged, total 643.
- Test status: 34 tests / 134 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 114 assertions,
  all green, no pre-existing fixture referenced ISIC "7010".
- This build's `deps.edn` used the CURRENT `kotoba-lang/langgraph`/
  `langchain` coordinates from the start (see `holdco`/6420's own
  ADR-0001 for the upstream-rename context this build inherited).
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Framing `transfer-price-outside-arms-length-range?` as a MAXIMUM-ceiling reuse (upper bound only) | Real transfer-pricing law requires a price to fall WITHIN a range on both sides, not merely under a ceiling — a single-bound characterization would misrepresent the real-world compliance requirement |
| A dedicated screening op for either distinctive check | Both checks are pure ground-truth recomputes needing no proposal inspection, matching `advertising`/7310's and `navigator`/8691's precedent that such checks are scoped directly to the actuation |
| A dual-actuation shape (budget + policy as two acts) | The blueprint's own text consistently names only ONE real-world act |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7010/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7010/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7010/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"7010"`)
