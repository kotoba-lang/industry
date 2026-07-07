# ADR-2607072800: `cloud-itonami-isic-9602` (hairdressing and other beauty treatment) deepened to `:implemented` -- sixteenth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`, the first fifteen verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/
  `9521`/`9321`/`8730`/`9102`/`9103` ADR-0001s (the governed-actor
  pattern this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9103`, this ADR records the SIXTEENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9602` (hairdressing and other beauty treatment, ISIC division
  96) -- the FIRST personal-care-services vertical in this fleet.

## Problem

A salon's treatment-performance workflow bundles several distinct
concerns under one governed workflow:

1. **Jurisdiction practitioner-licensing/chemical-safety correctness**
   -- an official spec-basis citation from a real regulator, never
   fabricated.
2. **Patch-test recency** -- the THIRD check in this fleet's
   temporal-sufficiency family to enforce a MAXIMUM ceiling
   (`eldercare.registry/care-plan-review-overdue?` established the
   first, `museum.registry/provenance-gap-exceeds-threshold?` the
   second), applied here to a fresh, domain-authentic ground truth:
   skin allergy-alert patch-test staleness before a chemical hair
   treatment.
3. **Allergy-flag resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a THIRTEENTH
   distinct grounding.
4. **Real, high-stakes actuation, once** -- performing a chemical or
   skin-piercing treatment on a real client is a single actuation
   event with direct bodily-safety stakes.

See `cloud-itonami-isic-9602`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9602` gains **SalonOps-LLM ⊣ Personal Service
   Safety Governor** -- `salon.*` namespaces, modeled closely on all
   twenty-three prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. `patch-test-window-exceeded?`/`max-patch-test-window-hours` is the
   THIRD instance of the MAXIMUM-ceiling temporal-sufficiency family,
   applied to a genuinely fresh ground truth: a bounded pre-treatment
   patch-test-validity window (48 hours), returning to a
   straightforward elapsed-time-since-event shape (closer to
   `eldercare`'s original) after `museum`'s generalization to a
   documented-history gap.
3. `allergy-flag-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a THIRTEENTH distinct grounding in this fleet.
4. Tested via the SCREENING op (`:allergy/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/`conservation`
   lesson PROACTIVELY for a fourth consecutive vertical.
5. Single actuation (`:actuation/perform-treatment`), matching
   `6511`'s/`6621`'s/`6629`'s/`6612`'s/`6492`'s/`7120`'s/`8620`'s/
   `7500`'s/`9603`'s/`9321`'s single-actuation shape -- a DELIBERATE
   return to single-actuation after three consecutive dual-actuation
   builds (`eldercare`, `museum`, `conservation`), matching this
   domain's actual blueprint-stated scope rather than defaulting to
   dual-actuation by habit.
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9602"`, fleet-wide maturity counts move from 29
   implemented / 68 blueprint / 546 spec to 30 implemented / 67
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/salon/*` -- 30 tests / 126 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean lifecycle
   (treatment performance) plus four HARD-hold cases (no spec-basis, a
   stale patch test beyond the pre-treatment window, an unresolved
   allergy flag screened directly and never reaching a human, and a
   double treatment performance) that never reach a human at all.

## Consequences

- (+) Personal-care services get the same governed, auditable-actor
  treatment as the twenty-three prior actors, and this fleet now has a
  SIXTEENTH concrete precedent for extending past ADR-2607032000's
  original scope, into a genuinely different economic sector
  (personal-care services, ISIC division 96) for the first time.
- (+) `patch-test-window-exceeded?` is a genuine structural
  contribution: a third instance of the MAXIMUM-ceiling family applied
  to a fresh domain-authentic ground truth.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/salon/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) This build deliberately restores single-actuation structural
  variety to the fleet, matching the domain's actual scope rather than
  defaulting to the dual-actuation shape the last three builds used.
- (+) The allergy-flag-unresolved test/demo correctly applied the
  established SCREENING-op-directly pattern for a fourth consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `salon.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `patch-test-window-exceeded?` models only a single representative
  pre-treatment patch-test-validity window, not a product-by-product/
  jurisdiction-by-jurisdiction survey, nor a full salon-management
  system -- see `cloud-itonami-isic-9602`'s own ADR-0001 and README
  coverage table for the full honest-scope accounting.
- Fleet-wide: 30 actors now `:implemented` out of 643 total registry
  entries; 67 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All fifteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`; mixing a different ISIC division (96, distinct from all of those fifteen's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9602` at `:blueprint` only | ❌ | The standing direction continues past `9103`; personal-care services are a natural, well-precedented next domain, further diversifying this fleet into a sector not yet touched |
| See `cloud-itonami-isic-9602`'s own ADR-0001 Alternatives table for build-level decisions | -- | (single- vs. dual-actuation modeling, unconditional-evaluation test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`, first fifteen post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9602/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
