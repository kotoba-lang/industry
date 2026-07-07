# ADR-2607072915: `cloud-itonami-isic-8510` (pre-primary and primary education) deepened to `:implemented` -- twenty-first vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610`/`9311`, the first twenty verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/
  `9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/
  `9311` ADR-0001s (the governed-actor pattern this decision
  continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9311`, this ADR records the TWENTY-FIRST
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-8510` (pre-primary and primary education, ISIC section P) --
  the FIRST education vertical built in this fleet.

## Problem

A school's promotion-finalization/safeguarding-record-finalization
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction school-licensing correctness** -- an official spec-
   basis citation from a real regulator (文部科学省/state Departments
   of Education under ESSA/the DfE and Ofsted jointly/the
   Kultusministerien der Länder), never fabricated.
2. **Destination-classroom sufficiency** -- the SECOND non-temporal
   instance of this fleet's MAXIMUM-ceiling family (`facility.
   registry/occupancy-exceeds-capacity?` established the first),
   reusing the same two-field-on-one-entity comparison shape for a
   genuinely different ground truth: destination-classroom size vs.
   its own maximum.
3. **Staff background-check clearance verification** -- reuses the
   unconditional-evaluation screening discipline for an EIGHTEENTH
   distinct grounding overall, and a FIRST specifically for the staff-
   background-check-clearance concept (distinct from the credential-
   CURRENCY concept `clinic`/`veterinary`/`hospital` established).
4. **Real, high-stakes actuation, twice** -- finalizing a real
   promotion and finalizing a real safeguarding record are two
   independently-gated real-world acts on the SAME entity.

See `cloud-itonami-isic-8510`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-8510` gains **SchoolOps-LLM ⊣ Curriculum
   Safeguarding Governor** -- `school.*` namespaces, modeled closely on
   all twenty-eight prior actors' Store/Registry/Governor/Phase/
   Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. `class-size-exceeds-maximum?` is the SECOND non-temporal instance
   of the MAXIMUM-ceiling family, comparing a student's own
   destination classroom's current size against that classroom's own
   recorded maximum -- reusing `facility`'s two-field-on-one-entity
   comparison shape for a genuinely different ground truth.
3. `background-check-not-cleared-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for an EIGHTEENTH distinct grounding overall, and a
   FIRST specifically for the staff-background-check-clearance concept
   -- a one-time clearance gate, genuinely distinct from the
   credential-CURRENCY concept `clinic`/`veterinary`/`hospital`
   established (a renewable license that can lapse and later be
   re-verified as current).
4. Tested via the SCREENING op (`:background-check/screen`) directly
   from the start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility` lesson PROACTIVELY for a ninth consecutive vertical.
5. Dual actuation (`:actuation/finalize-promotion`, `:actuation/
   finalize-safeguarding-record`), matching `6512`'s/`6622`'s/`6520`'s/
   `6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/
   `8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s dual-actuation shape,
   each with its own history collection, sequence counter and
   dedicated double-actuation boolean guard (never a `:status` value,
   per `6492`'s ADR-0001 lesson).
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"8510"`, fleet-wide maturity counts move from 34
   implemented / 63 blueprint / 546 spec to 35 implemented / 62
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/school/*` -- 36 tests / 173 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → assess → background-check screen → promotion
   finalize → safeguarding-record finalize) plus five HARD-hold cases
   (no spec-basis, a destination class size over its own maximum, an
   uncleared staff background check screened directly and never
   reaching a human, and a double promotion/safeguarding-record
   finalization) that never reach a human at all.

## Consequences

- (+) Pre-primary/primary education gets the same governed, auditable-
  actor treatment as the twenty-eight prior actors, and this fleet now
  has a TWENTY-FIRST concrete precedent for extending past
  ADR-2607032000's original scope, and its FIRST education-sector
  (ISIC section P) coverage.
- (+) `class-size-exceeds-maximum?` is a genuine structural
  contribution: the second non-temporal instance of the MAXIMUM-
  ceiling family, further validating that family's generality beyond
  the assembly-venue-occupancy concept `facility` established it for.
- (+) `background-check-not-cleared-violations` is a genuine domain-
  modeling contribution: the first time this fleet's unconditional-
  evaluation discipline has been applied to a one-time clearance gate
  rather than a renewable-license-currency concept.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/school/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The background-check-not-cleared test/demo correctly applied the
  established SCREENING-op-directly pattern for a ninth consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `school.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `class-size-exceeds-maximum?` models only a single classroom-
  size-vs-maximum comparison, not a full curriculum-design/
  pedagogical-assessment engine -- see `cloud-itonami-isic-8510`'s own
  ADR-0001 and README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 35 actors now `:implemented` out of 643 total registry
  entries; 62 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All twenty of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`; this is also this fleet's first education-sector vertical, with no prior sibling sharing even the same broad ISIC section |
| Keep `cloud-itonami-isic-8510` at `:blueprint` only | ❌ | The standing direction continues past `9311`; pre-primary/primary education is a natural next domain, opening this fleet's first education-sector coverage |
| See `cloud-itonami-isic-8510`'s own ADR-0001 Alternatives table for build-level decisions | -- | (check-family framing, background-check-vs-credential-currency distinction, screening-op test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610`/`9311`, first twenty post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8510/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
