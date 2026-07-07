# ADR-2607071351: `cloud-itonami-isic-6920` (accounting, bookkeeping and auditing) deepened to `:implemented` -- first professional-services vertical

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250 (`cloud-itonami-isic-6612`, first vertical
  outside ADR-2607032000's original batch); ADR-2607071320 (`cloud-
  itonami-isic-6492`, second); ADR-2607032000 (the original batch,
  fully closed); `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/
  `6520`/`6530`/`6820`/`6612`/`6492` ADR-0001s (the governed-actor
  pattern this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `6612`/`6492`, this ADR records the THIRD
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-6920` (accounting, bookkeeping and auditing activities, ISIC
  division 69), the first PROFESSIONAL-SERVICES vertical in this
  fleet.

## Problem

`cloud-itonami-isic-6920` published a business/operator-model
blueprint (Ledger-LLM ⊣ Audit Independence Governor, `:blueprint`
maturity) but had no governed actor implementation. Deepening it
required:

1. **Jurisdiction professional-standards/independence disclosure
   correctness** -- an official spec-basis citation from a real
   accounting/auditing standard-setter, never fabricated.
2. **Auditor independence** -- the accounting profession's own specific
   term for the exact concept `casualty.governor`'s conflict-of-
   interest checks screen for, reused a further time.
3. **Engagement-type validity** -- a genuinely NEW check kind for this
   fleet: does an actuation op target the right engagement type (audit
   vs. tax-filing)?
4. **Trial-balance correctness** -- a pure ground-truth recompute (the
   fundamental accounting equation, assets = liabilities + equity), a
   sixth domain-specific formula and a new equality-of-sums arithmetic
   shape within the "pure ground-truth recompute" family `6492`
   established.
5. **A real bug found and fixed during test verification**: both the
   advisor and the governor initially crashed with a
   `NullPointerException` when recomputing a trial balance for a
   `:tax-filing` engagement (which has no `:assets`/`:liabilities`/
   `:equity` fields at all) -- fixed by guarding the recompute on
   `:engagement-type :audit` in BOTH places.

See `cloud-itonami-isic-6920`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks, and this build's bug (this
superproject ADR records the fleet-level context and registry/
maturity bookkeeping; the child repo's own ADR is the authoritative
architecture record).

## Decision

1. `cloud-itonami-isic-6920` gains **Ledger-LLM ⊣ Audit Independence
   Governor** -- `accounting.*` namespaces, modeled closely on all ten
   prior actors' Store/Registry/Governor/Phase/Advisor/Operation/Sim
   shape and the SAME generic langgraph-clj StateGraph.
2. `wrong-engagement-type-violations` is a genuinely NEW check kind: a
   type-tag validity check, neither arithmetic nor party-screening --
   no sibling actor bundled two structurally different actuation
   targets under one entity with a type tag before this build.
3. `trial-balance-out-of-balance-violations` extends `cloud-itonami-
   isic-6492`'s pure-ground-truth-recompute family to an equality-of-
   sums arithmetic shape (one side of an equation against the other),
   distinct from `6492`'s threshold-ratio shape.
4. Double-issuance/double-filing guards check dedicated boolean facts
   (`:opinion-issued?`/`:filing-submitted?`) rather than a `:status`
   value -- a design choice deliberately informed by `6492`'s status-
   lifecycle bug, not merely reused by analogy.
5. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"6920"`, fleet-wide maturity counts move from 16
   implemented / 81 blueprint / 546 spec to 17 implemented / 80
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
6. `test/accounting/*` -- 38 tests / 172 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (an
   audit-opinion-issuance cycle, and a tax-filing-submission cycle)
   plus six HARD-hold cases (no spec-basis, an independence conflict,
   an out-of-balance trial balance, a wrong-engagement-type mismatch, a
   double issuance, a double filing) that never reach a human at all --
   all correct after the NullPointerException fix above.

## Consequences

- (+) Accounting/auditing practice gets the same governed, auditable-
  actor treatment as the ten prior actors, extending the pattern to a
  genuinely different domain (professional services) for the first
  time, and this fleet now has THREE concrete precedents (`6612`,
  `6492`, `6920`) for extending past ADR-2607032000's original scope.
- (+) `wrong-engagement-type-violations` is a genuine new check kind
  for this fleet's shared vocabulary of governor-check shapes.
- (+) The double-guard design (dedicated booleans, not `:status`)
  demonstrates a lesson applied by DESIGN CHOICE informed by the
  immediately-preceding build's bug (`6492`'s status-lifecycle trap),
  rather than merely reused by analogy -- exactly the failure mode
  that caused `6492`'s bug in the first place.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/accounting/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `accounting.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `trial-balance-difference` models only whether the fundamental
  accounting equation balances, not a full materiality assessment --
  see `cloud-itonami-isic-6920`'s own ADR-0001 and README coverage
  table for the full honest-scope accounting.
- Fleet-wide: 17 actors now `:implemented` out of 643 total registry
  entries; 80 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to ADR-2607071250 or ADR-2607071320 | ❌ | Both of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`; mixing a different ISIC vertical (6920) into either would blur scope boundaries the same way an addendum to ADR-2607032000 would have |
| Keep `cloud-itonami-isic-6920` at `:blueprint` only | ❌ | The standing direction continues past `6612`/`6492`; professional services is a natural, well-precedented next domain |
| See `cloud-itonami-isic-6920`'s own ADR-0001 Alternatives table for build-level decisions | -- | (single-op-dispatch vs. two ops, full materiality model vs. simplified trial-balance check, capability-lib reference, etc.) |

## References

- ADR-2607071250 (`cloud-itonami-isic-6612`, first post-batch vertical)
- ADR-2607071320 (`cloud-itonami-isic-6492`, second post-batch vertical)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-6920/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
