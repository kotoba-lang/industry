# ADR-2607072845: `cloud-itonami-isic-8610` (hospital activities) deepened to `:implemented` -- nineteenth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`, the first eighteen verticals built
  outside ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/
  `9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890` ADR-0001s
  (the governed-actor pattern this decision continues); langgraph-clj
  ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8890`, this ADR records the NINETEENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-8610` (hospital activities, ISIC division 86) -- a SECOND
  human-health vertical alongside `8620`'s clinic, but for inpatient
  hospital care rather than outpatient practice.

## Problem

A hospital's treatment-administration/discharge-authorization
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction hospital-institution licensing correctness** -- an
   official spec-basis citation from a real INSTITUTIONAL regulator,
   distinct from `clinic.facts`'s individual-practitioner-licensing
   bodies, never fabricated.
2. **Post-procedure observation sufficiency** -- the THIRD instance of
   this fleet's MINIMUM-threshold temporal-sufficiency shape
   (`veterinary.registry/withdrawal-period-insufficient?` established
   the first, gated on a type tag; `funeral.registry/waiting-period-
   elapsed?` the second, unconditional), applied here unconditionally.
3. **Credential resolution verification** -- reuses the unconditional-
   evaluation screening discipline for a SIXTEENTH distinct grounding
   overall, and the THIRD specifically for the credential-not-current
   clinical-licensing concept (after `clinic` and `veterinary`).
4. **Real, high-stakes actuation, twice** -- administering a real
   treatment and authorizing a real discharge are two independently-
   gated real-world acts on the SAME entity.

See `cloud-itonami-isic-8610`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-8610` gains **HospitalOps-LLM ⊣ Clinical
   Oversight Governor** -- `hospital.*` namespaces, modeled closely on
   all twenty-six prior actors' Store/Registry/Governor/Phase/
   Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. `observation-period-elapsed?` is the THIRD instance of the MINIMUM-
   threshold temporal-sufficiency family, applied unconditionally
   (every discharge needs the same minimum post-procedure observation
   window, matching `funeral`'s simpler application rather than
   `veterinary`'s type-tag-gated one).
3. `hospital.facts` cites INSTITUTIONAL hospital-operation regulators
   (Medical Care Act/CMS Conditions of Participation/CQC/G-BA), an
   honest, domain-accurate differentiation from `clinic.facts`'s
   individual-practitioner-licensing bodies, even where jurisdictions
   overlap.
4. `credential-not-current-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a SIXTEENTH distinct grounding overall, and a
   THIRD specifically for the credential-not-current concept (`clinic`
   established it, `veterinary` reused it verbatim, `hospital` is the
   third reuse).
5. Tested via the SCREENING op (`:credential/screen`) directly from
   the start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework` lesson
   PROACTIVELY for a seventh consecutive vertical.
6. Dual actuation (`:actuation/administer-treatment`, `:actuation/
   authorize-discharge`), matching `6512`'s/`6622`'s/`6520`'s/`6530`'s/
   `6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/`8730`'s/
   `9102`'s/`9103`'s/`8890`'s dual-actuation shape, each with its own
   history collection, sequence counter and dedicated double-actuation
   boolean guard (never a `:status` value, per `6492`'s ADR-0001
   lesson).
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"8610"`, fleet-wide maturity counts move from 32
   implemented / 65 blueprint / 546 spec to 33 implemented / 64
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/hospital/*` -- 36 tests / 172 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles
   (treatment administration, discharge authorization) plus five HARD-
   hold cases (no spec-basis, an insufficient post-procedure
   observation period, a not-current clinician license screened
   directly and never reaching a human, and a double administration/
   discharge of each actuation op) that never reach a human at all.

## Consequences

- (+) Inpatient hospital care gets the same governed, auditable-actor
  treatment as the twenty-six prior actors, and this fleet now has a
  NINETEENTH concrete precedent for extending past ADR-2607032000's
  original scope, deepening human-health coverage (ISIC division 86)
  alongside `8620`'s clinic with a genuinely different care model.
- (+) `observation-period-elapsed?` is a genuine structural
  contribution: the third instance of the MINIMUM-threshold temporal-
  sufficiency family, applied unconditionally.
- (+) `hospital.facts`'s institutional-vs-practitioner regulator
  distinction is a genuine domain-modeling contribution: the first
  time this fleet has explicitly differentiated two licensing concerns
  within the same broad sector and jurisdictions.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/hospital/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The credential-not-current test/demo correctly applied the
  established SCREENING-op-directly pattern for a seventh consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `hospital.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `observation-period-elapsed?` models only a single representative
  minimum post-procedure observation window, not a full clinical-
  decision-support/discharge-planning engine -- see `cloud-itonami-
  isic-8610`'s own ADR-0001 and README coverage table for the full
  honest-scope accounting.
- Fleet-wide: 33 actors now `:implemented` out of 643 total registry
  entries; 64 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All eighteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`; mixing a different sub-domain into any would blur scope boundaries even where the ISIC division (86) overlaps with `8620` |
| Keep `cloud-itonami-isic-8610` at `:blueprint` only | ❌ | The standing direction continues past `8890`; inpatient hospital care is a natural, well-precedented next domain, deepening this fleet's human-health coverage with a genuinely different care model than `8620`'s outpatient clinic |
| See `cloud-itonami-isic-8610`'s own ADR-0001 Alternatives table for build-level decisions | -- | (institutional vs. practitioner regulator citation, unconditional-evaluation test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`, first eighteen post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8610/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
