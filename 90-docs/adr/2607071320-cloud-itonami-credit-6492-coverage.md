# ADR-2607071320: `cloud-itonami-isic-6492` (other credit granting) deepened to `:implemented` -- first lending vertical

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250 (`cloud-itonami-isic-6612`, the FIRST vertical
  built outside ADR-2607032000's original insurance/real-estate
  batch); ADR-2607032000 (the original batch, fully closed); `cloud-
  itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/
  `6612` ADR-0001s (the governed-actor pattern this decision
  continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `6612`, this ADR records the SECOND
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-6492` (other credit granting, ISIC division 64), the first
  LENDING vertical in this fleet.

## Problem

`cloud-itonami-isic-6492` published a business/operator-model
blueprint (Credit-LLM ⊣ Credit Governor, `:blueprint` maturity) but had
no governed actor implementation. Deepening it required:

1. **Jurisdiction truth-in-lending disclosure correctness** -- an
   official spec-basis citation, never fabricated.
2. **Affordability correctness** -- an applicant's own debt-to-income
   ratio, recomputed straight from permanent ground-truth fields
   already on the application (no separate claimed figure to compare
   against, unlike every other arithmetic check in this fleet).
3. **Underwriting-approval-lifecycle correctness** -- a genuinely new
   status-lifecycle shape where the checked status legitimately
   advances PAST the value being checked after actuation (see this
   repo's own ADR-0001 Decision 5 for a real bug this caused, found and
   fixed).
4. **A single real actuation event** -- disbursing loan funds, no
   second actuation event manufactured for symmetry.

See `cloud-itonami-isic-6492`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks, and the bug found and fixed
in this build (this superproject ADR records the fleet-level context
and registry/maturity bookkeeping; the child repo's own ADR is the
authoritative architecture record).

## Decision

1. `cloud-itonami-isic-6492` gains **Credit-LLM ⊣ Credit Governor** --
   `credit.*` namespaces, modeled closely on all nine prior actors'
   Store/Registry/Governor/Phase/Advisor/Operation/Sim shape and the
   SAME generic langgraph-clj StateGraph.
2. `affordability-exceeded-violations` is a pure ground-truth recompute
   (debt-to-income ratio, from the application's own permanent fields)
   needing NO proposal inspection or stored-verdict lookup at all --
   simpler than every prior unconditional-evaluation check in this
   fleet, since its inputs are permanent facts rather than a claim to
   verify or an external finding to screen for.
3. No dynamically-filed sub-record: disbursement acts directly on a
   pre-seeded application, mirroring `casualty`'s/`reinsurance`'s
   simpler `:*/bind` shape rather than `pension`'s/`realty`'s/
   `brokerage`'s sub-record-plus-missing-check shape.
4. A REAL bug was found and fixed during demo verification:
   `application-not-approved-violations` initially checked `:status
   :approved` directly (by pattern-matching on FOUR prior ADRs'
   "status never regresses, check it directly" safe reuses), but
   `:loan/disburse`'s own commit advances status from `:approved` to
   `:disbursed` -- the SAME status-lifecycle trap `cloud-itonami-isic-
   6622`'s original bug had. Fixed via a status-value SET (`#{:approved
   :disbursed}`) rather than a single terminal value. The lesson is
   restated more precisely in the child repo's ADR: verify what the
   actuation op's own commit does to status, don't assume by analogy
   to prior safe cases.
5. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"6492"`, fleet-wide maturity counts move from 15
   implemented / 82 blueprint / 546 spec to 16 implemented / 81
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
6. `test/credit/*` -- 31 tests / 128 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean intake-through-
   disbursement lifecycle plus four HARD-hold cases (no spec-basis, an
   affordability-ceiling breach on screening, a disbursement attempt
   against an unapproved application, a double disbursement) that
   never reach a human at all -- all correct after the status-
   lifecycle fix above.

## Consequences

- (+) Consumer/commercial credit granting gets the same governed,
  auditable-actor treatment as the nine prior actors, and this fleet
  now has TWO concrete precedents (`6612`, `6492`) for extending past
  ADR-2607032000's original scope under the SAME standing
  authorization.
- (+) `affordability-exceeded-violations` demonstrates that the
  unconditional-evaluation discipline generalizes even further: when a
  threshold check's inputs are permanent entity fields, no proposal
  inspection or stored-verdict lookup is needed at all.
- (+) The status-lifecycle bug is a sobering, honestly-recorded data
  point: having documented "check presence of a fact, not the current
  status value" in FOUR prior ADRs did not prevent re-deriving the
  wrong conclusion here by analogy to the SAFE cases' shape rather than
  verifying THIS domain's actual lifecycle. The lesson is restated more
  precisely for future reuse (see the child repo's own ADR-0001
  Decision 5).
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/credit/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `credit.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `compute-debt-to-income-ratio` models only a single debt-to-
  income ratio check, not a full underwriting model (credit-score
  weighting, collateral analysis, payment history are out of scope --
  see `cloud-itonami-isic-6492`'s own ADR-0001 and README coverage
  table for the full honest-scope accounting).
- Fleet-wide: 16 actors now `:implemented` out of 643 total registry
  entries; 81 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to ADR-2607071250 (`6612`'s ADR) | ❌ | That ADR's title and scope are explicitly `cloud-itonami-isic-6612`; mixing a different ISIC vertical (6492) into it would blur scope boundaries the same way an addendum to ADR-2607032000 would have |
| Keep `cloud-itonami-isic-6492` at `:blueprint` only | ❌ | The standing direction continues past `6612`; lending is a natural, well-precedented next vertical |
| See `cloud-itonami-isic-6492`'s own ADR-0001 Alternatives table for build-level decisions | -- | (status-value-set fix vs. new field, full underwriting model vs. simplified DTI check, etc.) |

## References

- ADR-2607071250 (`cloud-itonami-isic-6612`, first post-batch vertical)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-6492/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
