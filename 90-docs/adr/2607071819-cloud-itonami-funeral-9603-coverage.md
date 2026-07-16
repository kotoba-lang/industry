# ADR-2607071819: `cloud-itonami-isic-9603` (funeral and related activities) deepened to `:implemented` -- tenth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`, the first nine verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500` ADR-0001s
  (the governed-actor pattern this decision continues); langgraph-clj
  ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `7500`, this ADR records the TENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9603` (funeral and related activities, ISIC division 96) -- the
  first personal-services/death-care vertical in this fleet, and the
  starkest single-actuation irreversibility case built to date.

## Problem

A funeral home's disposition workflow bundles several distinct
concerns under one governed workflow:

1. **Jurisdiction death-care correctness** -- an official spec-basis
   citation from a real death-care regulator, never fabricated.
2. **Statutory waiting-period sufficiency** -- reuses `veterinary.
   registry`'s NEWLY-established temporal-sufficiency pure-ground-
   truth-recompute shape for a SECOND domain instance, but applied
   UNCONDITIONALLY (every decedent, unlike veterinary's food-
   producing-animal-only gate) -- a real statutory figure (24 hours,
   Japan's Cemetery and Burial Act Article 3), not an invented one.
3. **Disposition-authorization verification** -- reuses the
   unconditional-evaluation discipline for a SEVENTH distinct
   grounding.
4. **Single, IRREVERSIBLE actuation** -- performing a burial or
   cremation cannot be undone once completed, the starkest instance of
   this fleet's "actuation is always a human call" invariant to date.

See `cloud-itonami-isic-9603`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9603` gains **FuneralOps-LLM ⊣ Funeral Services
   Governor** -- `funeral.*` namespaces, modeled closely on all
   seventeen prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. `waiting-period-elapsed?`/`waiting-period-not-elapsed-violations`
   reuses `veterinary.registry/withdrawal-period-insufficient?`'s
   temporal-sufficiency shape for a SECOND domain instance, applied
   UNCONDITIONALLY to every case (rather than gated on a type tag) --
   proving the newly-established shape generalizes to both a
   conditionally-gated and an unconditional application across two
   consecutive builds.
3. `funeral.registry/minimum-waiting-period-hours` (24) is drawn
   directly from a real statute (Japan's Cemetery and Burial Act
   Article 3), cited in `funeral.facts` -- extending this fleet's
   "never fabricate a number, cite a real source" discipline to a
   numeric regulatory constant.
4. Authorization screening reuses the unconditional-evaluation
   discipline (`casualty.governor/sanctions-violations`'s original
   fix) for a SEVENTH distinct grounding in this fleet.
5. Single actuation event (`:actuation/perform-disposition`), matching
   `6511`'s/`6621`'s/`6629`'s/`6612`'s/`6492`'s/`7120`'s/`8620`'s/
   `7500`'s single-actuation shape -- but uniquely IRREVERSIBLE among
   this fleet's actuation events to date.
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9603"`, fleet-wide maturity counts move from 23
   implemented / 74 blueprint / 546 spec to 24 implemented / 73
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/funeral/*` -- 29 tests / 125 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean lifecycle (a
   final-disposition cycle) plus four HARD-hold cases (no spec-basis,
   a statutory waiting period that hasn't yet elapsed, an unverified
   disposition authorization, a double disposition) that never reach a
   human at all -- all correct on the FIRST demo run.

## Consequences

- (+) Funeral/death-care gets the same governed, auditable-actor
  treatment as the seventeen prior actors, and this fleet now has TEN
  concrete precedents for extending past ADR-2607032000's original
  scope, into genuinely different domains -- including the fleet's
  starkest irreversibility case to date.
- (+) `waiting-period-elapsed?`/`waiting-period-not-elapsed-
  violations` proves the newly-established temporal-sufficiency check
  shape (introduced by `7500`) generalizes across BOTH a
  conditionally-gated application and an unconditional application
  within two consecutive builds, regression-tested by `test/funeral/
  governor_contract_test.clj`'s `waiting-period-not-elapsed-is-held`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/funeral/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) Both the demo and the full test suite passed clean on the first
  run -- no bug this time, unlike `6492`/`6920`.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `funeral.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `waiting-period-elapsed?` models only a single representative
  statutory figure, not a jurisdiction-by-jurisdiction survey of every
  waiting-period variant, nor a full next-of-kin priority hierarchy
  with objection resolution -- see `cloud-itonami-isic-9603`'s own
  ADR-0001 and README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 24 actors now `:implemented` out of 643 total registry
  entries; 73 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All nine of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`; mixing a different ISIC division (96, distinct from those nine's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9603` at `:blueprint` only | ❌ | The standing direction continues past `7500`; funeral/death-care is a natural, well-precedented next domain that also proves the newly-established temporal-sufficiency check shape generalizes to an unconditional application |
| See `cloud-itonami-isic-9603`'s own ADR-0001 Alternatives table for build-level decisions | -- | (next-of-kin priority hierarchy scope, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`, first nine post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9603/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
