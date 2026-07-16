# ADR-2607086600: `cloud-itonami-isic-9512` (repair of communication equipment) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607071849 (`cloud-itonami-isic-9521`, repairshop/consumer electronics -- structurally closest sibling; shared governor name)
- ADR-2607085900 (`cloud-itonami-isic-9312`, sports clubs -- governor-name-collision survey origin)
- ADR-2607086100 (`cloud-itonami-isic-9499`, other membership organizations -- survey exhaustion recorded)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-9512` publishes an OSS business blueprint for
repair of communication equipment: diagnosing and repairing
telephones, radios and other communication devices for customers.
Like every prior vertical in this fleet, the blueprint text alone is
not an implementation -- this ADR records the governed-actor build
that promotes `cloud-itonami-isic-9512` from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry, the sixty-
second vertical built outside ADR-2607032000's original insurance/
real-estate batch.

By the time this vertical was selected, ADR-2607086100
(`memberorg`/9499) had recorded that the fleet-wide governor-name-
collision survey was EXHAUSTED: every remaining `:blueprint`-tier
candidate declares a governor keyword identical to an already-
implemented sibling. This build's primary decision is a genuinely
different kind of judgment call than any prior vertical selection in
this fleet has made.

## Problem

1. `cloud-itonami-isic-9512`'s blueprint described a real-world
   communication-equipment-repair operating model (device intake,
   diagnostic/quote proposal, repair completion, device return) but
   had no governed-actor implementation: no Store, no Governor, no
   rollout phasing, no tests.
2. The blueprint declares `:itonami.blueprint/governor :repair-shop-
   governor` -- IDENTICAL to `repairshop`/9521's own governor name.
   This needed a deliberate decision: treat it as blocked (consistent
   with every prior vertical-selection turn), or re-examine whether
   the underlying constraint was ever a hard one.
3. `repairshop`/9521's and `9512`'s blueprint texts are near-
   identical (same dual-actuation shape, same generic consumer-
   product-safety catalog structure) -- this build needed to find a
   genuinely differentiating hook to avoid a hollow, mechanical copy.

## Decision

1. **Proceeding with a shared governor name is a deliberate choice,
   not a naming error.** Direct grep of `kotoba-lang/industry`'s own
   code and tests confirmed NO governor-name-uniqueness constraint
   exists anywhere -- the "avoid collisions" heuristic this fleet had
   been applying was a self-imposed convention to keep ADR/README
   cross-references unambiguous, not a technical requirement. "Repair
   shop" is genuinely the SAME business archetype regardless of the
   item category repaired. This build documents the reuse explicitly
   (here, in the child repo's own ADR, and in its README) rather than
   silently duplicating.
2. **Architecture mirrors `repairshop`/9521 closely**: same entity
   (`ticket`), same op shape (`:ticket/intake`, `:jurisdiction/
   assess`, `:safety/screen`, `:repair/complete`, `:device/return`),
   and two HONEST, literal reuses of `repairshop`'s own checks
   (`parts-cost-matches-claim?`, `safety-test-not-passed`) for the
   SAME real-world concerns -- not claimed as new.
3. **`customer-data-consent-unconfirmed-violations` -- the 61st
   unconditional-evaluation grounding, a genuinely new concept.**
   Grep-verified absent (zero hits for "customer-data-consent"/
   "data-wipe"/"personal-data-access" across every prior sibling,
   INCLUDING against `repairshop.facts` itself). Grounded in this
   blueprint's own distinguishing Trust Controls line ("customer
   device data (personal content) stays outside Git" -- absent from
   `repairshop`/9521's own text) and real law/guidance: the US FTC's
   2021 "Nixing the Fix" report, UK GDPR/Data Protection Act 2018,
   Germany's DSGVO Art. 6/7, Japan's 個人情報保護法. Gates
   `:dataconsent/screen` and BOTH actuation ops.
4. **Dual-actuation shape.** `#{:actuation/complete-repair
   :actuation/return-device}`, matching `repairshop`/9521's own shape
   exactly -- both POSITIVE actuations.
5. **Dedicated double-actuation-guard booleans.** `:repair-
   completed?` and `:device-returned?` on the `ticket` record, never
   a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/commrepair/store_contract_test.clj`.
7. **Phase 0→3 rollout** -- Phase 3's `:auto` set is
   `{:ticket/intake}` only; both actuations are permanently excluded
   from every phase's `:auto` set.
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

- Seventy-seventh implemented actor in this fleet's registry (76
  implemented immediately before this build).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (customer-data-consent-unconfirmed), grep-verified absent
  from every prior sibling (including `repairshop`/9521) before the
  claim was finalized.
- Documents two honest, literal reuses of `repairshop`/9521's own
  checks for the SAME real-world concerns, not claimed as new.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/commrepair/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 76 → 77, `:blueprint` 9 → 8, `:spec`
  545 unchanged (sum 630; see Scope note below on the `:total`
  figure).
- Test status: 37 tests / 188 assertions, lint clean, demo verified
  end-to-end (one clean dual-actuation lifecycle plus five HARD-hold
  cases).
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing -- 7 tests / 124 assertions,
  all green.
- **Establishes a new fleet-wide precedent**: a shared governor name
  across siblings, when the underlying business archetype is
  genuinely the same, is acceptable and should be documented
  explicitly. This reopens `9522`, `9523`, `9524` and `9529` (the
  remaining repair-shop candidates) for future vertical-selection
  turns, PROVIDED each brings its own genuinely differentiated check
  grounded in its own blueprint's own distinguishing text -- not a
  mechanical copy with the item category swapped.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Scope note

This ADR's `:fleet-maturity-before`/`:fleet-maturity-after` `:total`
field (630) reflects the actual observed sum of `:implemented` +
`:blueprint` + `:spec` in `registry.edn` at build time, rather than
`docs/cloud-itonami.md`'s "Total entries: 643" figure -- the same
clarification ADR-2607085700's, ADR-2607085800's, ADR-2607085900's,
ADR-2607086000's and ADR-2607086100's own scope notes already
recorded: that 643 figure tracks a separate, pre-existing, wider
ISIC-class/group count, not per-vertical maturity accounting. Not
introduced or worsened by this build; not in scope to reconcile here.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Treating this as blocked, like every prior vertical-selection turn | The constraint was a self-imposed naming convention, not a technical requirement (confirmed via direct grep); a genuinely new, well-grounded check was available |
| Inventing a different governor name diverging from the blueprint's own published field | No prior build in this fleet has ever diverged from the blueprint's own stated governor name; doing so would be a bigger departure than honestly reusing an existing name for the same archetype |
| Reusing `repairshop`/9521's checks verbatim with no new contribution | This blueprint's own distinguishing Trust Controls language signals a real, load-bearing data-privacy concern specific to communication equipment; ignoring it would waste a legitimate differentiation opportunity |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-9512/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9512/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9512/docs/business-model.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9521/src/repairshop/governor.cljc` (structurally closest sibling; `parts-cost-matches-claim?`/`safety-test-not-passed` origin)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"9512"`)
