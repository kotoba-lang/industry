# ADR-2607080700: `cloud-itonami-isic-6190` (community telecommunications access) deepened to `:implemented` -- twenty-eighth vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/
  `9412`/`6491`/`8720`/`8521`/`6619`/`3600`, the first twenty-seven
  verticals built outside ADR-2607032000's original insurance/real-
  estate batch); ADR-2607032000 (the original batch, fully closed);
  `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/
  `6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/
  `8890`/`8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/
  `3600` ADR-0001s (the governed-actor pattern this decision
  continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `3600`, this ADR records the TWENTY-EIGHTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-6190` (other telecommunications activities) -- the SECOND
  infrastructure/utility vertical built in this fleet, after `3600`'s
  water-safety operations.

## Problem

A telecom operator's number-provisioning/billing-suppression workflow
bundles several distinct concerns under one governed workflow:

1. **Jurisdiction numbering-plan correctness** -- an official spec-
   basis citation from a real national numbering-plan authority
   (総務省/the FCC-NANPA/Ofcom/the Bundesnetzagentur), never
   fabricated.
2. **E.164 structural validity** -- the FIRST instance of this
   fleet's format/syntactic-validity check family, verified via grep
   to be genuinely absent from every prior sibling's governor before
   this claim was made.
3. **Billing-dispute resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TWENTY-SIXTH
   distinct grounding overall, and a FIRST specifically for a
   billing-dispute concept.
4. **Real, high-stakes actuation, twice, one of which is NEGATIVE** --
   provisioning a real E.164 number and suppressing a real billing
   record are two independently-gated real-world acts on the SAME
   entity, and the second is this fleet's SECOND negative actuation
   (after `3600`'s alert-suppression): withholding a record, not
   issuing one.

See `cloud-itonami-isic-6190`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks and the negative-actuation
framing (this superproject ADR records the fleet-level context and
registry/maturity bookkeeping; the child repo's own ADR is the
authoritative architecture record).

## Decision

1. `cloud-itonami-isic-6190` gains **Telecom Advisor ⊣ Telecom Access
   Governor** -- `telecom.*` namespaces, modeled closely on all forty-
   one prior actors' Store/Registry/Governor/Phase/Advisor/Operation/
   Sim shape and the SAME generic langgraph-clj StateGraph.
2. `:actuation/suppress-billing-record` is the SECOND negative
   actuation this fleet has modeled (after `3600`'s `:actuation/
   suppress-alert`) -- withholding/silencing a real billing record
   rather than issuing one -- confirming the governed-actor discipline
   (HARD checks, high-stakes gate, phase-3 exclusion, dedicated
   double-actuation boolean) generalizes cleanly to this direction a
   SECOND time, on an unrelated domain (telecom billing vs. water-
   safety alerting), per this blueprint's own explicitly published
   Trust Control framing ("billing records cannot be suppressed
   without audit").
3. `e164-invalid-format?` is the FIRST instance of a format/syntactic-
   validity check family -- a pure ground-truth structural recompute
   on a line's own recorded E.164 number (leading `+`, no leading
   zero, 8-15 digits), distinct in shape from every prior range/
   threshold/set/ratio/unresolved-flag check family in this fleet's
   taxonomy, grep-verified absent from every prior sibling's
   `governor.cljc` before the claim was finalized (the same
   verify-before-claiming-precedent discipline `leasing`'s ADR-0001
   documents).
4. `billing-dispute-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a TWENTY-SIXTH distinct grounding overall, and a
   FIRST specifically for a billing-dispute concept.
5. Tested via the SCREENING op (`:billing/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral`/
   `secondary`/`card`/`water` lesson PROACTIVELY for a sixteenth
   consecutive vertical.
6. Dual actuation (`:actuation/provision-number`, `:actuation/
   suppress-billing-record`), matching `6512`'s/`6622`'s/`6520`'s/
   `6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/
   `8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s/`8510`'s/`9412`'s/
   `8720`'s/`8521`'s/`6619`'s/`3600`'s dual-actuation shape, each
   with its own history collection, sequence counter and dedicated
   double-actuation boolean guard (never a `:status` value, per
   `6492`'s ADR-0001 lesson).
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"6190"`, fleet-wide maturity counts move from 41
   implemented / 56 blueprint / 546 spec to 42 implemented / 55
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/telecom/*` -- 36 tests / 173 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → verify → billing screen → provision number →
   suppress billing record) plus five HARD-hold cases (no spec-basis,
   a malformed E.164 number, an unresolved billing dispute screened
   directly and never reaching a human, and a double number-
   provisioning/billing-suppression) that never reach a human at all.
9. The child repo's own `blueprint.edn`, which carried a stale pre-
   rename `:itonami.blueprint/id` and was missing `:robotics` in
   `:required-technologies` despite its own separate
   `:itonami.blueprint/robotics true` field, was corrected to match
   the registry.

## Consequences

- (+) Telecom-operator operation gets the same governed, auditable-
  actor treatment as the forty-one prior actors, and this fleet now
  has a TWENTY-EIGHTH concrete precedent for extending past
  ADR-2607032000's original scope, and its SECOND infrastructure/
  utility-sector coverage.
- (+) `:actuation/suppress-billing-record` is a genuine structural
  confirmation: the second negative actuation this fleet has modeled,
  proving the governed-actor pattern's negative-actuation
  generalization is not a one-off quirk of `3600`'s own domain.
- (+) `e164-invalid-format?` is a genuine structural contribution: the
  first format/syntactic-validity check family, grep-verified as a
  real addition to this fleet's check-family taxonomy.
- (+) `billing-dispute-unresolved-violations` is a genuine domain-
  modeling contribution: the first unconditional-evaluation grounding
  for a billing-dispute concept.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/telecom/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The billing-dispute test/demo correctly applied the established
  SCREENING-op-directly pattern for a sixteenth consecutive vertical
  -- further evidence that lessons recorded in this fleet's ADRs
  continue to transfer forward reliably.
- (+) Two small pre-existing inconsistencies in the blueprint scaffold
  (stale ID field, missing `:robotics` in required-technologies) were
  corrected as part of this promotion.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `telecom.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `e164-invalid-format?` models only a simplified structural check,
  not a full ITU-T E.164 numbering-plan validator, and this actor does
  not model real SIP/softswitch call routing, real HLR/number-
  portability integration, or lawful-intercept infrastructure -- see
  `cloud-itonami-isic-6190`'s own ADR-0001 and README coverage table
  for the full honest-scope accounting.
- Fleet-wide: 42 actors now `:implemented` out of 643 total registry
  entries; 55 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to `3600`'s ADR (both are infrastructure/utility verticals) | ❌ | The two domains (water-safety operations vs. telecom billing/numbering) are distinct enough, and this fleet's standing convention is a new ADR per vertical regardless of sector overlap |
| Keep `cloud-itonami-isic-6190` at `:blueprint` only | ❌ | The standing direction continues past `3600`; community telecommunications access is a natural next domain, deepening this fleet's infrastructure/utility-sector coverage to two verticals |
| Treat `e164-invalid-format?` as a variant of the existing two-sided range check family | ❌ | A range check compares a value against bounds; a format check validates an identifier's syntax -- different enough in shape and failure mode to warrant a distinct named family (see child repo's own ADR-0001 Alternatives table) |
| See `cloud-itonami-isic-6190`'s own ADR-0001 Alternatives table for build-level decisions | -- | (negative-actuation framing rationale, check-family framing, screening-op test design, blueprint-field-fix rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/
  `9412`/`6491`/`8720`/`8521`/`6619`/`3600`, first twenty-seven post-
  batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-6190/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
