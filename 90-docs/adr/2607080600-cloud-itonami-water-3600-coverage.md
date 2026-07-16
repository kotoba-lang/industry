# ADR-2607080600: `cloud-itonami-isic-3600` (water collection, treatment and supply) deepened to `:implemented` -- twenty-seventh vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/
  `8720`/`8521`/`6619`, the first twenty-six verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/
  `9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/
  `9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619` ADR-0001s (the
  governed-actor pattern this decision continues); langgraph-clj
  ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `6619`, this ADR records the TWENTY-SEVENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-3600` (water collection, treatment and supply) -- the FIRST
  infrastructure/utility vertical built in this fleet (every prior
  actor has been a human-services, leisure or financial-services
  domain).

## Problem

A water utility's report-publication/alert-suppression workflow
bundles several distinct concerns under one governed workflow:

1. **Jurisdiction drinking-water-safety correctness** -- an official
   spec-basis citation from a real regulator (国土交通省/環境省 under
   the current post-2024 Waterworks Act reorganization/the EPA's
   SDWA/the DWI/the UBA under the TrinkwV), never fabricated.
2. **Contaminant-level sufficiency** -- the THIRD instance of this
   fleet's two-sided range check family (`testlab`/`conservation`
   established the first two).
3. **Threshold-breach resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TWENTY-FIFTH
   distinct grounding overall, and a FIRST specifically for a
   threshold-breach concept.
4. **Real, high-stakes actuation, twice, one of which is NEGATIVE** --
   publishing a real public report and suppressing a real triggered
   safety alert are two independently-gated real-world acts on the
   SAME entity, and critically, the second is this fleet's first
   NEGATIVE actuation: withholding a notification, not issuing a
   record.

See `cloud-itonami-isic-3600`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks and the negative-actuation
framing (this superproject ADR records the fleet-level context and
registry/maturity bookkeeping; the child repo's own ADR is the
authoritative architecture record).

## Decision

1. `cloud-itonami-isic-3600` gains **Water Advisor ⊣ Water Safety
   Governor** -- `water.*` namespaces, modeled closely on all thirty-
   four prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. `:actuation/suppress-alert` is the FIRST negative actuation this
   fleet has modeled -- withholding/silencing an already-triggered
   safety notification rather than issuing a real-world record --
   proving the governed-actor discipline (HARD checks, high-stakes
   gate, phase-3 exclusion, dedicated double-actuation boolean)
   generalizes cleanly to this direction with no special-casing
   required, per this blueprint's own explicitly published Core
   Contract framing.
3. `contaminant-level-out-of-range?` is the THIRD instance of the two-
   sided range check family, reusing the identical lo/hi-bounds-
   comparison shape for a site's own measured contaminant level
   against its own recorded safe-range bounds.
4. `threshold-breach-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a TWENTY-FIFTH distinct grounding overall, and a
   FIRST specifically for a threshold-breach concept.
5. Tested via the SCREENING op (`:threshold/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral`/
   `secondary`/`card` lesson PROACTIVELY for a fifteenth consecutive
   vertical.
6. Dual actuation (`:actuation/publish-report`, `:actuation/suppress-
   alert`), matching `6512`'s/`6622`'s/`6520`'s/`6530`'s/`6820`'s/
   `6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/`8730`'s/`9102`'s/
   `9103`'s/`8890`'s/`8610`'s/`8510`'s/`9412`'s/`8720`'s/`8521`'s/
   `6619`'s dual-actuation shape, each with its own history
   collection, sequence counter and dedicated double-actuation
   boolean guard (never a `:status` value, per `6492`'s ADR-0001
   lesson).
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"3600"`, fleet-wide maturity counts move from 40
   implemented / 57 blueprint / 546 spec to 41 implemented / 56
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/water/*` -- 36 tests / 176 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → assess → threshold screen → report publish →
   alert suppress) plus four HARD-hold cases (no spec-basis, an out-
   of-range contaminant reading, an unresolved threshold breach
   screened directly and never reaching a human, and a double report-
   publication/alert-suppression) that never reach a human at all.
9. The child repo's own `blueprint.edn`, which was missing
   `:required-technologies`/`:optional-technologies` entirely and
   carried a stale pre-rename `:itonami.blueprint/id`, was corrected
   to match the registry and the already-renamed GitHub repo.

## Consequences

- (+) Water-utility operation gets the same governed, auditable-actor
  treatment as the thirty-four prior actors, and this fleet now has a
  TWENTY-SEVENTH concrete precedent for extending past ADR-2607032000's
  original scope, and its FIRST infrastructure/utility-sector
  coverage.
- (+) `:actuation/suppress-alert` is a genuine structural contribution:
  the first negative actuation this fleet has modeled, proving the
  governed-actor pattern generalizes beyond record-issuance acts.
- (+) `contaminant-level-out-of-range?` is a genuine structural
  contribution: the third instance of the two-sided range check
  family.
- (+) `threshold-breach-unresolved-violations` is a genuine domain-
  modeling contribution: the first unconditional-evaluation grounding
  for a threshold-breach concept.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/water/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The threshold-breach test/demo correctly applied the established
  SCREENING-op-directly pattern for a fifteenth consecutive vertical
  -- further evidence that lessons recorded in this fleet's ADRs
  continue to transfer forward reliably.
- (+) Two small pre-existing inconsistencies in the blueprint scaffold
  (missing technology fields, a stale ID field) were corrected as part
  of this promotion.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `water.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `contaminant-level-out-of-range?` models only a single
  contaminant-level comparison, not real SCADA/sensor-network
  telemetry ingestion or a full hydraulic-modeling engine -- see
  `cloud-itonami-isic-3600`'s own ADR-0001 and README coverage table
  for the full honest-scope accounting.
- Fleet-wide: 41 actors now `:implemented` out of 643 total registry
  entries; 56 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All twenty-six of those ADRs' titles and scopes are explicitly named human-services/leisure/financial-services verticals; this is this fleet's first infrastructure/utility vertical, with no prior sibling sharing even the same broad sector |
| Keep `cloud-itonami-isic-3600` at `:blueprint` only | ❌ | The standing direction continues past `6619`; water-utility operation is a natural next domain, opening this fleet's first infrastructure/utility-sector coverage |
| See `cloud-itonami-isic-3600`'s own ADR-0001 Alternatives table for build-level decisions | -- | (negative-actuation framing rationale, check-family framing, screening-op test design, blueprint-field-fix rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/
  `8720`/`8521`/`6619`, first twenty-six post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-3600/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
