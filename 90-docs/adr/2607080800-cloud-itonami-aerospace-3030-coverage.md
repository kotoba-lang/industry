# ADR-2607080800: `cloud-itonami-isic-3030` (aircraft and aerospace manufacturing enablement) deepened to `:implemented` -- twenty-ninth vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/`3600`/
  `6190`, the first twenty-eight verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/
  `9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/
  `9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/`3600`/`6190`
  ADR-0001s (the governed-actor pattern this decision continues);
  langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `6190`, this ADR records the TWENTY-NINTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-3030` (aircraft and aerospace manufacturing enablement) -- the
  FIRST manufacturing vertical built in this fleet.

## Problem

An aerospace manufacturer's assembly-dispatch/airworthiness-evidence
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction airworthiness-certification correctness** -- an
   official spec-basis citation from a real certification authority
   (国土交通省航空局/the FAA under 14 CFR Part 25/the UK CAA/the LBA-
   EASA under CS-25), never fabricated.
2. **Dimensional-tolerance sufficiency** -- the FOURTH instance of
   this fleet's two-sided range check family (`testlab`/
   `conservation`/`water` established the first three).
3. **NDT-defect resolution verification** -- reuses the unconditional-
   evaluation screening discipline for a TWENTY-SEVENTH distinct
   grounding overall, and a FIRST specifically for an NDT-defect
   concept.
4. **Real, high-stakes actuation, twice, BOTH positive** -- dispatching
   a real robot assembly action on a flight-critical structure and
   issuing real airworthiness evidence are two independently-gated
   real-world acts on the SAME entity, and unlike `3600`/`6190`, both
   are positive (issuing/finalizing a record) -- matching this
   fleet's majority actuation shape.

See `cloud-itonami-isic-3030`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks and the manufacturing-domain
framing (this superproject ADR records the fleet-level context and
registry/maturity bookkeeping; the child repo's own ADR is the
authoritative architecture record).

## Decision

1. `cloud-itonami-isic-3030` gains **Aerospace Advisor ⊣ Aerospace
   Manufacturing Governor** -- `aerospace.*` namespaces, modeled
   closely on all forty-two prior actors' Store/Registry/Governor/
   Phase/Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. This is this fleet's FIRST manufacturing-sector vertical --
   deepening past `3600`'s/`6190`'s infrastructure/utility coverage
   into a genuinely new sector, and the domain where the fleet-wide
   "a robot performs the physical domain work" premise is most
   literally true: the robot is the actual fabrication mechanism.
3. `assembly-tolerance-out-of-range?` is the FOURTH instance of the
   two-sided range check family, reusing the identical lo/hi-bounds-
   comparison shape for an assembly's own measured dimensional
   tolerance against its own recorded spec bounds, gating only
   `:actuation/dispatch-assembly` per this blueprint's own published
   Trust Control ("out-of-spec assembly is blocked").
4. `ndt-defect-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a TWENTY-SEVENTH distinct grounding overall, and a
   FIRST specifically for an NDT-defect concept.
5. Tested via the SCREENING op (`:ndt/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral`/
   `secondary`/`card`/`water`/`telecom` lesson PROACTIVELY for a
   seventeenth consecutive vertical.
6. Dual actuation (`:actuation/dispatch-assembly`, `:actuation/issue-
   airworthiness-evidence`), matching `6512`'s/`6622`'s/`6520`'s/
   `6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/
   `8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s/`8510`'s/`9412`'s/
   `8720`'s/`8521`'s/`6619`'s/`3600`'s/`6190`'s dual-actuation shape,
   each with its own history collection, sequence counter and
   dedicated double-actuation boolean guard (never a `:status` value,
   per `6492`'s ADR-0001 lesson). BOTH actuations here are POSITIVE,
   matching this fleet's majority shape (`3600`/`6190` remain the
   only negative-actuation exceptions).
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"3030"`, fleet-wide maturity counts move from 42
   implemented / 55 blueprint / 546 spec to 43 implemented / 54
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/aerospace/*` -- 36 tests / 176 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → verify → NDT screen → dispatch assembly →
   issue airworthiness evidence) plus five HARD-hold cases (no spec-
   basis, an out-of-spec assembly tolerance, an unresolved NDT defect
   screened directly and never reaching a human, and a double
   assembly-dispatch/airworthiness-evidence-issuance) that never reach
   a human at all.
9. The child repo's own `blueprint.edn`, which carried a stale pre-
   rename `:itonami.blueprint/id` and was missing `:optional-
   technologies` entirely, was corrected to match the registry.

## Consequences

- (+) Aerospace-manufacturing operation gets the same governed,
  auditable-actor treatment as the forty-two prior actors, and this
  fleet now has a TWENTY-NINTH concrete precedent for extending past
  ADR-2607032000's original scope, and its FIRST manufacturing-sector
  coverage.
- (+) `assembly-tolerance-out-of-range?` is a genuine structural
  contribution: the fourth instance of the two-sided range check
  family, and the first to map onto a manufacturing-QA concept
  (dimensional tolerance) rather than a service/facility-condition
  concept.
- (+) `ndt-defect-unresolved-violations` is a genuine domain-modeling
  contribution: the first unconditional-evaluation grounding for an
  NDT-defect concept.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/aerospace/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The NDT-defect test/demo correctly applied the established
  SCREENING-op-directly pattern for a seventeenth consecutive vertical
  -- further evidence that lessons recorded in this fleet's ADRs
  continue to transfer forward reliably.
- (+) Two small pre-existing inconsistencies in the blueprint scaffold
  (stale ID field, missing optional-technologies field) were corrected
  as part of this promotion.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `aerospace.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) This actor does not model a real fab/assembly-line control
  system, real robot motion-planning/force-control, or a full finite-
  element CAE/CFD simulation engine -- see `cloud-itonami-isic-3030`'s
  own ADR-0001 and README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 43 actors now `:implemented` out of 643 total registry
  entries; 54 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-3030` at `:blueprint` only | ❌ | The standing direction continues past `6190`; aerospace manufacturing is a natural next domain, opening this fleet's first manufacturing-sector coverage |
| Model `:actuation/dispatch-assembly` as repeatable per-fastener/per-operation | ❌ | Would require an entirely different entity/history shape not shared by any sibling; a single "complete this assembly unit's action" act matches the established dual-actuation-on-one-entity shape instead |
| See `cloud-itonami-isic-3030`'s own ADR-0001 Alternatives table for build-level decisions | -- | (actuation-granularity rationale, check-scope rationale, blueprint-field-fix rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/`3600`/
  `6190`, first twenty-eight post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-3030/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
