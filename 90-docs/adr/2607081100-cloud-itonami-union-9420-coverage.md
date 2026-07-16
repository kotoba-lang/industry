# ADR-2607081100: `cloud-itonami-isic-9420` (activities of trade unions) deepened to `:implemented` -- thirty-second vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800/ADR-2607080900/ADR-2607081000 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/
  `9412`/`6491`/`8720`/`8521`/`6619`/`3600`/`6190`/`3030`/`3830`/
  `7020`, the first thirty-one verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/
  `9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/
  `9311`/`8510`/`9412`/`6491`/`8720`/`8521`/`6619`/`3600`/`6190`/
  `3030`/`3830`/`7020` ADR-0001s (the governed-actor pattern this
  decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `7020`, this ADR records the THIRTY-SECOND
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9420` (activities of trade unions) -- the FIRST labor-
  relations/collective-action vertical built in this fleet, distinct
  from `9412`'s business/employers membership-organization domain
  (unions represent WORKERS in disputes against employers -- a
  fundamentally different relationship and stakes profile).

## Problem

A trade union's strike-authorization/bargaining-position workflow
bundles several distinct concerns under one governed workflow:

1. **Jurisdiction labor-relations correctness** -- an official spec-
   basis citation from a real labor authority (厚生労働省/the NLRB/
   the Certification Officer-ACAS/the BMAS), never fabricated.
2. **Membership-vote-share sufficiency** -- the THIRD instance of
   this fleet's ratio-based check family (`leasing`=1st MINIMUM-
   floor, `behavioral`=2nd MAXIMUM-ceiling), applying the MINIMUM-
   floor direction to a real labor-law concept: strike authorization
   in every seeded jurisdiction requires clearing a legally-required
   majority threshold.
3. **Labor-compliance-flag resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a THIRTIETH
   distinct grounding overall, and -- grep-verified BEFORE writing the
   docstring -- a GENUINELY NEW concept (unlike `consulting`/7020's
   conflict-of-interest check, which was a reuse), the first
   application of this discipline to a labor-compliance concern.
4. **Real, high-stakes actuation, twice, BOTH positive** --
   authorizing a real strike action and finalizing a real public
   bargaining position are two independently-gated real-world acts on
   the SAME entity, both positive (issuing/finalizing a record) --
   matching this fleet's majority actuation shape, and grounded
   directly in this blueprint's own README, business-model.md AND
   operator-guide.md, all three of which consistently name exactly
   these two acts.

See `cloud-itonami-isic-9420`'s own `docs/adr/0001-architecture.md`
for the full design, distinctive checks and the dual-actuation
framing (this superproject ADR records the fleet-level context and
registry/maturity bookkeeping; the child repo's own ADR is the
authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9420` gains **UnionOps-LLM ⊣ Union Governance
   Governor** -- `union.*` namespaces, modeled closely on all forty-
   five prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. This is this fleet's FIRST labor-relations/collective-action
   vertical -- the first to model membership-vote thresholds and
   strike authorization at all, and distinct from `9412`'s business/
   employers membership-organization domain despite both being
   nominally "membership organizations" in ISIC classification.
3. `strike-vote-share-insufficient?` is the THIRD instance of the
   ratio-based check family, reusing the identical quotient-
   comparison shape (MINIMUM-floor direction, like `leasing`'s) for a
   dispute's own votes-in-favor divided by votes-cast against its own
   required-majority-share threshold, gating only `:actuation/
   authorize-strike`.
4. `compliance-flag-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a THIRTIETH distinct grounding overall, and --
   confirmed via grep BEFORE writing the docstring, applying the same
   verification discipline `consulting`'s own ADR-0001 established --
   is honestly identified as a GENUINELY NEW concept, not a reuse:
   zero prior siblings' `governor.cljc` reference "compliance-flag".
5. Tested via the SCREENING op (`:compliance/screen`) directly from
   the start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school`/`association`/`leasing`/`behavioral`/
   `secondary`/`card`/`water`/`telecom`/`aerospace`/`recovery`/
   `consulting` lesson PROACTIVELY for a twentieth consecutive
   vertical.
6. Dual actuation (`:actuation/authorize-strike`, `:actuation/
   finalize-bargaining-position`), matching `6512`'s/`6622`'s/
   `6520`'s/`6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/
   `9521`'s/`8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s/`8510`'s/
   `9412`'s/`8720`'s/`8521`'s/`6619`'s/`3600`'s/`6190`'s/`3030`'s/
   `3830`'s dual-actuation shape, each with its own history
   collection, sequence counter and dedicated double-actuation
   boolean guard (never a `:status` value, per `6492`'s ADR-0001
   lesson) -- chosen over a single-actuation shape (like `leasing`/
   `consulting`) because the blueprint's own text (README, business-
   model.md AND operator-guide.md) ALL THREE consistently name
   exactly two distinct real-world acts requiring governor gating.
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9420"`, fleet-wide maturity counts move from 45
   implemented / 52 blueprint / 546 spec to 46 implemented / 51
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/union/*` -- 36 tests / 177 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → verify → compliance screen → authorize strike
   → finalize bargaining position) plus five HARD-hold cases (no
   spec-basis, an insufficient strike-vote share, an unresolved
   compliance flag screened directly and never reaching a human, and
   a double authorization/finalization) that never reach a human at
   all.

## Consequences

- (+) Trade-union operation gets the same governed, auditable-actor
  treatment as the forty-five prior actors, and this fleet now has a
  THIRTY-SECOND concrete precedent for extending past
  ADR-2607032000's original scope, and its FIRST labor-relations/
  collective-action coverage.
- (+) `strike-vote-share-insufficient?` is a genuine structural
  contribution: the third instance of the ratio-based check family,
  and a naturally well-motivated mapping onto a real, universal labor-
  law concept.
- (+) `compliance-flag-unresolved-violations` correctly identifies a
  genuinely NEW concept (grep-verified, not a reuse) -- demonstrating
  this fleet's precedent-verification discipline works in BOTH
  directions: correctly flagging reuses (like `consulting`/7020's
  conflict-of-interest) AND correctly flagging genuine novelty (this
  build).
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/union/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The compliance-flag test/demo correctly applied the established
  SCREENING-op-directly pattern for a twentieth consecutive vertical
  -- further evidence that lessons recorded in this fleet's ADRs
  continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `union.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) This actor does not model a real union-management/dues-
  processing system, a real balloting/vote-tabulation system, or the
  actual collective-bargaining negotiation itself, and this R0
  deliberately does NOT model a separate quorum (votes-cast/votes-
  eligible) check alongside the majority-share check -- see `cloud-
  itonami-isic-9420`'s own ADR-0001 and README coverage table for the
  full honest-scope accounting.
- Fleet-wide: 46 actors now `:implemented` out of 643 total registry
  entries; 51 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to `9412`'s ADR (both nominally "membership organizations") | ❌ | Trade unions represent workers against employers -- a fundamentally different relationship/stakes profile from a business/professional membership association, and the first vertical to model collective-action mechanics at all |
| Keep `cloud-itonami-isic-9420` at `:blueprint` only | ❌ | The standing direction continues past `7020`; trade-union activity is a natural next domain, opening this fleet's first labor-relations/collective-action coverage |
| Model a quorum check alongside the majority-share ratio check | ❌ | Would double the check surface for a concern this blueprint's own text does not distinguish; kept as an honest, minimal R0 (see child repo's own ADR-0001 Alternatives table) |
| See `cloud-itonami-isic-9420`'s own ADR-0001 Alternatives table for build-level decisions | -- | (quorum-scope rationale, compliance-flag-vs-conflict-of-interest distinction rationale, dual-vs-single-actuation-shape rationale, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915/ADR-2607080100/ADR-2607080200/ADR-2607080300/
  ADR-2607080400/ADR-2607080500/ADR-2607080600/ADR-2607080700/
  ADR-2607080800/ADR-2607080900/ADR-2607081000 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`/
  `9412`/`6491`/`8720`/`8521`/`6619`/`3600`/`6190`/`3030`/`3830`/
  `7020`, first thirty-one post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9420/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
