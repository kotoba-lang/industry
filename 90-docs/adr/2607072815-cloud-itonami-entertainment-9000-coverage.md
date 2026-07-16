# ADR-2607072815: `cloud-itonami-isic-9000` (creative, arts and entertainment activities) deepened to `:implemented` -- seventeenth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`, the first sixteen
  verticals built outside ADR-2607032000's original insurance/real-
  estate batch); ADR-2607032000 (the original batch, fully closed);
  `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/
  `6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602` ADR-0001s
  (the governed-actor pattern this decision continues); langgraph-clj
  ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9602`, this ADR records the SEVENTEENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9000` (creative, arts and entertainment activities, ISIC
  division 90) -- the FIRST creative/arts/entertainment vertical in
  this fleet.

## Problem

A theatre/production company's production-release workflow bundles
several distinct concerns under one governed workflow:

1. **Jurisdiction copyright/rights-clearance correctness** -- an
   official spec-basis citation from a real regulator, never
   fabricated.
2. **Release-channel restriction conflict** -- the THIRD instance of
   the set-membership/conflict family (`clinic.registry/treatment-
   contraindicated?` established the first, `veterinary.registry`
   reused it verbatim for the second), applied here to a fresh
   concern: a contractual release-channel restriction rather than a
   clinical-safety conflict.
3. **Rights-clearance resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a FOURTEENTH
   distinct grounding.
4. **Real, high-stakes actuation, once** -- releasing or publishing a
   real production to the public is a single actuation event with
   direct reputational/legal stakes.

See `cloud-itonami-isic-9000`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9000` gains **EntertainmentOps-LLM ⊣ Content
   and Booking Governor** -- `entertainment.*` namespaces, modeled
   closely on all twenty-four prior actors' Store/Registry/Governor/
   Phase/Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. `release-channel-restricted?` is the THIRD instance of the set-
   membership/conflict family, extending it from clinical-safety
   concerns (`clinic`/`veterinary`) to a contractual release-channel
   restriction: a production's own proposed release channel must not
   appear in its own recorded restricted-channel set (e.g. an
   exclusivity-window clause).
3. `rights-clearance-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a FOURTEENTH distinct grounding in this fleet.
4. Tested via the SCREENING op (`:rights/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon` lesson PROACTIVELY for a fifth consecutive
   vertical.
5. Single actuation (`:actuation/release-production`), matching
   `6511`'s/`6621`'s/`6629`'s/`6612`'s/`6492`'s/`7120`'s/`8620`'s/
   `7500`'s/`9603`'s/`9321`'s/`9602`'s single-actuation shape,
   matching this domain's actual blueprint-stated scope (one real-
   world release act, not several independently-gated acts).
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9000"`, fleet-wide maturity counts move from 30
   implemented / 67 blueprint / 546 spec to 31 implemented / 66
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/entertainment/*` -- 30 tests / 127 assertions, lint-clean,
   demo (`clojure -M:dev:run`) runs end-to-end: one clean lifecycle
   (production release) plus four HARD-hold cases (no spec-basis, a
   restricted release channel, an unresolved rights-clearance flag
   screened directly and never reaching a human, and a double release)
   that never reach a human at all.

## Consequences

- (+) Creative/arts/entertainment gets the same governed, auditable-
  actor treatment as the twenty-four prior actors, and this fleet now
  has a SEVENTEENTH concrete precedent for extending past
  ADR-2607032000's original scope, into a genuinely different economic
  sector (creative/arts/entertainment, ISIC division 90) for the first
  time.
- (+) `release-channel-restricted?` is a genuine structural
  contribution: the third instance of the set-membership/conflict
  family, extending it beyond clinical-safety concerns for the first
  time.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  entertainment/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern every sibling actor uses.
- (+) The rights-clearance-unresolved test/demo correctly applied the
  established SCREENING-op-directly pattern for a fifth consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `entertainment.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `release-channel-restricted?` models only a single restricted-
  channel-membership check, not a full rights-management/clearance
  database -- see `cloud-itonami-isic-9000`'s own ADR-0001 and README
  coverage table for the full honest-scope accounting.
- Fleet-wide: 31 actors now `:implemented` out of 643 total registry
  entries; 66 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All sixteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`; mixing a different ISIC division (90, distinct from all of those sixteen's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9000` at `:blueprint` only | ❌ | The standing direction continues past `9602`; creative/arts/entertainment is a natural, well-precedented next domain, further diversifying this fleet into a sector not yet touched |
| See `cloud-itonami-isic-9000`'s own ADR-0001 Alternatives table for build-level decisions | -- | (set-membership vs. new-family framing for the distinctive check, unconditional-evaluation test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`, first sixteen post-
  batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9000/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
