# ADR-2607080300: `cloud-itonami-isic-8720` (residential behavioral-health care) deepened to `:implemented` -- twenty-fourth vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/
  `9412`/`6491`, the first twenty-three verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/
  `9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/
  `9311`/`8510`/`9412`/`6491` ADR-0001s (the governed-actor pattern
  this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `6491`, this ADR records the TWENTY-FOURTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-8720` (residential care activities for mental retardation,
  mental health and substance abuse) -- a SECOND human-health vertical
  alongside `8610`'s hospital and `8620`'s clinic, but for residential
  behavioral-health care rather than acute medical care.

## Problem

A behavioral-health facility's treatment-plan-finalization/crisis-
response-finalization workflow bundles several distinct concerns
under one governed workflow:

1. **Jurisdiction behavioral-health-facility licensing correctness** --
   an official spec-basis citation from a real regulator (厚生労働省
   under the Mental Health Act/CMS's psychiatric-hospital special
   Conditions of Participation/the CQC/state PsychKG Acts), never
   fabricated.
2. **Supervision sufficiency** -- the SECOND ratio-based instance in
   this fleet's check-family taxonomy (`leasing.registry/collateral-
   coverage-ratio-insufficient?` established the first, for a MINIMUM
   required ratio; this validates the MAXIMUM direction).
3. **Medication-adherence resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TWENTY-SECOND
   distinct grounding overall, and a FIRST specifically for a
   medication-adherence-flag concept.
4. **Real, high-stakes actuation, twice** -- finalizing a real
   treatment plan and finalizing a real crisis response are two
   independently-gated real-world acts on the SAME entity.

See `cloud-itonami-isic-8720`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-8720` gains **BehavioralOps-LLM ⊣ Behavioral
   Care Governor** -- `behavioral.*` namespaces, modeled closely on
   all thirty-one prior actors' Store/Registry/Governor/Phase/
   Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. `supervision-ratio-insufficient?` is the SECOND ratio-based
   instance in the check-family taxonomy, comparing a facility's own
   current resident count divided by its own current staff count
   against a required maximum -- validating the ratio family's
   generality across both the MINIMUM-floor (`leasing`) and MAXIMUM-
   ceiling (this actor) directions.
3. `medication-adherence-flag-unresolved-violations` reuses the
   unconditional-evaluation discipline (`casualty.governor/
   sanctions-violations`'s original fix) for a TWENTY-SECOND distinct
   grounding overall, and a FIRST specifically for a medication-
   adherence-flag concept -- deliberately scoped to gate only
   `:treatment-plan/finalize`, not `:crisis-response/finalize`, since
   a medication-adherence concern is a distinct clinical question from
   the facility's current staffing adequacy for crisis response, the
   same domain-scoping discipline `leasing`'s ADR-0001 already
   established for its own analogous decision.
4. Tested via the SCREENING op (`:medication-adherence/screen`)
   directly from the start, applying the `parksafety`/`eldercare`/
   `museum`/`conservation`/`salon`/`entertainment`/`casework`/
   `hospital`/`facility`/`school`/`association`/`leasing` lesson
   PROACTIVELY for a twelfth consecutive vertical.
5. Dual actuation (`:actuation/finalize-treatment-plan`, `:actuation/
   finalize-crisis-response`), matching `6512`'s/`6622`'s/`6520`'s/
   `6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/
   `8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s/`8510`'s/`9412`'s
   dual-actuation shape, each with its own history collection,
   sequence counter and dedicated double-actuation boolean guard
   (never a `:status` value, per `6492`'s ADR-0001 lesson).
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"8720"`, fleet-wide maturity counts move from 37
   implemented / 60 blueprint / 546 spec to 38 implemented / 59
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/behavioral/*` -- 36 tests / 174 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → assess → medication-adherence screen →
   treatment-plan finalize → crisis-response finalize) plus five
   HARD-hold cases (no spec-basis, an insufficient supervision ratio,
   an unresolved medication-adherence flag screened directly and
   never reaching a human, and a double treatment-plan/crisis-response
   finalization) that never reach a human at all.

## Consequences

- (+) Residential behavioral-health care gets the same governed,
  auditable-actor treatment as the thirty-one prior actors, and this
  fleet now has a TWENTY-FOURTH concrete precedent for extending past
  ADR-2607032000's original scope, deepening human-health coverage
  alongside `8610`'s hospital and `8620`'s clinic with a genuinely
  different care model.
- (+) `supervision-ratio-insufficient?` is a genuine structural
  contribution: the second ratio-based instance in this fleet's
  taxonomy, proving the family's generality across both the MINIMUM
  and MAXIMUM directions.
- (+) `medication-adherence-flag-unresolved-violations` is a genuine
  domain-modeling contribution: the first unconditional-evaluation
  grounding for a medication-adherence-flag concept, deliberately
  scoped to one of two actuations by the same domain-reasoning
  discipline `leasing`'s ADR-0001 established.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  behavioral/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern every sibling actor uses.
- (+) The medication-adherence-flag test/demo correctly applied the
  established SCREENING-op-directly pattern for a twelfth consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `behavioral.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `supervision-ratio-insufficient?` models only a single
  representative maximum supervision ratio, not a full clinical-
  decision-support/case-management engine -- see `cloud-itonami-isic-
  8720`'s own ADR-0001 and README coverage table for the full honest-
  scope accounting.
- Fleet-wide: 38 actors now `:implemented` out of 643 total registry
  entries; 59 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All twenty-three of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`; mixing a different health-services sub-domain into `8610`'s or `8620`'s ADR would blur scope boundaries even where the broad human-health sector overlaps |
| Keep `cloud-itonami-isic-8720` at `:blueprint` only | ❌ | The standing direction continues past `6491`; residential behavioral-health care is a natural, well-precedented next domain, deepening this fleet's human-health coverage alongside `8610`'s hospital and `8620`'s clinic |
| See `cloud-itonami-isic-8720`'s own ADR-0001 Alternatives table for build-level decisions | -- | (ratio-family MAXIMUM-direction framing, medication-adherence-flag scoping rationale, screening-op test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/
  `9412`/`6491`, first twenty-three post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8720/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
