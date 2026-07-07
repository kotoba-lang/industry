# ADR-2607081300: `cloud-itonami-isic-2610` (semiconductor and electronics manufacturing enablement) deepened to `:implemented` -- thirty-fourth vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800/ADR-2607080900/ADR-2607081000/ADR-2607081100/
  ADR-2607081200 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/
  `6619`/`3600`/`6190`/`3030`/`3830`/`7020`/`9420`/`9491`, the first
  thirty-three verticals built outside ADR-2607032000's original
  insurance/real-estate batch); ADR-2607032000 (the original batch,
  fully closed); `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/
  `6629`/`6520`/`6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/
  `8720`/`8521`/`6619`/`3600`/`6190`/`3030`/`3830`/`7020`/`9420`/
  `9491` ADR-0001s (the governed-actor pattern this decision
  continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9491`, this ADR records the THIRTY-FOURTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-2610` (semiconductor and electronics manufacturing enablement)
  -- the SECOND manufacturing vertical built in this fleet, after
  `3030`'s aerospace assembly.

## Problem

A semiconductor fab operator's process-dispatch/yield-audit workflow
bundles several distinct concerns under one governed workflow:

1. **Jurisdiction process-safety correctness** -- an official spec-
   basis citation from a real chemical/gas-handling safety regulator
   (経済産業省/OSHA/the HSE/the BAuA), supplemented by SEMI
   international process standards, never fabricated.
2. **Yield-rate sufficiency** -- the FOURTH instance of this fleet's
   ratio-based check family (`leasing`=1st, `behavioral`=2nd,
   `union`=3rd), a direct, domain-canonical mapping onto real
   semiconductor-fab yield-threshold certification practice.
3. **Process-defect resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a THIRTY-SECOND
   distinct grounding overall.
4. **Real, high-stakes actuation, twice, BOTH positive** -- dispatching
   a real robot process-step action in the cleanroom and finalizing a
   real yield-audit record are two independently-gated real-world
   acts on the SAME entity, both positive (issuing/finalizing a
   record) -- matching this fleet's majority actuation shape.

See `cloud-itonami-isic-2610`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks and the domain-distinction
framing from `3030` (this superproject ADR records the fleet-level
context and registry/maturity bookkeeping; the child repo's own ADR
is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-2610` gains **Fab Advisor ⊣ Fab Operations
   Governor** -- `fab.*` namespaces, modeled closely on all forty-
   seven prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. This is this fleet's SECOND manufacturing vertical -- a
   deliberately DISTINCT domain concern from `3030`'s aerospace
   assembly (wafer-lot yield sufficiency and cleanroom process-defect
   containment, rather than airframe-assembly dimensional tolerance
   and NDT-defect detection), justifying genuinely different check
   families rather than reusing `3030`'s two-sided range check.
3. `yield-rate-insufficient?` is the FOURTH instance of the ratio-
   based check family, reusing the identical quotient-comparison
   shape (MINIMUM-floor direction, like `leasing`'s and `union`'s) for
   a lot's own good-dies count divided by its own total-dies count
   against its own required-yield-share threshold, gating only
   `:actuation/finalize-yield-audit`.
4. `process-defect-flag-unresolved-violations` reuses the
   unconditional-evaluation discipline (`casualty.governor/sanctions-
   violations`'s original fix) for a THIRTY-SECOND distinct grounding
   overall, gating `:defect/screen` and `:actuation/dispatch-process-
   step` specifically -- matching this blueprint's own published
   Trust Controls ("process steps outside spec are blocked", "a robot
   action the governor refuses is never dispatched to hardware").
5. Tested via the SCREENING op (`:defect/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral`/
   `secondary`/`card`/`water`/`telecom`/`aerospace`/`recovery`/
   `consulting`/`union`/`congregation` lesson PROACTIVELY for a
   twenty-second consecutive vertical.
6. Dual actuation (`:actuation/dispatch-process-step`, `:actuation/
   finalize-yield-audit`), matching `6512`'s/`6622`'s/`6520`'s/
   `6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/
   `8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s/`8510`'s/`9412`'s/
   `8720`'s/`8521`'s/`6619`'s/`3600`'s/`6190`'s/`3030`'s/`3830`'s/
   `9420`'s/`9491`'s dual-actuation shape, each with its own history
   collection, sequence counter and dedicated double-actuation
   boolean guard (never a `:status` value, per `6492`'s ADR-0001
   lesson). BOTH actuations here are POSITIVE, matching this fleet's
   majority shape (`3600`/`6190` remain the only negative-actuation
   exceptions).
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"2610"`, fleet-wide maturity counts move from 47
   implemented / 50 blueprint / 546 spec to 48 implemented / 49
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/fab/*` -- 36 tests / 177 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → verify → defect screen → dispatch process step
   → finalize yield audit) plus five HARD-hold cases (no spec-basis,
   an insufficient yield rate, an unresolved process-defect flag
   screened directly and never reaching a human, and a double
   dispatch/finalization) that never reach a human at all.
9. The child repo's own `blueprint.edn`, which carried a stale pre-
   rename `:itonami.blueprint/id` and was missing `:optional-
   technologies` entirely, was corrected to match the registry.

## Consequences

- (+) Semiconductor-fab operation gets the same governed, auditable-
  actor treatment as the forty-seven prior actors, and this fleet now
  has a THIRTY-FOURTH concrete precedent for extending past
  ADR-2607032000's original scope, and its SECOND manufacturing-
  sector coverage, with a genuinely distinct set of domain-specific
  checks from the first.
- (+) `yield-rate-insufficient?` is a genuine structural contribution:
  the fourth instance of the ratio-based check family, and a directly
  domain-canonical mapping onto real semiconductor-fab practice.
- (+) `process-defect-flag-unresolved-violations` is a genuine
  domain-modeling contribution: the 32nd unconditional-evaluation
  grounding, applied to fab-cleanroom process-defect containment.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/fab/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The process-defect test/demo correctly applied the established
  SCREENING-op-directly pattern for a twenty-second consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (+) Two small pre-existing inconsistencies in the blueprint scaffold
  (stale ID field, missing optional-technologies field) were corrected
  as part of this promotion.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `fab.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) This actor does not model a real fab/MES control system, real
  robot motion-planning/process-tool control, or a full EDA/CAE
  simulation engine -- see `cloud-itonami-isic-2610`'s own ADR-0001
  and README coverage table for the full honest-scope accounting.
- Fleet-wide: 48 actors now `:implemented` out of 643 total registry
  entries; 49 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to `3030`'s ADR (both are manufacturing) | ❌ | The two domains (aerospace assembly vs. semiconductor fab operation) have genuinely distinct concerns, warranting separate check-family choices and their own record, per this fleet's standing convention |
| Keep `cloud-itonami-isic-2610` at `:blueprint` only | ❌ | The standing direction continues past `9491`; semiconductor-fab enablement is a natural next domain, deepening this fleet's manufacturing-sector coverage to two verticals |
| Reuse `aerospace`'s two-sided range check for a process-parameter concern | ❌ | Yield-rate (a ratio) is the more domain-canonical semiconductor-fab quality gate; see child repo's own ADR-0001 Alternatives table |
| See `cloud-itonami-isic-2610`'s own ADR-0001 Alternatives table for build-level decisions | -- | (check-family-choice rationale, actuation-gating-scope rationale, blueprint-field-fix rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800/ADR-2607080900/ADR-2607081000/ADR-2607081100/
  ADR-2607081200 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/
  `6619`/`3600`/`6190`/`3030`/`3830`/`7020`/`9420`/`9491`, first
  thirty-three post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-2610/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
