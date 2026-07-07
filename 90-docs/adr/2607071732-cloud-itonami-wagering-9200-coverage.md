# ADR-2607071732: `cloud-itonami-isic-9200` (gambling and betting activities) deepened to `:implemented` -- eighth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`, the first seven verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530` ADR-0001s (the governed-
  actor pattern this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8530`, this ADR records the EIGHTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9200` (gambling and betting activities, ISIC division 92) --
  the first leisure/recreation-services vertical in this fleet,
  continuing the deliberate diversification beyond finance/insurance,
  professional/technical services, healthcare and education.

## Problem

A gaming operator's wager-settlement workflow bundles several distinct
concerns under one governed workflow:

1. **Jurisdiction gaming-licensing correctness** -- an official spec-
   basis citation from a real gaming regulator, never fabricated.
2. **Payout correctness** -- a pure ground-truth recompute (the SAME
   shape `pension.governor`'s/`reinsurance.governor`'s/`realty.
   governor`'s/`brokerage.governor`'s exact-match checks establish),
   reusing this fleet's established EXACT-MATCH independent-recompute
   family for a further domain (claimed payout must equal stake times
   odds).
3. **Patron compliance** -- reuses the unconditional-evaluation
   discipline for a sixth distinct grounding.
4. **Dual actuation, on the SAME entity, with dedicated-boolean guard
   designs informed by six prior builds** -- accepting a wager and
   settling a payout, each guarded by its own dedicated boolean
   (`:wager-accepted?`/`:payout-settled?`, informed by `6492`'s
   status-lifecycle bug and `6920`'s/`6611`'s/`7120`'s/`8620`'s/
   `8530`'s deliberate avoidance of it), not a `:status` value.

See `cloud-itonami-isic-9200`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9200` gains **WagerOps-LLM ⊣ Responsible
   Gambling Governor** -- `wagering.*` namespaces, modeled closely on
   all fifteen prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. `payout-matches-claim?`/`payout-mismatch-violations` reuses this
   fleet's established EXACT-MATCH independent-recompute family
   (`pension.registry`'s apportionment check/`reinsurance.registry`'s
   recovery check/`realty.registry`'s fee check/`brokerage.registry`'s
   order-value check) for the gambling domain -- a deliberate,
   straightforward reuse rather than a new arithmetic shape.
3. Patron compliance screening reuses the unconditional-evaluation
   discipline (`casualty.governor/sanctions-violations`'s original
   fix) for a SIXTH distinct grounding in this fleet: an unresolved
   flag HARD-holds both the screening op itself and both actuation
   ops.
4. Dual actuation on the SAME wager entity (mirroring `marketadmin.
   store`'s dual admission/halt-lift design and `registrar.store`'s
   dual grade/degree design), with independent dedicated-boolean
   double-actuation guards for each -- the SAME design choice
   `accounting.governor`'s/`marketadmin.governor`'s/`testlab.
   governor`'s/`clinic.governor`'s/`registrar.governor`'s guards make,
   now applied correctly across a SIXTH consecutive build, each
   explicitly informed by `6492`'s status-lifecycle bug rather than
   re-derived by shape-analogy.
5. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9200"`, fleet-wide maturity counts move from 21
   implemented / 76 blueprint / 546 spec to 22 implemented / 75
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
6. `test/wagering/*` -- 37 tests / 176 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (a
   wager-acceptance cycle, a payout-settlement cycle) plus four HARD-
   hold cases (no spec-basis, a payout mismatch, an unresolved patron
   compliance flag, a double acceptance/settlement) that never reach a
   human at all -- all correct on the FIRST demo run.

## Consequences

- (+) Gambling/betting gets the same governed, auditable-actor
  treatment as the fifteen prior actors, and this fleet now has EIGHT
  concrete precedents (`6612`, `6492`, `6920`, `6611`, `7120`, `8620`,
  `8530`, `9200`) for extending past ADR-2607032000's original scope,
  into genuinely different domains (finance, professional services,
  technical/scientific services, healthcare, education, and now
  leisure/recreation services).
- (+) `payout-matches-claim?`/`payout-mismatch-violations` extends
  this fleet's exact-match-recompute family to a fifth domain
  instance, regression-tested by `test/wagering/governor_contract_
  test.clj`'s `payout-mismatch-is-held`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/wagering/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The dedicated-boolean double-actuation-guard lesson (from
  `6492`'s bug) has now been applied correctly BY DESIGN across SIX
  consecutive builds (`6920`, `6611`, `7120`, `8620`, `8530`, `9200`),
  each explicitly citing the prior lesson rather than re-deriving it
  by pattern-matching.
- (+) Both the demo and the full test suite passed clean on the first
  run -- no bug this time, unlike `6492`/`6920`.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `wagering.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `compute-payout` models only a single flat stake-times-odds
  payout, not a full multi-leg/parlay/progressive-jackpot payout
  engine -- see `cloud-itonami-isic-9200`'s own ADR-0001 and README
  coverage table for the full honest-scope accounting.
- Fleet-wide: 22 actors now `:implemented` out of 643 total registry
  entries; 75 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/ADR-2607071640/ADR-2607071654/ADR-2607071717 | ❌ | All seven of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`; mixing a different ISIC division (92, vs. those seven's 64/66/69/71/86/85) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9200` at `:blueprint` only | ❌ | The standing direction continues past `8530`; gambling/betting is a natural, well-precedented next domain, continuing the deliberate diversification into leisure/recreation services, a division this fleet had not yet touched |
| See `cloud-itonami-isic-9200`'s own ADR-0001 Alternatives table for build-level decisions | -- | (exact-match reuse vs. a new arithmetic shape, multi-leg/parlay-engine scope, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`, first seven post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9200/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
