# ADR-2607080200: `cloud-itonami-isic-6491` (financial leasing) deepened to `:implemented` -- twenty-third vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`, the first
  twenty-two verticals built outside ADR-2607032000's original
  insurance/real-estate batch); ADR-2607032000 (the original batch,
  fully closed); `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/
  `6629`/`6520`/`6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412` ADR-0001s
  (the governed-actor pattern this decision continues); langgraph-clj
  ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9412`, this ADR records the TWENTY-THIRD
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-6491` (financial leasing) -- a SECOND financial-services
  vertical alongside `6492`'s credit granting, but for asset-backed
  lease financing rather than general lending.

## Problem

A lessor's lease-funding-disbursement workflow bundles several
distinct concerns under one governed workflow:

1. **Jurisdiction leasing/consumer-credit correctness** -- an official
   spec-basis citation from a real regulator (金融庁/the CFPB's
   Regulation M/the FCA's CONC/BaFin under the KWG), never
   fabricated.
2. **Collateral coverage sufficiency** -- the FIRST RATIO-based
   instance in this fleet's check-family taxonomy (every prior
   sufficiency family compares two fields directly; this compares
   their QUOTIENT against a required minimum).
3. **Adverse-credit-flag resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TWENTY-FIRST
   distinct grounding overall, and a FIRST specifically for an
   adverse-credit/financial-history-flag concept.
4. **Real, high-stakes actuation, once** -- disbursing real lease
   funding is a single actuation event with direct financial stakes.

See `cloud-itonami-isic-6491`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-6491` gains **Leasing-LLM ⊣ Leasing
   Governor** -- `leasing.*` namespaces, modeled closely on all thirty
   prior actors' Store/Registry/Governor/Phase/Advisor/Operation/Sim
   shape and the SAME generic langgraph-clj StateGraph.
2. `collateral-coverage-ratio-insufficient?` is the FIRST ratio-based
   instance in this fleet's check-family taxonomy, comparing a
   lease's own collateral value divided by its own financed amount
   against the lessor's own recorded minimum coverage ratio -- a
   genuinely different mathematical relationship from every prior
   direct-comparison family (MINIMUM-threshold, MAXIMUM-ceiling,
   two-sided range).
3. `adverse-credit-flag-unresolved-violations` reuses the
   unconditional-evaluation discipline (`casualty.governor/
   sanctions-violations`'s original fix) for a TWENTY-FIRST distinct
   grounding overall, and a FIRST specifically for an adverse-credit/
   financial-history-flag concept -- verified against `credit.
   governor`'s (`6492`) actual source before writing this check, since
   its own `affordability-exceeded-violations` check is a ground-truth
   recompute, not an unconditional-evaluation screening check, so no
   false precedent is claimed.
4. Tested via the SCREENING op (`:creditworthiness/screen`) directly
   from the start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association` lesson PROACTIVELY for an
   eleventh consecutive vertical.
5. Single actuation (`:actuation/fund-lease-disbursement`), matching
   `6511`'s/`6621`'s/`6629`'s/`6612`'s/`6492`'s/`7120`'s/`8620`'s/
   `7500`'s/`9603`'s/`9321`'s/`9602`'s/`9000`'s/`9311`'s single-
   actuation shape, with its own history collection, sequence counter
   and dedicated double-actuation boolean guard (never a `:status`
   value, per `6492`'s ADR-0001 lesson).
6. Related capability contract [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking)
   is cited but not directly required, matching `credit.governor`'s
   (`6492`) own posture toward the same `:banking` capability-tech
   tag -- this actor remains self-contained like every prior sibling.
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"6491"`, fleet-wide maturity counts move from 36
   implemented / 61 blueprint / 546 spec to 37 implemented / 60
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/leasing/*` -- 30 tests / 128 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean single-actuation
   lifecycle (intake → assess → creditworthiness screen → fund
   disbursement) plus four HARD-hold cases (no spec-basis, insufficient
   collateral coverage, an unresolved adverse credit flag screened
   directly and never reaching a human, and a double disbursement)
   that never reach a human at all.

## Consequences

- (+) Financial leasing gets the same governed, auditable-actor
  treatment as the thirty prior actors, and this fleet now has a
  TWENTY-THIRD concrete precedent for extending past ADR-2607032000's
  original scope, deepening financial-services coverage alongside
  `6492`'s credit granting with a genuinely different financing model.
- (+) `collateral-coverage-ratio-insufficient?` is a genuine
  structural contribution: the first ratio-based sufficiency check in
  this fleet's taxonomy, distinct from every prior direct-comparison
  family.
- (+) `adverse-credit-flag-unresolved-violations` is a genuine domain-
  modeling contribution: the first unconditional-evaluation grounding
  for an adverse-credit/financial-history-flag concept, correctly
  distinguished from `credit.governor`'s unrelated ground-truth
  affordability recompute after directly reading that sibling's
  source.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/leasing/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The adverse-credit-flag test/demo correctly applied the
  established SCREENING-op-directly pattern for an eleventh
  consecutive vertical -- further evidence that lessons recorded in
  this fleet's ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `leasing.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `collateral-coverage-ratio-insufficient?` models only a single
  representative minimum coverage ratio, not a full asset-valuation/
  residual-value-forecasting engine -- see `cloud-itonami-isic-6491`'s
  own ADR-0001 and README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 37 actors now `:implemented` out of 643 total registry
  entries; 60 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All twenty-two of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`; mixing a different financial-services sub-domain into `6492`'s ADR would blur scope boundaries even where the broad sector overlaps |
| Keep `cloud-itonami-isic-6491` at `:blueprint` only | ❌ | The standing direction continues past `9412`; financial leasing is a natural, well-precedented next domain, deepening this fleet's financial-services coverage alongside `6492`'s credit granting |
| See `cloud-itonami-isic-6491`'s own ADR-0001 Alternatives table for build-level decisions | -- | (ratio-based check-family framing, adverse-credit-flag precedent verification, capability-lib reference posture, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`, first
  twenty-two post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-6491/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
