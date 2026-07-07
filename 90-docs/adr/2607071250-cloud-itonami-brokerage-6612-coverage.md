# ADR-2607071250: `cloud-itonami-isic-6612` (securities brokerage) deepened to `:implemented` -- first vertical outside ADR-2607032000's original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607032000 (`cloud-itonami` insurance (ISIC 65/66) +
  real-estate (ISIC 68) coverage push -- its original 8-repo batch
  closed with Addendum 7 / `cloud-itonami-isic-6820`); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820` ADR-0001s
  (the governed-actor pattern this decision continues); langgraph-clj
  ADR-0001 (Pregel superstep + interrupt + Datomic checkpoint)
- Context: The owner's standing "pick a new ISIC blueprint vertical"
  direction (2026-06-28, restated across this session) produced eight
  consecutive governed-actor promotions across ADR-2607032000's
  original insurance/real-estate batch. That batch is now fully
  closed (all 7 insurance classes + real estate `:implemented`). This
  ADR records the FIRST promotion under the same standing direction
  that targets a vertical OUTSIDE that batch's original scope --
  `cloud-itonami-isic-6612` (security and commodity contracts
  brokerage, ISIC division 64/66) -- since there is no existing ADR
  whose scope covers this vertical, this is a new ADR rather than an
  addendum to ADR-2607032000.

## Problem

`cloud-itonami-isic-6612` published a business/operator-model blueprint
(Broker-LLM ⊣ Brokerage Governor, `:blueprint` maturity) but had no
governed actor implementation. Deepening it required the same
discipline every prior actor in this fleet has established:

1. **Jurisdiction broker-dealer registration/disclosure correctness**
   -- an official spec-basis citation, never fabricated.
2. **Trade arithmetic correctness** -- independently re-derive a
   claimed trade value from quantity and price, never trust it as-is.
3. **Conflict-of-interest AND suitability screening** -- two
   INDEPENDENT screening concerns, both evaluated with the
   unconditional-evaluation discipline this fleet established
   (`casualty.governor/sanctions-violations`, reused three times
   before this build for identity-screening checks, and here extended
   for the FIRST time to a non-identity compatibility-matching check).
4. **A single real actuation event** -- executing a trade on a
   client's behalf, with no second actuation event to manufacture for
   symmetry with sibling shape.

See `cloud-itonami-isic-6612`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks, and the decisions made in
this build (this superproject ADR records the fleet-level context and
registry/maturity bookkeeping; the child repo's own ADR is the
authoritative architecture record).

## Decision

1. `cloud-itonami-isic-6612` gains **Broker-LLM ⊣ Brokerage Governor**
   -- `brokerage.*` namespaces, modeled closely on all eight prior
   actors' Store/Registry/Governor/Phase/Advisor/Operation/Sim shape
   and the SAME generic langgraph-clj StateGraph.
2. `suitability-failure-violations` generalizes the unconditional-
   evaluation discipline (established for conflict-of-interest/
   sanctions screening) to a genuinely different KIND of screening
   question -- a risk-tolerance compatibility match between an order's
   risk level and an account's risk profile, not an identity/
   relationship screen. This is this build's principal structural
   contribution to the fleet's shared discipline.
3. `trade-value-mismatch-violations` reuses the exact-match
   independent-recompute pattern (`6629`/`6520`/`6820`) on a fifth,
   deliberately trivial formula: quantity times price.
4. `spec-basis-violations` proactively guards on the order's existence
   before firing (reusing `6530`'s/`6820`'s lesson from the start,
   avoiding a spurious co-firing with `order-missing-violations` on
   the same op).
5. This actor has exactly ONE actuation event (`:actuation/execute-
   trade`), returning to the single-actuation shape `6511`/`6621`/
   `6629` established rather than the dual-actuation shape five of the
   eight prior actors settled into -- an honest reflection of this
   domain having only one real-world financial act, not a
   manufactured second one for shape-consistency.
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"6612"`, fleet-wide maturity counts move from 14
   implemented / 83 blueprint / 546 spec to 15 implemented / 82
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/brokerage/*` -- 37 tests / 163 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean intake-through-
   execution lifecycle plus seven HARD-hold cases (no spec-basis, an
   order filed against an inactive account, an undisclosed conflict of
   interest, an unsuitable order, a trade-value mismatch, a nonexistent
   order, a double execution) that never reach a human at all -- all
   correct on the FIRST demo run (no governance-logic bug this build;
   the only slip caught was a test assertion checking the wrong ledger
   position, caught immediately by the test run itself failing loudly).

## Consequences

- (+) Securities/commodity brokerage gets the same governed, auditable-
  actor treatment as the eight prior actors, and this fleet now has a
  concrete precedent for extending past its original insurance/real-
  estate scope under the SAME standing authorization -- future
  vertical picks do not need to stay within ADR-2607032000's original
  batch.
- (+) The unconditional-evaluation discipline is now proven to
  generalize beyond identity/relationship screening (suitability is a
  compatibility match, not a screen for who someone is) -- a durable
  lesson for any future screening-then-actuation op pair in this
  fleet.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/brokerage/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `brokerage.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `compute-order-value` and `suitable-for-account?` are both
  deliberately simplified (no commissions/fees/multi-leg orders/
  partial fills; no concentration limits/time-horizon analysis/
  product-complexity tiering) -- see `cloud-itonami-isic-6612`'s own
  ADR-0001 and README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 15 actors now `:implemented` out of 643 total registry
  entries (425 classes + 218 groups); 82 remain `:blueprint`, 546
  remain `:spec`-only. The next "pick a new ISIC blueprint vertical"
  firing is free to select from ANY remaining `:blueprint`-tier
  `cloud-itonami-*` entry, not constrained to any prior ADR's original
  batch.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to ADR-2607032000 | ❌ | That ADR's scope is explicitly insurance (65/66) + real estate (68); ISIC 6612 is division 64/66 brokerage, outside that scope even though it shares the same governed-actor pattern -- a new ADR keeps scope boundaries honest |
| Keep `cloud-itonami-isic-6612` at `:blueprint` only | ❌ | The standing "pick a new ISIC blueprint vertical" direction is not scoped to ADR-2607032000's original batch; stopping there once that batch closed would be a narrower reading of the authorization than warranted |
| Split order filing into its own actuation event, for consistency with the dual-actuation majority | ❌ | See `cloud-itonami-isic-6612`'s own ADR-0001 Alternatives table -- this domain genuinely has one real-world financial act |

## References

- ADR-2607032000 (`cloud-itonami` insurance + real-estate coverage
  push, Addenda 1-7)
- `cloud-itonami-isic-6612/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
