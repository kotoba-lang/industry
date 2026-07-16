# ADR-2607071618: `cloud-itonami-isic-6611` (administration of financial markets) deepened to `:implemented` -- fourth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351 (`6612`/`6492`/
  `6920`, the first three verticals built outside ADR-2607032000's
  original insurance/real-estate batch); ADR-2607032000 (the original
  batch, fully closed); `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/
  `6629`/`6520`/`6530`/`6820`/`6612`/`6492`/`6920` ADR-0001s (the
  governed-actor pattern this decision continues); langgraph-clj
  ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `6612`/`6492`/`6920`, this ADR records the
  FOURTH promotion outside ADR-2607032000's original batch: `cloud-
  itonami-isic-6611` (administration of financial markets, ISIC
  division 64/66), a return to the finance division after `6920`'s
  excursion into professional services.

## Problem

`cloud-itonami-isic-6611` published a business/operator-model
blueprint (MarketOps-LLM ⊣ Market Administration Governor, `:blueprint`
maturity) but had no governed actor implementation. Deepening it
required:

1. **Jurisdiction exchange-registration/listing-rule correctness** --
   an official spec-basis citation from a real markets regulator,
   never fabricated.
2. **Listing-standard correctness** -- a pure ground-truth recompute
   (the SAME shape `credit.governor`'s/`accounting.governor`'s checks
   establish), but the FIRST check in this fleet to enforce a MINIMUM
   threshold rather than every prior cap-check's MAXIMUM ceiling.
3. **Surveillance-flag resolution** -- reuses the unconditional-
   evaluation discipline for a further application.
4. **Dual actuation, with an inline-state guard design** -- admitting a
   listing and lifting a trade halt, with the halt itself living
   inline on the listing (mirroring `realty`'s pending-contract
   pattern) and double-guards checking dedicated booleans (informed by
   `6492`'s status-lifecycle bug, not `:status` values).

See `cloud-itonami-isic-6611`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-6611` gains **MarketOps-LLM ⊣ Market
   Administration Governor** -- `marketadmin.*` namespaces, modeled
   closely on all eleven prior actors' Store/Registry/Governor/Phase/
   Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. `listing-standard-not-met-violations` is the FIRST check in this
   fleet's shared vocabulary to enforce a MINIMUM threshold (market
   capitalization must not fall below a reference value) rather than a
   maximum ceiling -- proving the pure-ground-truth-recompute family
   established by `6492`/`6920` generalizes to both inequality
   directions.
3. An active trade halt lives inline on the listing (mirroring
   `realty.store`'s pending-contract pattern), and lifting it clears
   the field, doubling as the double-lift guard.
4. Double-admission guard checks a dedicated `:admitted?` boolean, not
   `:status` -- the same design choice `accounting.governor`'s guards
   make, informed by `6492`'s status-lifecycle bug.
5. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"6611"`, fleet-wide maturity counts move from 17
   implemented / 80 blueprint / 546 spec to 18 implemented / 79
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
6. `test/marketadmin/*` -- 37 tests / 171 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (a
   listing-admission cycle, and a trade-halt-lift cycle) plus five
   HARD-hold cases (no spec-basis, a listing below the minimum listing
   standard, an unresolved surveillance flag, a halt-lift attempt with
   no active halt, a double admission) that never reach a human at
   all -- all correct on the FIRST demo run.

## Consequences

- (+) Exchange/market administration gets the same governed,
  auditable-actor treatment as the eleven prior actors, and this fleet
  now has FOUR concrete precedents (`6612`, `6492`, `6920`, `6611`) for
  extending past ADR-2607032000's original scope.
- (+) `listing-standard-not-met-violations` is a genuine structural
  contribution to this fleet's shared vocabulary of governor-check
  shapes: the first minimum-threshold check, proving the pure-ground-
  truth-recompute family generalizes to both inequality directions.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  marketadmin/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern every sibling actor uses.
- (+) Both established lessons (defensive field access from `6920`,
  dedicated-boolean guards from `6492`) were applied correctly from
  the first draft, and the demo passed clean on the first run.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `marketadmin.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `minimum-market-cap` models only a single representative
  threshold, not a full listing review -- see `cloud-itonami-isic-
  6611`'s own ADR-0001 and README coverage table for the full honest-
  scope accounting.
- Fleet-wide: 18 actors now `:implemented` out of 643 total registry
  entries; 79 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to ADR-2607071250/ADR-2607071320/ADR-2607071351 | ❌ | All three of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`; mixing a different ISIC vertical (6611) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-6611` at `:blueprint` only | ❌ | The standing direction continues past `6612`/`6492`/`6920`; exchange administration is a natural, well-precedented next finance-adjacent domain |
| See `cloud-itonami-isic-6611`'s own ADR-0001 Alternatives table for build-level decisions | -- | (full listing review vs. simplified threshold, separate halt collection vs. inline field, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351 (`6612`/`6492`/`6920`,
  first three post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-6611/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
