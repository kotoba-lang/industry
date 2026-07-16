# ADR-2607080500: `cloud-itonami-isic-6619` (card transaction processing and settlement) deepened to `:implemented` -- twenty-sixth vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`, the
  first twenty-five verticals built outside ADR-2607032000's original
  insurance/real-estate batch); ADR-2607032000 (the original batch,
  fully closed); `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/
  `6629`/`6520`/`6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/
  `8720`/`8521` ADR-0001s (the governed-actor pattern this decision
  continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8521`, this ADR records the TWENTY-SIXTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-6619` (card transaction processing and settlement) -- a THIRD
  financial-services vertical alongside `6492`'s credit granting and
  `6491`'s financial leasing, but for card-payment processing/
  settlement rather than lending.

## Problem

A card processor's settlement-finalization/chargeback-release
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction card-settlement/chargeback-dispute correctness** --
   an official spec-basis citation from a real regulator (経済産業省/
   the CFPB's Regulation Z/the FCA's PSD2 transposition/BaFin under
   the ZAG), never fabricated.
2. **Settlement-amount sufficiency** -- the THIRD non-temporal
   instance of this fleet's MAXIMUM-ceiling check family (`facility`/
   `school` established the first two), directly implementing this
   blueprint's own published Trust Control: "partial approvals never
   over-charge the granted amount."
3. **Fraud-flag resolution verification** -- reuses the unconditional-
   evaluation screening discipline for a TWENTY-FOURTH distinct
   grounding overall, and a FIRST specifically for a fraud-flag
   concept.
4. **Real, high-stakes actuation, twice** -- finalizing a real
   settlement and releasing a real chargeback hold are two
   independently-gated real-world financial acts on the SAME entity,
   and critically, a raw PAN must never be handled anywhere in this
   flow.

See `cloud-itonami-isic-6619`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-6619` gains **Card Advisor ⊣ Card Settlement
   Governor** -- `card.*` namespaces, modeled closely on all thirty-
   three prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
   No raw PAN field exists anywhere in this actor's schema, per the
   blueprint's own explicit Trust Control.
2. `settlement-amount-exceeds-authorized?` is the THIRD non-temporal
   instance of the MAXIMUM-ceiling check family, directly implementing
   this blueprint's own published Trust Control ("partial approvals
   never over-charge the granted amount") -- an unusually direct
   textual grounding compared to prior invented-domain-concern checks.
3. `fraud-flag-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a TWENTY-FOURTH distinct grounding overall, and a
   FIRST specifically for a fraud-flag concept.
4. Tested via the SCREENING op (`:fraud/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral`/
   `secondary` lesson PROACTIVELY for a fourteenth consecutive
   vertical.
5. Dual actuation (`:actuation/settle-transaction`, `:actuation/
   release-chargeback`), matching `6512`'s/`6622`'s/`6520`'s/`6530`'s/
   `6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/`8730`'s/
   `9102`'s/`9103`'s/`8890`'s/`8610`'s/`8510`'s/`9412`'s/`8720`'s/
   `8521`'s dual-actuation shape, each with its own history
   collection, sequence counter and dedicated double-actuation
   boolean guard (never a `:status` value, per `6492`'s ADR-0001
   lesson).
6. Related capability contracts (`kotoba-lang/card`, `kotoba-lang/
   banking`, `kotoba-lang/swift`) are cited but not directly required
   -- matching `credit.governor`'s (`6492`) and `leasing.governor`'s
   (`6491`) own posture, this is the FIRST actor in this fleet to cite
   THREE related capability contracts at once.
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"6619"`, fleet-wide maturity counts move from 39
   implemented / 58 blueprint / 546 spec to 40 implemented / 57
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/card/*` -- 36 tests / 173 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → assess → fraud screen → settlement finalize →
   chargeback release) plus four HARD-hold cases (no spec-basis, a
   settlement over-charging its own authorization, an unresolved fraud
   flag screened directly and never reaching a human, and a double
   settlement/chargeback-release) that never reach a human at all.
9. The child repo's own `blueprint.edn` internal `:itonami.blueprint/
   id` field, still carrying the pre-rename `"cloud-itonami-6619"`
   value, was corrected to `"cloud-itonami-isic-6619"` to match the
   already-renamed GitHub repo and the registry's own `:business-id`.

## Consequences

- (+) Card transaction processing/settlement gets the same governed,
  auditable-actor treatment as the thirty-three prior actors, and this
  fleet now has a TWENTY-SIXTH concrete precedent for extending past
  ADR-2607032000's original scope, deepening financial-services
  coverage alongside `6492`'s credit granting and `6491`'s financial
  leasing with a genuinely different financial-services model.
- (+) `settlement-amount-exceeds-authorized?` is a genuine structural
  contribution: the third non-temporal instance of the MAXIMUM-ceiling
  family, with an unusually direct textual grounding in this
  blueprint's own published Trust Controls.
- (+) `fraud-flag-unresolved-violations` is a genuine domain-modeling
  contribution: the first unconditional-evaluation grounding for a
  fraud-flag concept.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/card/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses, with no raw-PAN field anywhere in the
  schema by design.
- (+) The fraud-flag test/demo correctly applied the established
  SCREENING-op-directly pattern for a fourteenth consecutive vertical
  -- further evidence that lessons recorded in this fleet's ADRs
  continue to transfer forward reliably.
- (+) A small pre-existing inconsistency (the blueprint's own stale
  internal ID field) was corrected as part of this promotion.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `card.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `settlement-amount-exceeds-authorized?` models only a single
  amount-comparison concern, not PAN/Luhn validation, ISO 8583 message
  handling, or a full card-network integration -- see `cloud-itonami-
  isic-6619`'s own ADR-0001 and README coverage table for the full
  honest-scope accounting.
- Fleet-wide: 40 actors now `:implemented` out of 643 total registry
  entries; 57 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to `cloud-itonami-isic-6492`'s or `6491`'s ADR | ❌ | Both ADRs' titles and scopes are explicitly credit granting/financial leasing; card-payment processing/settlement is a distinct financial-services sub-domain with its own actuation shape, even though the broad financial-services sector overlaps |
| Keep `cloud-itonami-isic-6619` at `:blueprint` only | ❌ | The standing direction continues past `8521`; card transaction processing is a natural, well-precedented next domain, deepening this fleet's financial-services coverage alongside `6492`'s credit granting and `6491`'s financial leasing |
| See `cloud-itonami-isic-6619`'s own ADR-0001 Alternatives table for build-level decisions | -- | (PAN-scope boundary, capability-lib citation posture, screening-op scoping rationale, blueprint-ID-fix rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`,
  first twenty-five post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-6619/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
