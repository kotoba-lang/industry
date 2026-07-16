# ADR-2607072730: `cloud-itonami-isic-9102` (museums activities and operation of historical sites and buildings) deepened to `:implemented` -- fourteenth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`, the first thirteen
  verticals built outside ADR-2607032000's original insurance/real-
  estate batch); ADR-2607032000 (the original batch, fully closed);
  `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/
  `6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730` ADR-0001s (the governed-actor
  pattern this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8730`, this ADR records the FOURTEENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9102` (museums activities and operation of historical sites and
  buildings, ISIC division 91) -- the FIRST cultural-heritage vertical
  in this fleet.

## Problem

A museum's item-loan/item-deaccession workflow bundles several
distinct concerns under one governed workflow:

1. **Jurisdiction cultural-property correctness** -- an official
   spec-basis citation from a real regulator, never fabricated.
2. **Provenance due-diligence** -- the SECOND check in this fleet's
   temporal-sufficiency family to enforce a MAXIMUM ceiling
   (`eldercare.registry/care-plan-review-overdue?` established the
   first), generalizing it from "elapsed time since a recurring
   event" to "size of a gap in a historical ownership-custody record
   chain."
3. **Incident-flag resolution verification** -- reuses the
   unconditional-evaluation screening discipline for an ELEVENTH
   distinct grounding, gating BOTH actuation ops of a dual-actuation
   actor off the same unresolved-flag concept.
4. **Real, high-stakes actuation, twice** -- loaning out a real
   collection item and permanently deaccessioning a real collection
   item are two independently-gated real-world acts on the SAME
   entity.

See `cloud-itonami-isic-9102`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9102` gains **CuratorOps-LLM ⊣ Collections
   Governor** -- `museum.*` namespaces, modeled closely on all twenty-
   one prior actors' Store/Registry/Governor/Phase/Advisor/Operation/
   Sim shape and the SAME generic langgraph-clj StateGraph.
2. `provenance-gap-exceeds-threshold?`/`max-provenance-gap-years`
   extends the MAXIMUM-ceiling temporal-sufficiency family
   (`eldercare.registry/care-plan-review-overdue?`) to a genuinely new
   shape: rather than measuring elapsed time since a recurring event,
   it measures the size of the largest undocumented gap within an
   item's own ownership-custody chain (`:provenance-gap-years`, ceiling
   10 years, a representative museum-ethics due-diligence threshold).
3. `incident-flag-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for an ELEVENTH distinct grounding in this fleet, and
   the second (after `eldercare`) to gate BOTH actuation ops of a
   dual-actuation actor off the same unresolved-flag concept.
4. Tested via the SCREENING op (`:incident/screen`) directly from the
   start, applying the `parksafety`/`eldercare` lesson PROACTIVELY for
   a second consecutive vertical (a failing screen never persists its
   payload to the store, so the actuation ops alone could never
   discover the bad ground-truth flag through this check family
   without the screening op having actually been run first).
5. Dual actuation (`:actuation/loan-item`, `:actuation/deaccession-
   item`), matching `6512`'s/`6622`'s/`6520`'s/`6530`'s/`6820`'s/
   `6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/`8730`'s dual-actuation
   shape, each with its own history collection, sequence counter and
   dedicated double-actuation boolean guard (never a `:status` value,
   per `6492`'s ADR-0001 lesson).
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9102"`, fleet-wide maturity counts move from 27
   implemented / 70 blueprint / 546 spec to 28 implemented / 69
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/museum/*` -- 37 tests / 175 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (item
   loan, item deaccession) plus five HARD-hold cases (no spec-basis, a
   provenance gap beyond the due-diligence ceiling, an unresolved
   incident flag screened directly and never reaching a human, and a
   double loan/deaccession of each actuation op) that never reach a
   human at all.

## Consequences

- (+) Cultural heritage gets the same governed, auditable-actor
  treatment as the twenty-one prior actors, and this fleet now has a
  FOURTEENTH concrete precedent for extending past ADR-2607032000's
  original scope, into a genuinely different economic sector (culture
  & heritage, ISIC division 91) for the first time.
- (+) `provenance-gap-exceeds-threshold?` is a genuine structural
  contribution: it generalizes the MAXIMUM-ceiling temporal-
  sufficiency family from "elapsed time since a recurring event" to
  "size of a gap in a historical record chain."
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/museum/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The incident-flag-unresolved test/demo correctly applied the
  established SCREENING-op-directly pattern for a second consecutive
  vertical after `eldercare` -- concrete evidence that lessons recorded
  in this fleet's ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `museum.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `max-provenance-gap-years` models a single representative due-
  diligence threshold (10 years), not a jurisdiction-by-jurisdiction
  survey, nor a full collections-management system -- see `cloud-
  itonami-isic-9102`'s own ADR-0001 and README coverage table for the
  full honest-scope accounting.
- Fleet-wide: 28 actors now `:implemented` out of 643 total registry
  entries; 69 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All thirteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`; mixing a different ISIC division (91, distinct from all of those thirteen's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9102` at `:blueprint` only | ❌ | The standing direction continues past `8730`; cultural heritage is a natural, well-precedented next domain, further diversifying this fleet into a sector not yet touched |
| See `cloud-itonami-isic-9102`'s own ADR-0001 Alternatives table for build-level decisions | -- | (whether provenance-gap-exceeds-threshold? is a new family vs. an extension, unconditional-evaluation test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`, first thirteen post-batch
  verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9102/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
