# ADR-2607072830: `cloud-itonami-isic-8890` (other social work activities without accommodation) deepened to `:implemented` -- eighteenth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`, the first seventeen verticals built outside ADR-2607032000's
  original insurance/real-estate batch); ADR-2607032000 (the original
  batch, fully closed); `cloud-itonami-isic-6511`/`6512`/`6621`/
  `6622`/`6629`/`6520`/`6530`/`6820`/`6612`/`6492`/`6920`/`6611`/
  `7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/
  `9102`/`9103`/`9602`/`9000` ADR-0001s (the governed-actor pattern
  this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9000`, this ADR records the EIGHTEENTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-8890` (other social work activities without accommodation, ISIC
  division 88) -- a SECOND social-services vertical alongside `8730`'s
  eldercare, but for non-residential welfare casework rather than
  residential care.

## Problem

A social-services agency's eligibility-determination/referral
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction welfare-eligibility/social-work correctness** -- an
   official spec-basis citation from a real regulator, never
   fabricated.
2. **Eligibility-criteria sufficiency** -- the SECOND instance of the
   set-containment/subset family (`registrar.registry/prerequisites-
   satisfied?` established the first, for academic prerequisites),
   applied here to welfare-eligibility criteria.
3. **Risk-flag resolution verification** -- reuses the unconditional-
   evaluation screening discipline for a FIFTEENTH distinct grounding,
   gating BOTH actuation ops of a dual-actuation actor off the same
   unresolved-flag concept.
4. **Real, high-stakes actuation, twice** -- finalizing a real
   client's eligibility determination and finalizing a real client's
   referral are two independently-gated real-world acts on the SAME
   entity.

See `cloud-itonami-isic-8890`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-8890` gains **CaseworkOps-LLM ⊣ Social
   Services Governor** -- `casework.*` namespaces, modeled closely on
   all twenty-five prior actors' Store/Registry/Governor/Phase/
   Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. `eligibility-criteria-unsatisfied?` is the SECOND instance of the
   set-containment/subset family (`registrar.registry/prerequisites-
   satisfied?` established the first), extending it to a domain with
   no academic-curriculum concept at all: a case's own required
   welfare-eligibility criteria must ALL appear in its own recorded
   satisfied-criteria set.
3. `risk-flag-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a FIFTEENTH distinct grounding in this fleet.
4. Tested via the SCREENING op (`:risk/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment` lesson PROACTIVELY for a
   sixth consecutive vertical.
5. Dual actuation (`:actuation/finalize-eligibility`, `:actuation/
   finalize-referral`), matching `6512`'s/`6622`'s/`6520`'s/`6530`'s/
   `6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/`8730`'s/
   `9102`'s/`9103`'s dual-actuation shape, each with its own history
   collection, sequence counter and dedicated double-actuation boolean
   guard (never a `:status` value, per `6492`'s ADR-0001 lesson).
6. The Store protocol's entity accessor is named `case-` (trailing
   dash), not `case`, to avoid colliding with the `case` special form
   `clojure.core` refers into every `.cljc` namespace by default -- a
   new naming consideration this fleet had not previously encountered.
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"8890"`, fleet-wide maturity counts move from 31
   implemented / 66 blueprint / 546 spec to 32 implemented / 65
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/casework/*` -- 38 tests / 178 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: two clean lifecycles
   (eligibility determination, referral) plus five HARD-hold cases (no
   spec-basis, an unsatisfied eligibility criterion, an unresolved
   risk flag screened directly and never reaching a human, and a
   double finalization of each actuation op) that never reach a human
   at all.

## Consequences

- (+) Non-residential social-services casework gets the same
  governed, auditable-actor treatment as the twenty-five prior actors,
  and this fleet now has an EIGHTEENTH concrete precedent for
  extending past ADR-2607032000's original scope, deepening social-
  services coverage (ISIC division 88) alongside `8730`'s eldercare.
- (+) `eligibility-criteria-unsatisfied?` is a genuine structural
  contribution: the second instance of the set-containment/subset
  family, reused for a domain with no academic-curriculum concept.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/casework/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The risk-flag-unresolved test/demo correctly applied the
  established SCREENING-op-directly pattern for a sixth consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `casework.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `eligibility-criteria-unsatisfied?` models only a literal
  required-criteria-vs-satisfied-criteria membership check, not a full
  means-testing/benefits-calculation engine -- see `cloud-itonami-
  isic-8890`'s own ADR-0001 and README coverage table for the full
  honest-scope accounting.
- Fleet-wide: 32 actors now `:implemented` out of 643 total registry
  entries; 65 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All seventeen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`; mixing a different sub-domain into any would blur scope boundaries even where the ISIC division (88) overlaps with `8730` |
| Keep `cloud-itonami-isic-8890` at `:blueprint` only | ❌ | The standing direction continues past `9000`; non-residential social-work casework is a natural, well-precedented next domain, deepening this fleet's social-services coverage with a genuinely different service model than `8730`'s residential eldercare |
| See `cloud-itonami-isic-8890`'s own ADR-0001 Alternatives table for build-level decisions | -- | (set-containment framing, `case-` naming to avoid the special-form collision, unconditional-evaluation test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`, first seventeen post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8890/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
