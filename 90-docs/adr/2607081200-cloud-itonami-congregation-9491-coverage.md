# ADR-2607081200: `cloud-itonami-isic-9491` (activities of religious organizations) deepened to `:implemented` -- thirty-third vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800/ADR-2607080900/ADR-2607081000/ADR-2607081100
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/`3600`/
  `6190`/`3030`/`3830`/`7020`/`9420`, the first thirty-two verticals
  built outside ADR-2607032000's original insurance/real-estate
  batch); ADR-2607032000 (the original batch, fully closed); `cloud-
  itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/
  `6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/`3600`/
  `6190`/`3030`/`3830`/`7020`/`9420` ADR-0001s (the governed-actor
  pattern this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9420`, this ADR records the THIRTY-THIRD
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9491` (activities of religious organizations) -- the FIRST
  congregational/religious-organization vertical built in this fleet.

## Problem

A religious congregation's pastoral-referral/doctrinal-statement
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction administrative/safeguarding correctness** -- an
   official spec-basis citation from a real religious-corporation
   registrar and general child/vulnerable-person safeguarding law
   (文化庁/the state nonprofit registrars-CAPTA/the Charity Commission-
   DBS/the BMFSFJ), never fabricated, and never citing doctrine
   itself.
2. **Doctrinal-statement self-consistency** -- the FIFTH instance of
   this fleet's set-containment/subset check family (`registrar`/
   `casework`/`secondary` established the first three, `consulting`
   the fourth in the permission/boundary polarity), applied here as a
   PURE self-consistency check that deliberately never judges
   theological correctness -- only whether a proposed statement's
   topics stay within the congregation's OWN previously self-declared
   core doctrine.
3. **Safeguarding-concern resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a THIRTY-FIRST
   distinct grounding overall, explicitly distinguished (by reading
   the actual code, not just the name) from `school`/8510's staff-
   background-check concept.
4. **Real, high-stakes actuation, twice, BOTH positive** -- finalizing
   a real pastoral-care referral and publishing a real public
   doctrinal statement are two independently-gated real-world acts on
   the SAME entity, grounded directly in this blueprint's own README,
   business-model.md AND operator-guide.md, all three of which
   consistently name exactly these two acts.

See `cloud-itonami-isic-9491`'s own `docs/adr/0001-architecture.md`
for the full design, the explicit administrative-vs-doctrine boundary,
and the distinctive checks (this superproject ADR records the fleet-
level context and registry/maturity bookkeeping; the child repo's own
ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9491` gains **CongregationOps-LLM ⊣
   Congregational Governance Governor** -- `congregation.*`
   namespaces, modeled closely on all forty-six prior actors' Store/
   Registry/Governor/Phase/Advisor/Operation/Sim shape and the SAME
   generic langgraph-clj StateGraph.
2. This is this fleet's FIRST congregational/religious-organization
   vertical, deliberately confined to administrative/safeguarding
   concerns -- neither `congregation.facts` (spec-basis catalog) nor
   `congregation.registry/doctrinal-statement-exceeds-core-doctrine?`
   ever model, judge, or generate doctrine/theology itself, a boundary
   made explicit in both the child repo's ADR-0001 and its README.
3. `doctrinal-statement-exceeds-core-doctrine?` is the FIFTH instance
   of the set-containment/subset check family, reusing the identical
   `clojure.set/subset?` mechanism in the permission/boundary polarity
   `consulting` established (the second instance of that polarity),
   but framed explicitly as a pure self-consistency check against the
   congregation's OWN prior self-declaration, never an external
   theological judgment.
4. `safeguarding-concern-unresolved-violations` reuses the
   unconditional-evaluation discipline (`casualty.governor/sanctions-
   violations`'s original fix) for a THIRTY-FIRST distinct grounding
   overall -- and, after actually READING `school.governor/
   background-check-not-cleared-violations`'s implementation (not just
   grepping its name, applying the verification discipline `leasing`'s
   and `union`'s ADR-0001s establish), is honestly distinguished as a
   DIFFERENT concept: `school`'s check verifies a staff member's own
   clearance status; this check verifies whether the matter/situation
   itself carries an unresolved concern flag.
5. Tested via the SCREENING op (`:safeguarding/screen`) directly from
   the start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral`/
   `secondary`/`card`/`water`/`telecom`/`aerospace`/`recovery`/
   `consulting`/`union` lesson PROACTIVELY for a twenty-first
   consecutive vertical.
6. Dual actuation (`:actuation/finalize-pastoral-referral`,
   `:actuation/publish-doctrinal-statement`), matching `6512`'s/
   `6622`'s/`6520`'s/`6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/
   `9200`'s/`9521`'s/`8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s/
   `8510`'s/`9412`'s/`8720`'s/`8521`'s/`6619`'s/`3600`'s/`6190`'s/
   `3030`'s/`3830`'s/`9420`'s dual-actuation shape, each with its own
   history collection, sequence counter and dedicated double-actuation
   boolean guard (never a `:status` value, per `6492`'s ADR-0001
   lesson). BOTH actuations here are POSITIVE, matching this fleet's
   majority shape (`3600`/`6190` remain the only negative-actuation
   exceptions).
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9491"`, fleet-wide maturity counts move from 46
   implemented / 51 blueprint / 546 spec to 47 implemented / 50
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/congregation/*` -- 36 tests / 174 assertions, lint-clean,
   demo (`clojure -M:dev:run`) runs end-to-end: one clean dual-
   actuation lifecycle (intake → verify → safeguarding screen →
   finalize referral → publish statement) plus five HARD-hold cases
   (no spec-basis, a doctrinal statement exceeding core doctrine, an
   unresolved safeguarding concern screened directly and never
   reaching a human, and a double referral/publication) that never
   reach a human at all.

## Consequences

- (+) Congregational operation gets the same governed, auditable-
  actor treatment as the forty-six prior actors, and this fleet now
  has a THIRTY-THIRD concrete precedent for extending past
  ADR-2607032000's original scope, and its FIRST congregational/
  religious-organization coverage.
- (+) `doctrinal-statement-exceeds-core-doctrine?` is a genuine
  structural contribution: the fifth instance of the set-containment
  family, and a careful demonstration that a sensitive domain can be
  governed WITHOUT the actor or governor ever adjudicating content it
  has no competence to judge.
- (+) `safeguarding-concern-unresolved-violations` correctly
  distinguishes itself from an existing sibling concept by reading
  actual code rather than assuming from a name -- extending the
  precedent-verification discipline this fleet's ADRs have built up
  (`leasing` verifying before claiming NEW, `union` verifying before
  claiming NEW, `consulting` verifying before claiming REUSE, this
  build verifying before claiming DISTINCT-FROM-AN-EXISTING-CONCEPT).
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  congregation/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern every sibling actor uses.
- (+) The safeguarding-concern test/demo correctly applied the
  established SCREENING-op-directly pattern for a twenty-first
  consecutive vertical -- further evidence that lessons recorded in
  this fleet's ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `congregation.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) This actor does not model a real congregation-management system,
  the actual pastoral-care casework itself, or -- deliberately -- any
  judgment of theological/doctrinal content -- see `cloud-itonami-
  isic-9491`'s own ADR-0001 and README coverage table for the full
  honest-scope accounting.
- Fleet-wide: 47 actors now `:implemented` out of 643 total registry
  entries; 50 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-9491` at `:blueprint` only | ❌ | The standing direction continues past `9420`; religious-organization administration is a natural next domain, opening this fleet's first congregational coverage |
| Cite a specific denomination's safeguarding framework as the jurisdiction spec-basis | ❌ | This actor serves ANY religious organization in a jurisdiction; citing broadly-applicable government administrative/safeguarding law keeps the catalog non-preferential across denominations |
| Model the doctrinal-statement check as an external judgment of theological correctness | ❌ | Categorically outside any government's or this actor's competence -- see child repo's own ADR-0001 Decision 3/Alternatives table |
| See `cloud-itonami-isic-9491`'s own ADR-0001 Alternatives table for build-level decisions | -- | (spec-basis-neutrality rationale, doctrine-non-judgment rationale, safeguarding-concept-distinction rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800/ADR-2607080900/ADR-2607081000/ADR-2607081100
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/`3600`/
  `6190`/`3030`/`3830`/`7020`/`9420`, first thirty-two post-batch
  verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9491/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
