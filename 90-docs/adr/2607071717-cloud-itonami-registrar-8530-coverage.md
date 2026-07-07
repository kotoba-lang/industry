# ADR-2607071717: `cloud-itonami-isic-8530` (higher education) deepened to `:implemented` -- seventh vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`, the first six verticals built outside ADR-2607032000's
  original insurance/real-estate batch); ADR-2607032000 (the original
  batch, fully closed); `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/
  `6629`/`6520`/`6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`
  ADR-0001s (the governed-actor pattern this decision continues);
  langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8620`, this ADR records the SEVENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-8530` (higher education, ISIC division 85) -- the first
  education vertical in this fleet, continuing the deliberate
  diversification beyond finance/insurance, professional/technical
  services and healthcare that began with `7120`/`8620`.

## Problem

`cloud-itonami-isic-8530` published a business/operator-model
blueprint (RegistrarOps-LLM ⊣ Academic Integrity Governor,
`:blueprint` maturity) but had no governed actor implementation.
Deepening it required:

1. **Jurisdiction degree-accreditation correctness** -- an official
   spec-basis citation from a real accreditation authority, never
   fabricated.
2. **Prerequisite completion** -- a pure ground-truth recompute (the
   SAME shape `credit.governor`'s/`accounting.governor`'s/`marketadmin.
   governor`'s/`testlab.governor`'s/`clinic.governor`'s checks
   establish), but the FIRST check in this fleet to be a SET-
   CONTAINMENT/subset test (a universal quantification) rather than
   `clinic.governor`'s existential single-item set-membership test.
3. **Credit sufficiency** -- reuses `marketadmin.governor/listing-
   standard-not-met-violations`'s MINIMUM-threshold shape for a further
   domain.
4. **Academic integrity** -- reuses the unconditional-evaluation
   discipline for a fifth distinct grounding.
5. **Dual actuation, on the SAME entity, with dedicated-boolean guard
   designs informed by five prior builds** -- finalizing a grade and
   conferring a degree, each guarded by its own dedicated boolean
   (`:grade-finalized?`/`:degree-conferred?`, informed by `6492`'s
   status-lifecycle bug and `6920`'s/`6611`'s/`7120`'s/`8620`'s
   deliberate avoidance of it), not a `:status` value.

See `cloud-itonami-isic-8530`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-8530` gains **RegistrarOps-LLM ⊣ Academic
   Integrity Governor** -- `registrar.*` namespaces, modeled closely on
   all fourteen prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. `prerequisites-satisfied?`/`prerequisites-not-satisfied-violations`
   is the FIRST check in this fleet's shared vocabulary to be a set-
   containment/subset test (does every required prerequisite appear in
   the completed-course set) rather than every prior set-based check's
   existential single-item membership test (`clinic.governor/
   contraindicated-violations`) -- proving the family generalizes to a
   universal set quantification.
3. `credits-not-sufficient-violations` reuses `marketadmin.governor/
   listing-standard-not-met-violations`'s MINIMUM-threshold pure-
   ground-truth-recompute shape for the higher-education domain.
4. Academic-integrity screening reuses the unconditional-evaluation
   discipline (`casualty.governor/sanctions-violations`'s original
   fix) for a FIFTH distinct grounding in this fleet: an unresolved
   flag HARD-holds both the screening op itself and both actuation
   ops.
5. Dual actuation on the SAME enrollment entity (mirroring
   `marketadmin.store`'s dual admission/halt-lift design rather than
   `accounting.store`'s dual engagement-type split), with independent
   dedicated-boolean double-actuation guards for each -- the SAME
   design choice `accounting.governor`'s/`marketadmin.governor`'s/
   `testlab.governor`'s/`clinic.governor`'s guards make, now applied
   correctly across a FIFTH consecutive build, each explicitly
   informed by `6492`'s status-lifecycle bug rather than re-derived by
   shape-analogy.
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"8530"`, fleet-wide maturity counts move from 20
   implemented / 77 blueprint / 546 spec to 21 implemented / 76
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/registrar/*` -- 39 tests / 182 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles (a
   grade-finalization cycle, a degree-conferral cycle) plus five HARD-
   hold cases (no spec-basis, an unsatisfied course prerequisite,
   insufficient credits for degree conferral, an unresolved academic-
   integrity flag, a double finalization/conferral) that never reach a
   human at all -- all correct on the FIRST demo run.

## Consequences

- (+) Higher education gets the same governed, auditable-actor
  treatment as the fourteen prior actors, and this fleet now has SEVEN
  concrete precedents (`6612`, `6492`, `6920`, `6611`, `7120`, `8620`,
  `8530`) for extending past ADR-2607032000's original scope, into
  genuinely different domains (finance, professional services,
  technical/scientific services, healthcare, and now education).
- (+) `prerequisites-satisfied?`/`prerequisites-not-satisfied-
  violations` is a genuine structural contribution to this fleet's
  shared vocabulary of governor-check shapes: the first set-
  containment/subset check, proving the set-based check family
  generalizes from existential to universal quantification.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/registrar/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The dedicated-boolean double-actuation-guard lesson (from
  `6492`'s bug) has now been applied correctly BY DESIGN across FIVE
  consecutive builds (`6920`, `6611`, `7120`, `8620`, `8530`), each
  explicitly citing the prior lesson rather than re-deriving it by
  pattern-matching.
- (+) Both the demo and the full test suite passed clean on the first
  run -- no bug this time, unlike `6492`/`6920`.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `registrar.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `prerequisites-satisfied?`/`credits-sufficient?` model only a
  literal course-code containment check and a total-credit-hour floor,
  not a full degree-audit/curriculum-map engine -- see `cloud-itonami-
  isic-8530`'s own ADR-0001 and README coverage table for the full
  honest-scope accounting.
- Fleet-wide: 21 actors now `:implemented` out of 643 total registry
  entries; 76 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/ADR-2607071640/ADR-2607071654 | ❌ | All six of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`; mixing a different ISIC division (85, vs. those six's 64/66/69/71/86) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-8530` at `:blueprint` only | ❌ | The standing direction continues past `8620`; higher education is a natural, well-precedented next domain, continuing the deliberate diversification into education, a division this fleet had not yet touched |
| See `cloud-itonami-isic-8530`'s own ADR-0001 Alternatives table for build-level decisions | -- | (dual-actuation-on-one-entity vs. two-entity-type split, degree-audit-engine scope, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`, first six post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8530/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
