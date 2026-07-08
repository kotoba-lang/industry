# ADR-2607085600: `cloud-itonami-isic-7490` (other professional/scientific/technical n.e.c.) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607084400 (`cloud-itonami-isic-8542`, cultural education)
- ADR-2607084600 (`cloud-itonami-isic-6411`, central banking)
- `cloud-itonami-isic-7490/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-7490` publishes an OSS business blueprint for
other professional, scientific and technical activities not
elsewhere classified: translation/interpretation, non-real-estate/
non-insurance appraisal, and patent brokering. Like every prior
vertical in this fleet, the blueprint text alone is not an
implementation — this ADR records the governed-actor build that
promotes `cloud-itonami-isic-7490` from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry, the fifty-
sixth vertical built outside ADR-2607032000's original insurance/
real-estate batch.

## Problem

1. `cloud-itonami-isic-7490`'s blueprint described a real-world
   professional-services operating model (engagement intake,
   deliverable-scope verification, deliverable/attestation issuance)
   but had no governed-actor implementation: no Store, no Governor,
   no rollout phasing, no tests.
2. The blueprint's own text names only ONE real-world act ("issuing a
   deliverable or attestation to a client") — this build needed the
   single-actuation shape.
3. Patent brokering (one of this blueprint's own named example
   activities) carries a genuine, real-world compliance concern
   distinct from every prior sibling: chain-of-title verification
   (does an IP asset's own ownership chain verify clearly before a
   deliverable/attestation is issued) — no dedicated check for this
   concept exists anywhere else in this fleet, and it needed to be
   distinguished from `design`/7410's existing IP/licensing-conflict
   concept.
4. This fleet already has a long-established "credential not current"
   concept — this build's own credential screening needed to
   honestly characterize its own reuse rather than overclaim novelty.

## Decision

1. **Single-actuation shape.** Following `sports`/8541's precedent for
   resolving the same either/or-naming ambiguity, "deliverable or
   attestation" is treated as ONE conceptual act. Matching `leasing`/
   `underwriting`/`testlab`/`clinic`/`veterinary`/`funeral`/
   `parksafety`/`salon`/`entertainment`/`facility`/`consulting`/
   `advertising`/`polling`/`research`/`design`/`sports`/`alliedhealth`/
   `photo`/`personalservice`/`edsupport`/`cultural`'s single-
   actuation shape, `high-stakes` is the one-member set
   `#{:actuation/issue-deliverable}`.
2. **Entity and op shape.** Primary entity `engagement`. Five ops:
   `:engagement/intake`, `:engagement/verify`, `:chainoftitle/
   screen`, `:credential/screen`, `:actuation/issue-deliverable`
   (high-stakes).
3. **`chain-of-title-unresolved-violations` — the 55th unconditional-
   evaluation grounding, a genuinely new concept.** Grep-verified
   absent (zero hits for "chain-of-title" across every prior
   sibling). Grounded in USPTO 37 CFR Part 3, UK Patents Act 1977
   §33, Germany's Patentgesetz §30, and WIPO IP due-diligence
   guidance. Genuinely distinct from `design`/7410's `ip-licensing-
   conflict-unresolved?` (a deliverable-scope-vs-licensed-scope
   conflict, not an ownership-verification concern). Gates
   `:chainoftitle/screen` and the actuation.
4. **`credential-not-current-violations` — an honest reuse, not
   claimed as new.** This exact concept was already established by
   `clinic.governor` and reused by many siblings since (`hospital`/
   `eldercare`/`veterinary`/`nursing`). Grounded in real professional-
   certification practice (ATA certification, USPAP/RICS standards).
   Gates `:credential/screen` and the actuation.
5. **Dedicated double-actuation-guard boolean.** `:deliverable-
   issued?` on the `engagement` record, never a single `:status`
   value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/proserv/store_contract_test.clj`. The per-entity accessor is
   safely named `engagement` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:engagement/
   intake}` only; the actuation is permanently excluded from every
   phase's `:auto` set.
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

- Seventieth actor in this fleet (69 implemented before this build).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (chain-of-title-unresolved), grep-verified absent from
  every prior sibling before the claim was finalized.
- Documents an honest reuse of the long-established credential-not-
  current concept, not claimed as new.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/proserv/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 69 → 70, `:blueprint` 15 → 14,
  `:spec` 546 unchanged, total 643.
- Test status: 28 tests / 136 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 118 assertions,
  all green, no pre-existing fixture referenced ISIC "7490".
- This build's `deps.edn` used the CURRENT `kotoba-lang/langgraph`/
  `langchain` coordinates from the start (see `holdco`/6420's own
  ADR-0001 for the upstream-rename context this build inherited).
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape (deliverable + attestation as two acts) | The blueprint's own text consistently names only ONE real-world act; following `sports`/8541's precedent, this either/or naming is treated as one conceptual act |
| Reusing `design`/7410's `ip-licensing-conflict-unresolved?` for the chain-of-title concern | IP/licensing conflict (deliverable elements vs. licensed scope) and chain-of-title (asset ownership verification) are distinct real-world concerns, confirmed via grep |
| Claiming credential-not-current as a new concept | This exact concept is already well-established across many siblings; honest characterization matches this fleet's precedent-verification discipline |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7490/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7490/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7490/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"7490"`)
