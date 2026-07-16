# ADR-2607083600: `cloud-itonami-isic-9609` (other personal service activities n.e.c.) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607083300 (`cloud-itonami-isic-6420`, activities of holding companies)
- ADR-2607083500 (`cloud-itonami-isic-7420`, photographic activities)
- `cloud-itonami-isic-9609/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-9609` publishes an OSS business blueprint for
other personal service activities not elsewhere classified:
matchmaking services, genealogical research, personal shopping/
concierge and similar personal-referral services. Like every prior
vertical in this fleet, the blueprint text alone is not an
implementation — this ADR records the governed-actor build that
promotes `cloud-itonami-isic-9609` from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry, the fiftieth
vertical built outside ADR-2607032000's original insurance/real-
estate batch.

## Problem

1. `cloud-itonami-isic-9609`'s blueprint described a real-world
   personal-service operating model (client intake, service-plan
   assessment, background-check screening, referral finalization)
   but had no governed-actor implementation: no Store, no Governor,
   no rollout phasing, no tests.
2. The blueprint's business-model.md Trust Controls named both "a
   service is performed" and "a personal referral is finalized" as
   needing sign-off, while the README's own singular "the following:"
   phrasing named only ONE act — this build needed to resolve which
   actuation shape applies.
3. Personal-service/matchmaking businesses carry a genuine, real-
   world compliance concern distinct from every prior sibling:
   consumer-protection law in every seeded jurisdiction grants clients
   a mandatory cooling-off/cancellation period after signing a
   personal-service contract, before a provider may finalize a real
   referral or introduction — a temporal ground-truth concern with no
   existing dedicated check in this fleet.
4. This fleet already has a "background check not cleared" concept
   (`school`/8510, reused by `sports`/8541) — this build's own
   background-check screening needed to honestly characterize its own
   reuse rather than overclaim novelty.

## Decision

1. **Single-actuation shape.** Following `sports`/8541's precedent for
   resolving the same either/or-naming ambiguity, the business-
   model.md's dual naming is treated as ONE conceptual act. Matching
   `leasing`/`underwriting`/`testlab`/`clinic`/`veterinary`/`funeral`/
   `parksafety`/`salon`/`entertainment`/`facility`/`consulting`/
   `advertising`/`polling`/`research`/`design`/`sports`/`alliedhealth`/
   `photo`'s single-actuation shape, `high-stakes` is the one-member
   set `#{:actuation/finalize-referral}`.
2. **Entity and op shape.** Primary entity `client`. Four ops:
   `:client/intake`, `:serviceplan/verify`, `:background-check/
   screen`, `:actuation/finalize-referral` (high-stakes).
3. **`cooling-off-period-not-elapsed?` — the 8th MINIMUM-threshold
   sufficiency instance, a genuinely new concept.** Grep-verified
   absent (zero hits for "cooling-off"/"cancellation-period"/
   "rescission" across every prior sibling). Following `veterinary`/
   `funeral`/`hospital` (1st-3rd, temporal) and `association`/
   `secondary`/`polling`/`research` (4th-7th, non-temporal), this
   recomputes `(< days-since-contract-signed minimum-cooling-off-
   period-days)` directly from the client's own recorded field,
   RETURNING to a temporal ground truth grounded in California's
   Dating Service Contracts Act §1694, Japan's cooling-off provisions,
   and the EU/UK 14-day right. Gates only `:actuation/finalize-
   referral`.
4. **`background-check-not-cleared-violations` — an honest THIRD
   literal reuse, not claimed as new.** `school.governor` established
   this concept first; `sports.governor` reused it literally as the
   second instance; this build's reuse is the THIRD literal instance,
   the 48th distinct application of the unconditional-evaluation
   discipline overall. An initial docstring draft undercounted this
   ordinal and was corrected before any test was run. Gates
   `:background-check/screen` and the actuation.
5. **Dedicated double-actuation-guard boolean.** `:referral-
   finalized?` on the `client` record, never a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/personalservice/store_contract_test.clj`. The per-entity
   accessor is safely named `client` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:client/intake}`
   only; the actuation is permanently excluded from every phase's
   `:auto` set.
8. **No bespoke domain capability lib** — this blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-
   specific capability beyond the generic stack.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed** — the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Sixty-fourth actor in this fleet (63 implemented before this
  build).
- Confirms the MINIMUM-threshold sufficiency check family generalizes
  to an 8th instance, returning to a temporal ground truth for a
  genuinely new domain concept.
- Documents an honest THIRD literal reuse of the background-check-
  not-cleared concept, correcting an initial docstring undercount
  before landing.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/
  personalservice/store_contract_test.clj`.
- Fleet maturity: `:implemented` 63 → 64, `:blueprint` 21 → 20,
  `:spec` 546 unchanged, total 643.
- Test status: 30 tests / 135 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 112 assertions,
  all green, no pre-existing fixture referenced ISIC "9609".
- This build's `deps.edn` used the CURRENT `kotoba-lang/langgraph`/
  `langchain` coordinates from the start (see `holdco`/6420's own
  ADR-0001 for the upstream-rename context this build inherited).
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape (service performed + referral finalized as two separate high-stakes acts) | The README's own "No automated proposal..." phrasing names only one act; the business-model.md's either/or phrasing is treated as one conceptual act, following `sports`/8541's precedent |
| Folding the cooling-off check into the generic evidence-completeness checklist alone | Consumer-protection cooling-off law is a temporal, jurisdiction-specific minimum independent of whatever evidence checklist a proposal cites — a dedicated, unconditionally-recomputed check more precisely matches real cooling-off law |
| Treating the background-check reuse as a "renamed for domain fit" honest reuse (like `holdco`'s `certification-not-current`) | The concept, field name and check shape are IDENTICAL to `school`'s/`sports`'s — a literal reuse, not a rename, is the honest characterization |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-9609/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9609/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9609/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"9609"`)
