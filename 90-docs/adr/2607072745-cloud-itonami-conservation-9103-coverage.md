# ADR-2607072745: `cloud-itonami-isic-9103` (botanical and zoological gardens and nature reserves activities) deepened to `:implemented` -- fifteenth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`, the
  first fourteen verticals built outside ADR-2607032000's original
  insurance/real-estate batch); ADR-2607032000 (the original batch,
  fully closed); `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/
  `6629`/`6520`/`6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`
  ADR-0001s (the governed-actor pattern this decision continues);
  langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9102`, this ADR records the FIFTEENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9103` (botanical and zoological gardens and nature reserves
  activities, ISIC division 91) -- a SECOND cultural/recreational
  vertical alongside `9102`'s museum, but for LIVING collections
  rather than static objects.

## Problem

A zoo/botanical garden's specimen-transfer/specimen-release workflow
bundles several distinct concerns under one governed workflow:

1. **Jurisdiction wildlife/plant-conservation correctness** -- an
   official spec-basis citation from a real regulator, never
   fabricated.
2. **Body-condition sufficiency** -- the SECOND check in this fleet
   to combine BOTH directions in ONE check (`testlab.registry/within-
   tolerance?` established the first), and the FIRST to apply per-
   ENTITY species-specific acceptance bounds rather than per-test-
   protocol bounds.
3. **Welfare-flag resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TWELFTH
   distinct grounding, gating BOTH actuation ops of a dual-actuation
   actor off the same unresolved-flag concept.
4. **Real, high-stakes actuation, twice** -- transferring a real
   living specimen and releasing a real living specimen are two
   independently-gated real-world acts on the SAME entity.

See `cloud-itonami-isic-9103`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9103` gains **ConservationOps-LLM ⊣
   Conservation Governor** -- `conservation.*` namespaces, modeled
   closely on all twenty-two prior actors' Store/Registry/Governor/
   Phase/Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. `body-condition-out-of-range?`/`bcs-min-healthy`/`bcs-max-healthy`
   extends the two-sided range-check family (`testlab.registry/
   within-tolerance?`) to a genuinely new shape: rather than comparing
   a measured value against a per-protocol acceptance range, it
   compares a specimen's own body-condition score against its OWN
   species-specific healthy range, since different species genuinely
   have different healthy body-condition bounds.
3. `welfare-flag-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a TWELFTH distinct grounding in this fleet, and
   the third (after `eldercare` and `museum`) to gate BOTH actuation
   ops of a dual-actuation actor off the same unresolved-flag concept.
4. Tested via the SCREENING op (`:welfare/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum` lesson
   PROACTIVELY for a third consecutive vertical (a failing screen
   never persists its payload to the store, so the actuation ops alone
   could never discover the bad ground-truth flag through this check
   family without the screening op having actually been run first).
5. Dual actuation (`:actuation/transfer-specimen`, `:actuation/
   release-specimen`), matching `6512`'s/`6622`'s/`6520`'s/`6530`'s/
   `6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/`8730`'s/
   `9102`'s dual-actuation shape, each with its own history collection,
   sequence counter and dedicated double-actuation boolean guard
   (never a `:status` value, per `6492`'s ADR-0001 lesson).
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9103"`, fleet-wide maturity counts move from 28
   implemented / 69 blueprint / 546 spec to 29 implemented / 68
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/conservation/*` -- 38 tests / 176 assertions, lint-clean,
   demo (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles
   (specimen transfer, specimen release) plus five HARD-hold cases (no
   spec-basis, a body-condition score outside its own healthy range, an
   unresolved welfare flag screened directly and never reaching a
   human, and a double transfer/release of each actuation op) that
   never reach a human at all.

## Consequences

- (+) Zoo/botanical-garden conservation gets the same governed,
  auditable-actor treatment as the twenty-two prior actors, and this
  fleet now has a FIFTEENTH concrete precedent for extending past
  ADR-2607032000's original scope, deepening cultural/recreational
  coverage (ISIC division 91) with a genuinely different entity type
  (living specimens vs. static artifacts).
- (+) `body-condition-out-of-range?` is a genuine structural
  contribution: it generalizes the two-sided range-check family from
  per-protocol acceptance bounds to per-entity species-specific
  acceptance bounds.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  conservation/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern every sibling actor uses.
- (+) The welfare-flag-unresolved test/demo correctly applied the
  established SCREENING-op-directly pattern for a third consecutive
  vertical after `eldercare` and `museum` -- further evidence that
  lessons recorded in this fleet's ADRs continue to transfer forward
  reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `conservation.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `body-condition-out-of-range?` models only a single
  representative body-condition-scoring range per specimen, not a full
  veterinary/husbandry program -- see `cloud-itonami-isic-9103`'s own
  ADR-0001 and README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 29 actors now `:implemented` out of 643 total registry
  entries; 68 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All fourteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`; mixing a different sub-domain into any would blur scope boundaries even where the ISIC division (91) overlaps with `9102` |
| Keep `cloud-itonami-isic-9103` at `:blueprint` only | ❌ | The standing direction continues past `9102`; living-collection conservation is a natural, well-precedented next domain, deepening this fleet's cultural/recreational coverage with a genuinely different entity type |
| See `cloud-itonami-isic-9103`'s own ADR-0001 Alternatives table for build-level decisions | -- | (whether body-condition-out-of-range? is an extension of the range-check or threshold-check family, unconditional-evaluation test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`, first
  fourteen post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9103/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
