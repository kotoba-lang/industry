# ADR-2607072715: `cloud-itonami-isic-8730` (residential care activities for the elderly and disabled) deepened to `:implemented` -- thirteenth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`, the first twelve verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/
  `9521`/`9321` ADR-0001s (the governed-actor pattern this decision
  continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9321`, this ADR records the THIRTEENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-8730` (residential care activities for the elderly and
  disabled, ISIC division 87) -- a SECOND social/health-services
  vertical in this fleet, alongside `8620`'s clinic.

## Problem

An assisted-living facility's care-plan/incident-response finalization
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction assisted-living correctness** -- an official
   spec-basis citation from a real regulator, never fabricated.
2. **Care-plan review timeliness** -- the FIRST check in this fleet's
   temporal-sufficiency family to enforce a MAXIMUM elapsed-time
   ceiling ("not too much time may pass before the next review"),
   inverting the MINIMUM-wait direction `veterinary`/`funeral`
   established.
3. **Incident-flag resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TENTH distinct
   grounding, gating BOTH actuation ops of a dual-actuation actor off
   the same unresolved-flag concept for the first time.
4. **Real, high-stakes actuation, twice** -- finalizing a real
   resident's care plan and finalizing a real resident's incident
   response are two independently-gated real-world acts on the SAME
   entity.

See `cloud-itonami-isic-8730`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-8730` gains **EldercareOps-LLM ⊣ Eldercare
   Governor** -- `eldercare.*` namespaces, modeled closely on all
   twenty prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. `care-plan-review-overdue?`/`max-review-interval-days` is the FIRST
   check in this fleet's temporal-sufficiency family to invert the
   direction to a MAXIMUM ceiling: a resident's own `:days-since-last-
   care-plan-review` must NOT exceed 90 days, rather than a MINIMUM
   required wait (`veterinary.registry/withdrawal-period-
   insufficient?`'s/`funeral.registry/waiting-period-elapsed?`'s
   direction). A genuinely new comparison operator (`>` vs. `<`)
   against the same kind of ground-truth elapsed-time field.
3. `incident-flag-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a TENTH distinct grounding in this fleet, and the
   first to gate BOTH actuation ops of a dual-actuation actor off the
   SAME unresolved-flag concept.
4. Tested via the SCREENING op (`:incident/screen`) directly from the
   start, applying `parksafety`'s ADR-2607071922 Decision 5 lesson
   PROACTIVELY (a failing screen never persists its payload to the
   store, so the actuation ops alone could never discover the bad
   ground-truth flag through this check family without the screening
   op having actually been run first).
5. Dual actuation (`:actuation/finalize-care-plan`, `:actuation/
   finalize-incident-response`), matching `6512`'s/`6622`'s/`6520`'s/
   `6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s
   dual-actuation shape, each with its own history collection,
   sequence counter and dedicated double-finalization boolean guard
   (never a `:status` value, per `6492`'s ADR-0001 lesson).
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"8730"`, fleet-wide maturity counts move from 26
   implemented / 71 blueprint / 546 spec to 27 implemented / 70
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/eldercare/*` -- 37 tests / 175 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (care-
   plan finalization, incident-response finalization) plus five
   HARD-hold cases (no spec-basis, a care-plan review that exceeds its
   own regulatory ceiling, an unresolved incident flag screened
   directly and never reaching a human, and a double finalization of
   each actuation op) that never reach a human at all.

## Consequences

- (+) Residential eldercare gets the same governed, auditable-actor
  treatment as the twenty prior actors, and this fleet now has a
  THIRTEENTH concrete precedent for extending past ADR-2607032000's
  original scope, into a SECOND social/health-services vertical
  alongside `8620`'s clinic.
- (+) `care-plan-review-overdue?` is a genuine structural contribution:
  the first temporal-sufficiency check in this fleet to enforce a
  MAXIMUM ceiling rather than a MINIMUM wait.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/eldercare/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The incident-flag-unresolved test/demo correctly applied
  `parksafety`'s freshly-documented lesson proactively (tested via the
  screening op directly from the start) rather than re-discovering the
  same mistake -- concrete evidence that lessons recorded in this
  fleet's ADRs transfer forward to later builds.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `eldercare.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `max-review-interval-days` models a single representative
  regulatory review-interval ceiling (90 days), not a jurisdiction-by-
  jurisdiction survey, nor a full care-management/staffing-acuity
  program -- see `cloud-itonami-isic-8730`'s own ADR-0001 and README
  coverage table for the full honest-scope accounting.
- Fleet-wide: 27 actors now `:implemented` out of 643 total registry
  entries; 70 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All twelve of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`; mixing a different ISIC division (87, distinct from most of those twelve's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-8730` at `:blueprint` only | ❌ | The standing direction continues past `9321`; residential eldercare is a natural, well-precedented next domain, further diversifying this fleet's social/health-services coverage alongside `8620`'s clinic |
| See `cloud-itonami-isic-8730`'s own ADR-0001 Alternatives table for build-level decisions | -- | (temporal-check direction, unconditional-evaluation test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`, first twelve post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8730/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
