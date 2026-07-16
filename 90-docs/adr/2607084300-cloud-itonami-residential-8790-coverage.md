# ADR-2607084300: `cloud-itonami-isic-8790` (other residential care activities) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607083600 (`cloud-itonami-isic-9609`, other personal service activities n.e.c.)
- ADR-2607083800 (`cloud-itonami-isic-8550`, educational support activities)
- ADR-2607084000 (`cloud-itonami-isic-7010`, activities of head offices)
- `cloud-itonami-isic-8790/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-8790` publishes an OSS business blueprint for
other residential care activities not elsewhere classified: children's
homes, orphanages, residential care for the homeless and similar
vulnerable-population residential settings. Like every prior vertical
in this fleet, the blueprint text alone is not an implementation —
this ADR records the governed-actor build that promotes `cloud-
itonami-isic-8790` from `:blueprint` to `:implemented` in the
`kotoba-lang/industry` registry, the fifty-third vertical built
outside ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-8790`'s blueprint described a real-world
   residential-care operating model (resident intake, care-plan
   verification, safeguarding/background-check screening, care-plan
   and incident-response finalization) but had no governed-actor
   implementation: no Store, no Governor, no rollout phasing, no
   tests.
2. The blueprint's own text consistently names TWO real-world acts
   together ("finalizing a care plan or incident response"), and the
   business-model.md's own Offer lists them as two separate items —
   this build needed to resolve whether that is one conceptual act or
   two distinct actuations.
3. Residential care for vulnerable populations (children, homeless
   individuals) carries a genuine, real-world compliance concern
   distinct from every prior sibling: mandatory-reporting law
   requires unresolved child-abuse/neglect (or comparable vulnerable-
   adult) concerns to be reported before a care plan or incident
   response can be finalized — no dedicated check for this concept
   exists anywhere else in this fleet, despite the fleet already
   having many unconditional-evaluation groundings for other concerns.
4. This fleet already has a "background check not cleared" concept
   (`school`/8510, reused by `sports`/8541, `personalservice`/9609
   and `edsupport`/8550) — this build's own background-check
   screening needed to honestly characterize its own reuse rather
   than overclaim novelty.

## Decision

1. **Dual-actuation-on-one-entity shape.** Matching `nursing`/8710's,
   `laundry`/9601's and `holdco`/6420's precedent — the blueprint's
   Offer lists "care-plan proposal" and "incident-response proposal"
   as two SEPARATE items, and the two acts are conceptually distinct
   (a proactive planning act vs. a reactive incident-handling act),
   unlike `sports`/8541's genuinely ambiguous either/or phrasing.
   `high-stakes` is the two-member set `#{:actuation/finalize-care-
   plan :actuation/finalize-incident-response}`, each with its own
   history collection, sequence counter and dedicated double-
   actuation-guard boolean.
2. **Entity and op shape.** Primary entity `resident`. Six ops:
   `:resident/intake`, `:careplan/verify`, `:safeguarding/screen`,
   `:background-check/screen`, `:actuation/finalize-care-plan`
   (high-stakes), `:actuation/finalize-incident-response`
   (high-stakes).
3. **`mandatory-reporting-obligation-unresolved-violations` — the
   51st unconditional-evaluation grounding, a genuinely new concept.**
   Grep-verified absent as a dedicated CHECK FUNCTION across every
   prior sibling. Grounded in US CAPTA (42 U.S.C. §5106g), Japan's
   Child Abuse Prevention Act Article 6, and Germany's SGB VIII §8a.
   Gates `:safeguarding/screen` and both actuations.
4. **`background-check-not-cleared-violations` — an honest FIFTH
   literal reuse, not claimed as new.** `school.governor` established
   this concept first; `sports.governor` reused it literally as the
   second instance; `personalservice.governor` as the third;
   `edsupport.governor` as the fourth; this build's reuse is the
   fifth, the 52nd distinct application of the unconditional-
   evaluation discipline overall. Gates `:background-check/screen`
   and both actuations.
5. **TWO dedicated double-actuation-guard booleans.** `:care-plan-
   finalized?` and `:incident-response-finalized?`, never a single
   `:status` value, each with its own history/sequence.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/residential/store_contract_test.clj`. The per-entity
   accessor is safely named `resident` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:resident/
   intake}` only; both actuations are permanently excluded from every
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

- Sixty-seventh actor in this fleet (66 implemented before this
  build).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (mandatory-reporting-obligation-unresolved), grep-verified
  absent from every prior sibling before the claim was finalized.
- Documents an honest FIFTH literal reuse of the background-check-
  not-cleared concept (school 1st, sports 2nd, personalservice 3rd,
  edsupport 4th, residential 5th), not claimed as new.
- Confirms the dual-actuation-on-one-entity shape generalizes to a
  4th instance in this fleet (nursing, laundry, holdco, residential).
- `MemStore` ‖ `DatomicStore` parity is proven by `test/residential/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 66 → 67, `:blueprint` 18 → 17,
  `:spec` 546 unchanged, total 643.
- Test status: 35 tests / 184 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 115 assertions,
  all green, no pre-existing fixture referenced ISIC "8790".
- This build's `deps.edn` used the CURRENT `kotoba-lang/langgraph`/
  `langchain` coordinates from the start (see `holdco`/6420's own
  ADR-0001 for the upstream-rename context this build inherited).
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Treating "care plan or incident response" as one conceptual act (following `sports`/8541's either/or precedent) | Unlike `sports`/8541's genuinely ambiguous phrasing, this blueprint's Offer section lists the two acts as SEPARATE line items, and they are conceptually distinct (proactive planning vs. reactive incident handling) — matching `nursing`/8710's own resolution of a nearly identical pattern |
| Reusing `nursing`/8710's `credential-not-current` concept instead of a new mandatory-reporting concept | This blueprint's population (children/vulnerable residents) and domain concern (safeguarding/abuse reporting, not staff credentialing) call for a distinct, dedicated check grounded in real child-welfare law |
| A single combined screening op covering both distinctive concerns | The two concerns are independently groundable in different real-world regulatory regimes; two separate dedicated ops more precisely match the "screen the screening op directly" discipline |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8790/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8790/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8790/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"8790"`)
