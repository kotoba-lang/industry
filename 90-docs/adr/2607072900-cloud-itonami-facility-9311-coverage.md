# ADR-2607072900: `cloud-itonami-isic-9311` (operation of sports facilities) deepened to `:implemented` -- twentieth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`, the first nineteen
  verticals built outside ADR-2607032000's original insurance/real-
  estate batch); ADR-2607032000 (the original batch, fully closed);
  `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/
  `6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/
  `8890`/`8610` ADR-0001s (the governed-actor pattern this decision
  continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8610`, this ADR records the TWENTIETH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9311` (operation of sports facilities, ISIC division 93) -- a
  SECOND leisure-attractions vertical alongside `9321`'s amusement
  parks, but for gyms/stadiums/arenas rather than mechanical rides.

## Problem

A sports facility's use-authorization workflow bundles several
distinct concerns under one governed workflow:

1. **Jurisdiction assembly-venue/occupancy-safety correctness** -- an
   official spec-basis citation from a real regulator (総務省消防庁/
   NFPA/SGSA/state Bauaufsichtsbehörden), never fabricated.
2. **Occupancy sufficiency** -- the FIRST non-temporal instance of
   this fleet's MAXIMUM-ceiling family (`eldercare.registry/care-
   plan-review-overdue?`, `museum.registry/provenance-gap-exceeds-
   threshold?` and `salon.registry/patch-test-window-exceeded?`
   established the first three, all temporal), reusing `parksafety.
   registry/operators-sufficient?`'s two-field-on-one-entity
   comparison shape for the MAXIMUM direction.
3. **Post-hold inspection verification** -- reuses the unconditional-
   evaluation screening discipline for a SEVENTEENTH distinct
   grounding overall, and a SECOND specifically for the post-hold-
   inspection concept (after `parksafety`).
4. **Real, high-stakes actuation, once** -- authorizing real facility
   use during or after a flagged safety condition is a single
   actuation event with direct public-safety stakes.

See `cloud-itonami-isic-9311`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9311` gains **FacilityOps-LLM ⊣ Facility
   Safety Governor** -- `facility.*` namespaces, modeled closely on
   all twenty-seven prior actors' Store/Registry/Governor/Phase/
   Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. `occupancy-exceeds-capacity?` is the FIRST non-temporal instance of
   the MAXIMUM-ceiling family, comparing a facility's own current
   occupancy against its own recorded capacity limit -- reusing
   `parksafety`'s two-field-on-one-entity comparison shape for the
   MAXIMUM direction instead of MINIMUM.
3. `inspection-not-passed-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a SEVENTEENTH distinct grounding overall, and a
   SECOND specifically for the post-hold-inspection concept
   (`parksafety` established it for amusement rides; this reuses it
   verbatim for assembly venues -- the identical real-world safety
   concern applies to both physical structures).
4. Tested via the SCREENING op (`:inspection/screen`) directly from
   the start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital` lesson
   PROACTIVELY for an eighth consecutive vertical -- notably the
   first time this specific lesson has been applied back to the SAME
   underlying screening concept (`parksafety`'s own inspection-not-
   passed bug) in a different vertical, closing the loop on the
   original mistake.
5. Single actuation (`:actuation/authorize-facility-use`), matching
   `6511`'s/`6621`'s/`6629`'s/`6612`'s/`6492`'s/`7120`'s/`8620`'s/
   `7500`'s/`9603`'s/`9321`'s/`9602`'s/`9000`'s single-actuation
   shape, with its own history collection, sequence counter and
   dedicated double-actuation boolean guard (never a `:status` value,
   per `6492`'s ADR-0001 lesson).
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9311"`, fleet-wide maturity counts move from 33
   implemented / 64 blueprint / 546 spec to 34 implemented / 63
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/facility/*` -- 30 tests / 127 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean single-actuation
   lifecycle (intake → assess → inspection screen → authorize use)
   plus four HARD-hold cases (no spec-basis, occupancy exceeding
   capacity, a failed post-hold inspection screened directly and never
   reaching a human, and a double authorization attempt) that never
   reach a human at all.

## Consequences

- (+) Sports-facility operation gets the same governed, auditable-
  actor treatment as the twenty-seven prior actors, and this fleet now
  has a TWENTIETH concrete precedent for extending past
  ADR-2607032000's original scope, deepening leisure-attractions
  coverage (ISIC division 93) alongside `9321`'s amusement parks with
  a genuinely different physical-safety concern (occupancy/capacity
  vs. ride mechanics).
- (+) `occupancy-exceeds-capacity?` is a genuine structural
  contribution: the first non-temporal instance of the MAXIMUM-
  ceiling family, further validating that family's generality beyond
  elapsed-time comparisons.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/facility/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The inspection-not-passed test/demo correctly applied the
  established SCREENING-op-directly pattern for an eighth consecutive
  vertical -- and, notably, this is the first time the lesson has
  closed the loop back onto the SAME underlying screening concept
  (`parksafety`'s own original bug) in a different vertical, further
  evidence that lessons recorded in this fleet's ADRs continue to
  transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `facility.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `occupancy-exceeds-capacity?` models only a single occupancy-
  count-vs-capacity-limit comparison, not a full fire-code/life-safety
  engineering review -- see `cloud-itonami-isic-9311`'s own ADR-0001
  and README coverage table for the full honest-scope accounting.
- Fleet-wide: 34 actors now `:implemented` out of 643 total registry
  entries; 63 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All nineteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`; mixing a different sub-domain into any would blur scope boundaries even where the ISIC division (93) overlaps with `9321` |
| Keep `cloud-itonami-isic-9311` at `:blueprint` only | ❌ | The standing direction continues past `8610`; sports-facility operation is a natural, well-precedented next domain, deepening this fleet's leisure-attractions coverage with a genuinely different physical-safety concern than `9321`'s mechanical rides |
| See `cloud-itonami-isic-9311`'s own ADR-0001 Alternatives table for build-level decisions | -- | (check-family framing, screening-op test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`, first nineteen
  post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9311/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
