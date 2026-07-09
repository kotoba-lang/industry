# ADR-2607081000: `cloud-itonami-isic-7020` (management consultancy activities) deepened to `:implemented` -- thirty-first vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800/ADR-2607080900 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/
  `8720`/`8521`/`6619`/`3600`/`6190`/`3030`/`3830`, the first thirty
  verticals built outside ADR-2607032000's original insurance/real-
  estate batch); ADR-2607032000 (the original batch, fully closed);
  `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/
  `6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/
  `8890`/`8610`/`9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/
  `3600`/`6190`/`3030`/`3830` ADR-0001s (the governed-actor pattern
  this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `3830`, this ADR records the THIRTY-FIRST
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-7020` (management consultancy activities) -- the FIRST
  professional-services vertical built in this fleet.

## Problem

A management consultancy's finding/deliverable workflow bundles
several distinct concerns under one governed workflow:

1. **Jurisdiction professional-standards correctness** -- an official
   spec-basis citation from a real recognized professional body
   (日本コンサルティング協会/IMC USA/the MCA-CMI/the BDU), never
   fabricated.
2. **Engagement-scope boundary sufficiency** -- the FOURTH instance of
   this fleet's set-containment/subset check family (`registrar`/
   `casework`/`secondary` established the first three), but in the
   OPPOSITE polarity: a proposed recommendation set must stay within a
   contracted scope set, rather than a required set being satisfied by
   a completed set.
3. **Conflict-of-interest resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TWENTY-NINTH
   distinct grounding overall, and correctly identified (via grep) as
   the FOURTH grounding of the specific conflict-of-interest concept
   (after `adjustment`/6621, `intermediation`/6622, `brokerage`/6612),
   the first time this concept applies outside a financial-services
   context.
4. **Real, high-stakes actuation, ONCE** -- issuing a real
   recommendation/deliverable to a client is the ONE independently-
   gated real-world act this blueprint's own Trust Controls name,
   making this the SECOND single-actuation-shape build in this fleet
   (after `leasing`/6491).

See `cloud-itonami-isic-7020`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks and the single-actuation/
check-polarity framing (this superproject ADR records the fleet-level
context and registry/maturity bookkeeping; the child repo's own ADR
is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-7020` gains **Consultant-LLM ⊣ Consulting
   Engagement Governor** -- `consulting.*` namespaces, modeled closely
   on all forty-four prior actors' Store/Registry/Governor/Phase/
   Advisor/Operation/Sim shape and the SAME generic langgraph-clj
   StateGraph.
2. This is this fleet's FIRST professional-services vertical --
   distinct from every prior human-services, leisure, financial-
   services, infrastructure/utility, manufacturing or circular-economy
   domain, and the SECOND single-actuation-shape build (after
   `leasing`/6491), confirming the governed-actor pattern scales down
   cleanly to the leanest blueprint tier as well as the richest.
3. `engagement-scope-exceeded?` is the FOURTH instance of the set-
   containment/subset check family, reusing the identical `clojure.
   set/subset?` mechanism but in the OPPOSITE polarity from its
   predecessors (a proposed set staying within a contracted set,
   rather than a required set being satisfied by a completed set) --
   a genuine generalization of the family, documented rather than
   silently assumed identical.
4. `conflict-of-interest-unresolved-violations` reuses the
   unconditional-evaluation discipline (`casualty.governor/sanctions-
   violations`'s original fix) for a TWENTY-NINTH distinct grounding
   overall, and -- confirmed via grep BEFORE writing the docstring,
   avoiding the false-precedent-claim risk `leasing`'s ADR-0001
   documents -- is honestly identified as the FOURTH grounding of the
   EXISTING conflict-of-interest concept (`adjustment`/6621,
   `intermediation`/6622, `brokerage`/6612 established the first
   three), and the FIRST time this concept applies outside a
   financial-services context.
5. Tested via the SCREENING op (`:conflict/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral`/
   `secondary`/`card`/`water`/`telecom`/`aerospace`/`recovery` lesson
   PROACTIVELY for a nineteenth consecutive vertical.
6. Single actuation (`:actuation/issue-deliverable`), matching
   `leasing`'s/`underwriting`'s/`testlab`'s/`clinic`'s/`veterinary`'s/
   `funeral`'s/`parksafety`'s/`salon`'s/`entertainment`'s/`facility`'s
   single-actuation shape (one history collection, one sequence
   counter, one dedicated double-actuation boolean guard, never a
   `:status` value, per `6492`'s ADR-0001 lesson) -- deliberately NOT
   invented as a dual-actuation build, since the blueprint's own text
   names only one real-world act.
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"7020"`, fleet-wide maturity counts move from 44
   implemented / 53 blueprint / 546 spec to 45 implemented / 52
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/consulting/*` -- 30 tests / 129 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean single-actuation
   lifecycle (intake → research → conflict screen → issue deliverable)
   plus four HARD-hold cases (no spec-basis, a deliverable exceeding
   contracted scope, an unresolved conflict of interest screened
   directly and never reaching a human, and a double deliverable
   issuance) that never reach a human at all.

## Consequences

- (+) Management-consultancy operation gets the same governed,
  auditable-actor treatment as the forty-four prior actors, and this
  fleet now has a THIRTY-FIRST concrete precedent for extending past
  ADR-2607032000's original scope, and its FIRST professional-services
  coverage.
- (+) `engagement-scope-exceeded?` is a genuine structural
  contribution: the fourth instance of the set-containment/subset
  check family, and the first to invert the polarity into a
  permission-boundary framing, expanding the family's applicability
  beyond pure sufficiency checks.
- (+) `conflict-of-interest-unresolved-violations` correctly avoids a
  false-precedent claim -- honestly identified as a reuse (4th
  grounding) rather than mis-attributed as a new concept, and confirms
  the concept generalizes outside financial services for the first
  time.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/consulting/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses, including the EDN-string-encoding
  convention extended to Clojure-set-valued entity fields.
- (+) The conflict-of-interest test/demo correctly applied the
  established SCREENING-op-directly pattern for a nineteenth
  consecutive vertical -- further evidence that lessons recorded in
  this fleet's ADRs continue to transfer forward reliably.
- (+) Confirms the governed-actor pattern applies cleanly to the
  leanest blueprint tier (single actuation, generic boilerplate Core
  Contract, no `docs/samples/operator-console.html`) as well as the
  richest.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `consulting.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) This actor does not model a real engagement-management/CRM
  system, real strategy-analysis tooling, or the actual analytical work
  a consultant performs -- see `cloud-itonami-isic-7020`'s own
  ADR-0001 and README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 45 actors now `:implemented` out of 643 total registry
  entries; 52 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Invent a second actuation to match the fleet's more common dual-actuation shape | ❌ | The blueprint's own published Trust Controls and Core Contract name exactly ONE real-world act requiring governor gating; inventing a second would fabricate scope not grounded in the blueprint's own text |
| Keep `cloud-itonami-isic-7020` at `:blueprint` only | ❌ | The standing direction continues past `3830`; management consultancy is a natural next domain, opening this fleet's first professional-services coverage |
| Claim conflict-of-interest as a novel check-family concept | ❌ | Grep-verified as an existing concept (adjustment/6621, intermediation/6622, brokerage/6612); honestly framed as the 4th grounding, not a new contribution (see child repo's own ADR-0001 Alternatives table) |
| See `cloud-itonami-isic-7020`'s own ADR-0001 Alternatives table for build-level decisions | -- | (single-actuation-shape rationale, check-polarity rationale, precedent-verification rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800/ADR-2607080900 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/`9412`/`6491`/
  `8720`/`8521`/`6619`/`3600`/`6190`/`3030`/`3830`, first thirty
  post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-7020/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
