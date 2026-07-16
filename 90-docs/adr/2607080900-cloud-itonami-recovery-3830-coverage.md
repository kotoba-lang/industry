# ADR-2607080900: `cloud-itonami-isic-3830` (local materials recovery) deepened to `:implemented` -- thirtieth vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/
  `6619`/`3600`/`6190`/`3030`, the first twenty-nine verticals built
  outside ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/
  `9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/
  `9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/`3600`/`6190`/
  `3030` ADR-0001s (the governed-actor pattern this decision
  continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `3030`, this ADR records the THIRTIETH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-3830` (local materials recovery) -- the SECOND circular-
  economy/infrastructure vertical built in this fleet.

## Problem

A materials-recovery operator's grading/impact-report workflow
bundles several distinct concerns under one governed workflow:

1. **Jurisdiction grading-standard correctness** -- an official spec-
   basis citation from a real regulator or recognized standard body
   (経済産業省/the EPA-ISRI/the Environment Agency-WRAP/the UBA under
   the KrWG), never fabricated.
2. **Contamination-ceiling sufficiency** -- the FOURTH instance of
   this fleet's MAXIMUM-ceiling check family (`facility`/`school`/
   `card` established the first three), deliberately single-sided
   since contamination has no meaningful lower-bound failure mode.
3. **Contamination-flag resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TWENTY-EIGHTH
   distinct grounding overall, and a FIRST specifically for a
   contamination-flag concept, kept independent from the numeric
   ceiling check per `water.governor`'s established "two concepts, one
   phenomenon" precedent.
4. **Real, high-stakes actuation, twice, BOTH positive** -- certifying
   a real material grade and publishing a real impact/ESG report are
   two independently-gated real-world acts on the SAME entity, both
   positive (issuing/finalizing a record) -- matching this fleet's
   majority actuation shape.

See `cloud-itonami-isic-3830`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks and the check-family framing
(this superproject ADR records the fleet-level context and registry/
maturity bookkeeping; the child repo's own ADR is the authoritative
architecture record).

## Decision

1. `cloud-itonami-isic-3830` gains **Recovery Advisor ⊣ Traceability
   Governor** -- `recovery.*` namespaces, modeled closely on all
   forty-three prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. This is this fleet's SECOND circular-economy/infrastructure
   vertical -- distinct from `3600`'s/`6190`'s utility-service domains
   and `3030`'s manufacturing domain; the distinguishing concern here
   is traceability of a real-world material's chain of custody and
   grade.
3. `contamination-percentage-exceeds-maximum?` is the FOURTH instance
   of the MAXIMUM-ceiling check family, reusing the identical single-
   value-vs-ceiling-comparison shape for a batch's own measured
   contamination percentage against its own recorded maximum-allowed-
   for-grade threshold, gating only `:actuation/certify-material-
   grade`.
4. `contamination-flag-unresolved-violations` reuses the
   unconditional-evaluation discipline (`casualty.governor/sanctions-
   violations`'s original fix) for a TWENTY-EIGHTH distinct grounding
   overall, and a FIRST specifically for a contamination-flag concept
   -- kept as an INDEPENDENT boolean concept from the numeric ceiling
   check, per `water.governor`'s established precedent of modeling two
   distinct concepts on one underlying phenomenon.
5. Tested via the SCREENING op (`:contamination/screen`) directly from
   the start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral`/
   `secondary`/`card`/`water`/`telecom`/`aerospace` lesson
   PROACTIVELY for an eighteenth consecutive vertical.
6. Dual actuation (`:actuation/certify-material-grade`, `:actuation/
   publish-impact-report`), matching `6512`'s/`6622`'s/`6520`'s/
   `6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/
   `8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s/`8510`'s/`9412`'s/
   `8720`'s/`8521`'s/`6619`'s/`3600`'s/`6190`'s/`3030`'s dual-
   actuation shape, each with its own history collection, sequence
   counter and dedicated double-actuation boolean guard (never a
   `:status` value, per `6492`'s ADR-0001 lesson). BOTH actuations
   here are POSITIVE, matching this fleet's majority shape (`3600`/
   `6190` remain the only negative-actuation exceptions).
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"3830"`, fleet-wide maturity counts move from 43
   implemented / 54 blueprint / 546 spec to 44 implemented / 53
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/recovery/*` -- 36 tests / 174 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → verify → contamination screen → certify grade
   → publish impact report) plus five HARD-hold cases (no spec-basis,
   an over-contaminated batch, an unresolved contamination flag
   screened directly and never reaching a human, and a double
   certification/publication) that never reach a human at all.
9. The child repo's own `blueprint.edn`, which carried a stale pre-
   rename `:itonami.blueprint/id` and was missing `:required-
   technologies`/`:optional-technologies` entirely, was corrected to
   match the registry.

## Consequences

- (+) Materials-recovery operation gets the same governed, auditable-
  actor treatment as the forty-three prior actors, and this fleet now
  has a THIRTIETH concrete precedent for extending past
  ADR-2607032000's original scope, and its SECOND circular-economy/
  infrastructure-sector coverage.
- (+) `contamination-percentage-exceeds-maximum?` is a genuine
  structural contribution: the fourth instance of the MAXIMUM-ceiling
  check family, and a deliberate confirmation that single-sided
  ceiling checks and two-sided range checks are genuinely distinct
  families to be chosen by the real-world failure-mode shape, not
  defaulted to one or the other.
- (+) `contamination-flag-unresolved-violations` is a genuine domain-
  modeling contribution: the first unconditional-evaluation grounding
  for a contamination-flag concept, and confirms `water.governor`'s
  "two independent concepts on one phenomenon" shape generalizes to a
  new domain.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/recovery/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The contamination-flag test/demo correctly applied the
  established SCREENING-op-directly pattern for an eighteenth
  consecutive vertical -- further evidence that lessons recorded in
  this fleet's ADRs continue to transfer forward reliably.
- (+) Two small pre-existing inconsistencies in the blueprint scaffold
  (stale ID field, missing required/optional-technologies fields) were
  corrected as part of this promotion.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `recovery.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) This actor does not model a real scale/scanner/MRF control
  system, a real buyer-matching/logistics engine, or route
  optimization -- see `cloud-itonami-isic-3830`'s own ADR-0001 and
  README coverage table for the full honest-scope accounting.
- Fleet-wide: 44 actors now `:implemented` out of 643 total registry
  entries; 53 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to `3600`'s ADR (both are infrastructure-adjacent) | ❌ | Materials recovery's traceability concern is distinct from water-safety operations; this fleet's standing convention is a new ADR per vertical |
| Keep `cloud-itonami-isic-3830` at `:blueprint` only | ❌ | The standing direction continues past `3030`; local materials recovery is a natural next domain, deepening this fleet's circular-economy/infrastructure coverage to two verticals |
| Model the contamination check as a two-sided range (matching `testlab`/`conservation`/`water`/`aerospace`) | ❌ | A contamination percentage has no meaningful lower-bound failure mode -- a single-sided MAXIMUM-ceiling check is the honest shape (see child repo's own ADR-0001 Alternatives table) |
| See `cloud-itonami-isic-3830`'s own ADR-0001 Alternatives table for build-level decisions | -- | (check-family-choice rationale, screen/ceiling independence rationale, blueprint-field-fix rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/
  `6619`/`3600`/`6190`/`3030`, first twenty-nine post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-3830/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
