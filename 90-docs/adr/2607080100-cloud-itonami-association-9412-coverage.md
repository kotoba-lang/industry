# ADR-2607080100: `cloud-itonami-isic-9412` (professional membership organizations) deepened to `:implemented` -- twenty-second vertical outside the original batch

- Status: Accepted (2026-07-08)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`/`8890`/`8610`/`9311`/`8510`, the first twenty-one verticals
  built outside ADR-2607032000's original insurance/real-estate
  batch); ADR-2607032000 (the original batch, fully closed);
  `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/
  `6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/
  `8890`/`8610`/`9311`/`8510` ADR-0001s (the governed-actor pattern
  this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8510`, this ADR records the TWENTY-SECOND
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-9412` (activities of professional membership organizations,
  ISIC division 94) -- the FIRST membership/professional-body-
  governance vertical built in this fleet.

## Problem

An association's certification-issuance/disciplinary-referral-
finalization workflow bundles several distinct concerns under one
governed workflow:

1. **Jurisdiction professional-body-governance correctness** -- an
   official spec-basis citation from a real regulator (内閣府公益認定
   等委員会/the ANSI National Accreditation Board/the Professional
   Standards Authority/the Kammern der Länder), never fabricated.
2. **Continuing-education sufficiency** -- the FIRST non-temporal
   instance of this fleet's MINIMUM-threshold sufficiency family
   (`veterinary`/`funeral`/`hospital` established the first three, all
   temporal), generalizing the ground-truth-comparison shape to a
   numeric-hours concept.
3. **Ethics-complaint resolution verification** -- reuses the
   unconditional-evaluation screening discipline for a TWENTIETH
   distinct grounding overall, and a FIRST specifically for the
   professional-ethics/conduct-complaint concept.
4. **Real, high-stakes actuation, twice** -- issuing a real
   certification and finalizing a real disciplinary referral are two
   independently-gated real-world acts on the SAME entity.

See `cloud-itonami-isic-9412`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-9412` gains **AssocOps-LLM ⊣ Association
   Governance Governor** -- `association.*` namespaces, modeled
   closely on all twenty-nine prior actors' Store/Registry/Governor/
   Phase/Advisor/Operation/Sim shape and the SAME generic langgraph-
   clj StateGraph.
2. `continuing-education-hours-insufficient?` is the FIRST non-
   temporal instance of the MINIMUM-threshold sufficiency family,
   comparing a member's own completed continuing-education hours
   against the association's own recorded minimum requirement --
   reusing the family's ground-truth-comparison shape for a genuinely
   different, non-temporal numeric concept.
3. `complaint-unresolved-violations` reuses the unconditional-
   evaluation discipline (`casualty.governor/sanctions-violations`'s
   original fix) for a TWENTIETH distinct grounding overall, and a
   FIRST specifically for the professional-ethics/conduct-complaint
   concept -- deliberately scoped to gate only `:certification/issue`,
   not `:discipline/finalize`, since an unresolved complaint is the
   natural precursor to a referral rather than a disqualifying
   condition for finalizing one.
4. Tested via the SCREENING op (`:complaint/screen`) directly from the
   start, applying the `parksafety`/`eldercare`/`museum`/
   `conservation`/`salon`/`entertainment`/`casework`/`hospital`/
   `facility`/`school` lesson PROACTIVELY for a tenth consecutive
   vertical.
5. Dual actuation (`:actuation/issue-certification`, `:actuation/
   finalize-disciplinary-referral`), matching `6512`'s/`6622`'s/
   `6520`'s/`6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/
   `9521`'s/`8730`'s/`9102`'s/`9103`'s/`8890`'s/`8610`'s/`8510`'s
   dual-actuation shape, each with its own history collection,
   sequence counter and dedicated double-actuation boolean guard
   (never a `:status` value, per `6492`'s ADR-0001 lesson).
6. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"9412"`, fleet-wide maturity counts move from 35
   implemented / 62 blueprint / 546 spec to 36 implemented / 61
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
7. `test/association/*` -- 36 tests / 173 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean dual-actuation
   lifecycle (intake → assess → complaint screen → certification issue
   → discipline finalize) plus five HARD-hold cases (no spec-basis,
   insufficient continuing-education hours, an unresolved ethics
   complaint screened directly and never reaching a human, and a
   double certification-issuance/disciplinary-referral-finalization)
   that never reach a human at all.

## Consequences

- (+) Professional-membership-organization governance gets the same
  governed, auditable-actor treatment as the twenty-nine prior actors,
  and this fleet now has a TWENTY-SECOND concrete precedent for
  extending past ADR-2607032000's original scope, and its FIRST
  membership/professional-body-governance-sector (ISIC division 94)
  coverage.
- (+) `continuing-education-hours-insufficient?` is a genuine
  structural contribution: the first non-temporal instance of the
  MINIMUM-threshold sufficiency family, further validating that
  family's generality beyond elapsed-time comparisons.
- (+) `complaint-unresolved-violations` is a genuine domain-modeling
  contribution: the first time this fleet's unconditional-evaluation
  discipline has been deliberately scoped to gate only ONE of two
  actuations rather than reflexively applying to both.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  association/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern every sibling actor uses.
- (+) The complaint-unresolved test/demo correctly applied the
  established SCREENING-op-directly pattern for a tenth consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `association.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `continuing-education-hours-insufficient?` models only a single
  completed-vs-required-hours comparison, not a full standards-
  setting/continuing-education-curriculum engine -- see `cloud-
  itonami-isic-9412`'s own ADR-0001 and README coverage table for the
  full honest-scope accounting.
- Fleet-wide: 36 actors now `:implemented` out of 643 total registry
  entries; 61 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All twenty-one of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`/`9311`/`8510`; this is also this fleet's first membership-sector vertical, with no prior sibling sharing even the same broad ISIC division |
| Keep `cloud-itonami-isic-9412` at `:blueprint` only | ❌ | The standing direction continues past `8510`; professional-membership-organization governance is a natural next domain, opening this fleet's first membership-sector coverage |
| See `cloud-itonami-isic-9412`'s own ADR-0001 Alternatives table for build-level decisions | -- | (complaint-unresolved scoping rationale, check-family framing, screening-op test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845/ADR-2607072900/
  ADR-2607072915 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`/`8890`/`8610`/`9311`/`8510`, first twenty-one post-batch
  verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9412/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
