# ADR-2607071849: `cloud-itonami-isic-9521` (repair of consumer electronics) deepened to `:implemented` -- eleventh vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`, the first ten verticals built
  outside ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`
  ADR-0001s (the governed-actor pattern this decision continues);
  langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9603`, this ADR records the ELEVENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9521` (repair of consumer electronics, ISIC division 95) --
  the first repair-services vertical in this fleet, and a second
  personal/repair-services build alongside `9603`'s division 96.

## Problem

An electronics-repair shop's completion/return workflow bundles
several distinct concerns under one governed workflow:

1. **Jurisdiction consumer-product-safety correctness** -- an official
   spec-basis citation from a real product-safety regulator, never
   fabricated.
2. **Parts-cost correctness** -- reuses this fleet's established
   EXACT-MATCH independent-recompute family (`pension.registry`'s
   apportionment check/`reinsurance.registry`'s recovery check/
   `realty.registry`'s fee check/`brokerage.registry`'s order-value
   check/`wagering.registry`'s payout check) for a SIXTH domain
   instance (repair-invoice arithmetic: claimed parts cost must equal
   quantity times unit-price).
3. **Post-repair safety verification** -- reuses the unconditional-
   evaluation screening discipline for an EIGHTH distinct grounding.
4. **Dual actuation, on the SAME entity** -- completing a repair and
   returning a device are two distinct real-world acts.

See `cloud-itonami-isic-9521`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9521` gains **RepairOps-LLM ⊣ Repair Shop
   Governor** -- `repairshop.*` namespaces, modeled closely on all
   eighteen prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. `parts-cost-matches-claim?`/`parts-cost-mismatch-violations`
   extends this fleet's exact-match-recompute family to a SIXTH domain
   instance -- a deliberate, straightforward reuse rather than a new
   shape, since quantity x unit-price is genuinely the same arithmetic
   family `wagering.registry/payout-matches-claim?` established.
3. `safety-test-not-passed-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for an EIGHTH distinct grounding in this fleet.
4. Dual actuation on the SAME ticket entity (mirroring `marketadmin.
   store`'s/`registrar.store`'s/`wagering.store`'s dual-actuation-on-
   one-entity design), with independent dedicated-boolean double-
   actuation guards for each -- the SAME design choice this fleet's
   prior guards make, now applied correctly across a NINTH consecutive
   build.
5. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9521"`, fleet-wide maturity counts move from 24
   implemented / 73 blueprint / 546 spec to 25 implemented / 72
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
6. `test/repairshop/*` -- 37 tests / 176 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (a
   repair-completion cycle, a device-return cycle) plus four HARD-hold
   cases (no spec-basis, a parts-cost mismatch, a failed post-repair
   safety test, a double completion/return) that never reach a human
   at all -- all correct on the FIRST demo run.

## Consequences

- (+) Electronics-repair services get the same governed, auditable-
  actor treatment as the eighteen prior actors, and this fleet now has
  a SECOND repair/personal-services precedent (alongside `9603`) for
  extending past ADR-2607032000's original scope.
- (+) `parts-cost-matches-claim?`/`parts-cost-mismatch-violations`
  extends this fleet's exact-match-recompute family to a sixth domain
  instance, regression-tested by `test/repairshop/governor_contract_
  test.clj`'s `parts-cost-mismatch-is-held`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  repairshop/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern every sibling actor uses.
- (+) Both the demo and the full test suite passed clean on the first
  run -- no bug this time, unlike `6492`/`6920`.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `repairshop.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `compute-parts-cost` models only a single flat quantity-times-
  unit-price calculation, not a full parts-catalog/labor/tax invoice
  engine -- see `cloud-itonami-isic-9521`'s own ADR-0001 and README
  coverage table for the full honest-scope accounting.
- Fleet-wide: 25 actors now `:implemented` out of 643 total registry
  entries; 72 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All ten of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`; mixing a different ISIC division (95, distinct from those ten's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9521` at `:blueprint` only | ❌ | The standing direction continues past `9603`; electronics-repair is a natural, well-precedented next domain, further diversifying personal/repair services beyond `9603`'s division 96 into division 95 |
| See `cloud-itonami-isic-9521`'s own ADR-0001 Alternatives table for build-level decisions | -- | (exact-match reuse vs. a new arithmetic shape, invoice-engine scope, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`, first ten post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9521/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
