# ADR-2607071922: `cloud-itonami-isic-9321` (activities of amusement parks and theme parks) deepened to `:implemented` -- twelfth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`, the first
  eleven verticals built outside ADR-2607032000's original insurance/
  real-estate batch); ADR-2607032000 (the original batch, fully
  closed); `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/
  `6520`/`6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/
  `8530`/`9200`/`7500`/`9603`/`9521` ADR-0001s (the governed-actor
  pattern this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9521`, this ADR records the TWELFTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9321` (activities of amusement parks and theme parks, ISIC
  division 93) -- the first leisure-attractions vertical in this
  fleet.

## Problem

An amusement park's ride-reopening workflow bundles several distinct
concerns under one governed workflow:

1. **Jurisdiction ride-safety correctness** -- an official spec-basis
   citation from a real ride-safety regulator, never fabricated.
2. **Post-hold inspection verification** -- reuses the unconditional-
   evaluation screening discipline for a NINTH distinct grounding.
3. **Operator-staffing sufficiency** -- reuses this fleet's MINIMUM-
   threshold pure-ground-truth-recompute shape, but the FIRST instance
   to compare two fields on the SAME entity rather than one field
   against a shared constant.
4. **Real, safety-critical actuation, once** -- reopening a ride after
   a safety hold has direct physical-safety stakes for the public.

See `cloud-itonami-isic-9321`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks -- including a real test/
demo-design bug caught during this build's own verification (this
superproject ADR records the fleet-level context and registry/
maturity bookkeeping; the child repo's own ADR is the authoritative
architecture record).

## Decision

1. `cloud-itonami-isic-9321` gains **ParkOps-LLM ⊣ Ride Safety
   Governor** -- `parksafety.*` namespaces, modeled closely on all
   nineteen prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. Post-hold inspection screening reuses the unconditional-evaluation
   discipline (`casualty.governor/sanctions-violations`'s original
   fix) for a NINTH distinct grounding in this fleet.
3. `operators-sufficient?`/`operators-insufficient-violations` extends
   this fleet's MINIMUM-threshold family to a genuine new shape: a
   two-field-on-one-entity comparison (certified-operators-on-duty vs.
   the ride's own minimum-operators-required), rather than a field-vs-
   shared-constant comparison.
4. A REAL bug was caught during test verification: the initial test
   for the inspection-not-passed check called the ACTUATION op
   directly rather than the SCREENING op, exposing that the
   unconditional-evaluation check family fires only via the current
   proposal's own verdict or a prior store commit -- never an
   independent ground-truth recompute for the actuation op alone (a
   failing screen is itself a HARD hold and so never persists to the
   store). Fixed by testing the screening op directly, matching every
   sibling's established pattern exactly, and documented as a lesson
   on this check family's structural behavior.
5. Single actuation event (`:actuation/reopen-ride`), matching `6511`'s/
   `6621`'s/`6629`'s/`6612`'s/`6492`'s/`7120`'s/`8620`'s/`7500`'s/
   `9603`'s single-actuation shape.
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9321"`, fleet-wide maturity counts move from 25
   implemented / 72 blueprint / 546 spec to 26 implemented / 71
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/parksafety/*` -- 29 tests / 127 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end (after the test/demo fix):
   one clean lifecycle (a ride-reopening cycle) plus four HARD-hold
   cases (no spec-basis, a failed post-hold inspection screening that
   never reaches a human, an understaffed ride, a double reopening)
   that never reach a human at all -- all correct once the test/demo
   design was fixed.

## Consequences

- (+) Amusement-park/ride-safety gets the same governed, auditable-
  actor treatment as the nineteen prior actors, and this fleet now has
  a TWELFTH concrete precedent for extending past ADR-2607032000's
  original scope, into a genuinely different domain (leisure
  attractions, ISIC division 93).
- (+) `operators-sufficient?`/`operators-insufficient-violations` is a
  genuine structural contribution: the first minimum-threshold check
  in this fleet to compare two fields on the same entity.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  parksafety/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern every sibling actor uses.
- (+) The test/demo bug was caught by the SAME discipline that has
  caught every real bug in this fleet -- running the full test suite
  and independently verifying the demo ledger -- and its root cause (a
  structural property of the unconditional-evaluation check family,
  not a governor logic error) is now explicitly documented in the
  child repo's own ADR-0001, so future builds don't repeat the
  mistake.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `parksafety.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `operators-sufficient?` models only a certified-operator-
  headcount-vs-minimum comparison, not a full ride-safety engineering
  program -- see `cloud-itonami-isic-9321`'s own ADR-0001 and README
  coverage table for the full honest-scope accounting.
- Fleet-wide: 26 actors now `:implemented` out of 643 total registry
  entries; 71 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All eleven of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`; mixing a different ISIC division (93, distinct from those eleven's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9321` at `:blueprint` only | ❌ | The standing direction continues past `9521`; amusement parks are a natural, well-precedented next domain, further diversifying this fleet into leisure attractions |
| See `cloud-itonami-isic-9321`'s own ADR-0001 Alternatives table for build-level decisions | -- | (whether to change the governor's check design vs. fix the test after the bug was found, engineering-program scope, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`, first eleven
  post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9321/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor, including the
  test/demo-design bug narrative)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
