# ADR-2607080400: `cloud-itonami-isic-8521` (general secondary education) deepened to `:implemented` -- twenty-fifth vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610`/`9311`/`8510`/`9412`/`6491`/`8720`, the first twenty-four
  verticals built outside ADR-2607032000's original insurance/real-
  estate batch); ADR-2607032000 (the original batch, fully closed);
  `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/
  `6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/
  `8890`/`8610`/`9311`/`8510`/`9412`/`6491`/`8720` ADR-0001s (the
  governed-actor pattern this decision continues, including `8510`'s,
  whose governor+advisor names this actor reuses); langgraph-clj
  ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8720`, this ADR records the TWENTY-FIFTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-8521` (general secondary education) -- a SECOND education
  vertical alongside `8510`'s pre-primary/primary school, but for
  adolescent secondary education (grading and graduation rather than
  promotion and safeguarding-record finalization).

## Problem

A secondary school's grading-finalization/graduation-finalization
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction secondary-education licensing correctness** -- an
   official spec-basis citation from a real regulator (文部科学省/
   state Departments of Education under ESSA/Ofqual/state examination
   regulations), never fabricated.
2. **Attendance sufficiency** -- the SECOND non-temporal instance of
   this fleet's MINIMUM-threshold sufficiency family (`association.
   registry/continuing-education-hours-insufficient?` established the
   first).
3. **Academic-integrity resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TWENTY-THIRD
   distinct grounding overall, and a FIRST specifically for an
   academic-integrity-flag concept.
4. **Graduation-requirement completeness** -- the THIRD instance of
   this fleet's set-containment/subset family (`registrar` established
   the first, `casework` the second).
5. **Real, high-stakes actuation, twice** -- finalizing a real grading
   and finalizing a real graduation are two independently-gated real-
   world acts on the SAME entity.

Notably, `cloud-itonami-isic-8521`'s own published blueprint names its
governor "Curriculum Safeguarding Governor" and its advisor
"SchoolOps-LLM" -- the IDENTICAL names `cloud-itonami-isic-8510` uses.
This is the FIRST time two distinct verticals in this fleet share an
identical governor+advisor name pair.

See `cloud-itonami-isic-8521`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks and the naming-collision
rationale (this superproject ADR records the fleet-level context and
registry/maturity bookkeeping; the child repo's own ADR is the
authoritative architecture record).

## Decision

1. `cloud-itonami-isic-8521` gains **SchoolOps-LLM ⊣ Curriculum
   Safeguarding Governor** (the SAME governor+advisor names `8510`
   uses, preserved per the blueprint's own published template rather
   than silently renamed) -- `secondary.*` namespaces, modeled closely
   on all thirty-two prior actors' Store/Registry/Governor/Phase/
   Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. `attendance-hours-insufficient?` is the SECOND non-temporal
   instance of the MINIMUM-threshold sufficiency family, comparing a
   student's own accumulated attendance hours against a jurisdiction's
   own recorded minimum.
3. `graduation-requirements-unsatisfied?` is the THIRD instance of the
   set-containment/subset family, reusing the identical `clojure.set/
   subset?` shape for a student's own completed-credits set against
   the school's own required-credits set.
4. `academic-integrity-flag-unresolved-violations` reuses the
   unconditional-evaluation discipline (`casualty.governor/
   sanctions-violations`'s original fix) for a TWENTY-THIRD distinct
   grounding overall, and a FIRST specifically for an academic-
   integrity-flag concept -- deliberately scoped to gate only
   `:grading/finalize`, not `:graduation/finalize`, the same domain-
   scoping discipline `leasing`'s and `behavioral`'s ADR-0001s already
   established for their own analogous decisions.
5. Tested via the SCREENING op (`:academic-integrity/screen`) directly
   from the start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral` lesson
   PROACTIVELY for a thirteenth consecutive vertical.
6. Dual actuation (`:actuation/finalize-grading`, `:actuation/
   finalize-graduation`), matching `6512`'s/`6622`'s/`6520`'s/
   `6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/
   `8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s/`8510`'s/`9412`'s/
   `8720`'s dual-actuation shape, each with its own history
   collection, sequence counter and dedicated double-actuation
   boolean guard (never a `:status` value, per `6492`'s ADR-0001
   lesson).
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"8521"`, fleet-wide maturity counts move from 38
   implemented / 59 blueprint / 546 spec to 39 implemented / 58
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/secondary/*` -- 40 tests / 185 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → assess → academic-integrity screen → grading
   finalize → graduation finalize) plus five HARD-hold cases (no
   spec-basis, insufficient attendance hours, an unresolved academic-
   integrity flag screened directly and never reaching a human,
   unsatisfied graduation requirements, and a double grading/
   graduation finalization) that never reach a human at all.

## Consequences

- (+) Secondary education gets the same governed, auditable-actor
  treatment as the thirty-two prior actors, and this fleet now has a
  TWENTY-FIFTH concrete precedent for extending past ADR-2607032000's
  original scope, deepening education coverage alongside `8510`'s
  pre-primary/primary school.
- (+) `attendance-hours-insufficient?` and `graduation-requirements-
  unsatisfied?` are both genuine structural contributions, further
  validating the MINIMUM-threshold sufficiency and set-containment/
  subset families' generality across distinct domains.
- (+) `academic-integrity-flag-unresolved-violations` is a genuine
  domain-modeling contribution: the first unconditional-evaluation
  grounding for an academic-integrity-flag concept.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/secondary/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The academic-integrity-flag test/demo correctly applied the
  established SCREENING-op-directly pattern for a thirteenth
  consecutive vertical -- further evidence that lessons recorded in
  this fleet's ADRs continue to transfer forward reliably.
- (+) The shared governor+advisor naming with `8510` was handled
  transparently: preserved per the blueprint's own published template
  rather than silently renamed, with the rationale explicitly recorded
  in this actor's own ADR-0001 Decision 1.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `secondary.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `attendance-hours-insufficient?`/`graduation-requirements-
  unsatisfied?` model only a single representative attendance/credit-
  set concern, not a full curriculum-design/pedagogical-assessment
  engine -- see `cloud-itonami-isic-8521`'s own ADR-0001 and README
  coverage table for the full honest-scope accounting.
- Fleet-wide: 39 actors now `:implemented` out of 643 total registry
  entries; 58 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to `cloud-itonami-isic-8510`'s ADR | ❌ | `8510`'s ADR-0001's title and scope are explicitly pre-primary/primary education; `8521` is a distinct ISIC class with a distinct actuation shape, even though the blueprint shares the SAME governor/advisor names |
| Keep `cloud-itonami-isic-8521` at `:blueprint` only | ❌ | The standing direction continues past `8720`; secondary education is a natural, well-precedented next domain, deepening this fleet's education coverage alongside `8510`'s pre-primary/primary school |
| See `cloud-itonami-isic-8521`'s own ADR-0001 Alternatives table for build-level decisions | -- | (governor/advisor naming-collision handling, check-family framing, screening-op scoping rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610`/`9311`/`8510`/`9412`/`6491`/`8720`, first twenty-four post-
  batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8521/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
