# ADR-2607084400: `cloud-itonami-isic-8542` (cultural education) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607084000 (`cloud-itonami-isic-7010`, activities of head offices)
- ADR-2607084300 (`cloud-itonami-isic-8790`, other residential care activities)
- `cloud-itonami-isic-8542/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-8542` publishes an OSS business blueprint for
cultural education: instruction in music, arts, dance, language and
other cultural skills. Like every prior vertical in this fleet, the
blueprint text alone is not an implementation — this ADR records the
governed-actor build that promotes `cloud-itonami-isic-8542` from
`:blueprint` to `:implemented` in the `kotoba-lang/industry`
registry, the fifty-fourth vertical built outside ADR-2607032000's
original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-8542`'s blueprint described a real-world
   cultural-education operating model (student intake, curriculum
   verification, certification/progress-record finalization) but had
   no governed-actor implementation: no Store, no Governor, no
   rollout phasing, no tests.
2. The blueprint's own text names only ONE real-world act
   ("finalizing a certification or progress record") — this build
   needed the single-actuation shape.
3. Cultural education (music, arts, dance) frequently involves minors
   performing in public/recorded settings, which carries a genuine,
   real-world compliance concern distinct from every prior sibling:
   child-performer work-permit law (distinct from generic guardian
   consent, distinct from staff background checks) — no dedicated
   check for this concept exists anywhere else in this fleet.
4. This fleet already has an "hours completed vs. hours required"
   concept (`secondary`/8521's attendance-hours-insufficient?) — this
   build's own practice-hours check needed to honestly characterize
   its own reuse rather than overclaim novelty.

## Decision

1. **Single-actuation shape.** Following `sports`/8541's precedent for
   resolving the same either/or-naming ambiguity, "certification or
   progress record" is treated as ONE conceptual act. Matching
   `leasing`/`underwriting`/`testlab`/`clinic`/`veterinary`/`funeral`/
   `parksafety`/`salon`/`entertainment`/`facility`/`consulting`/
   `advertising`/`polling`/`research`/`design`/`sports`/`alliedhealth`/
   `photo`/`personalservice`/`edsupport`'s single-actuation shape,
   `high-stakes` is the one-member set `#{:actuation/finalize-
   certification}`.
2. **Entity and op shape.** Primary entity `student`. Four ops:
   `:student/intake`, `:curriculum/verify`, `:permit/screen`,
   `:actuation/finalize-certification` (high-stakes).
3. **`child-performer-work-permit-unresolved-violations` — the 53rd
   unconditional-evaluation grounding, a genuinely new concept.**
   Grep-verified absent (zero hits for "child-performer"/"performer-
   license"/"entertainment-work-permit" across every prior sibling).
   Grounded in California Labor Code §1308.5, UK's Children
   (Performances and Activities) Regulations 2014, Japan's Labor
   Standards Act Article 56/57, and Germany's JArbSchG §6. Gates
   `:permit/screen` and the actuation.
4. **`practice-hours-insufficient?` — an honest ninth MINIMUM-
   threshold instance, not claimed as new.** Directly analogous to
   `secondary.registry/attendance-hours-insufficient?`'s own shape.
   Gates only the actuation (a pure ground-truth recompute, no
   dedicated screening op needed).
5. **Dedicated double-actuation-guard boolean.** `:certification-
   finalized?` on the `student` record, never a single `:status`
   value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/cultural/store_contract_test.clj`. The per-entity accessor
   is safely named `student` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:student/
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

- Sixty-eighth actor in this fleet (67 implemented before this
  build).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (child-performer-work-permit-unresolved), grep-verified
  absent from every prior sibling before the claim was finalized.
- Documents an honest NINTH instance of the MINIMUM-threshold
  sufficiency check family, not claimed as new.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/cultural/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 67 → 68, `:blueprint` 17 → 16,
  `:spec` 546 unchanged, total 643.
- Test status: 30 tests / 132 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 116 assertions,
  all green, no pre-existing fixture referenced ISIC "8542".
- This build's `deps.edn` used the CURRENT `kotoba-lang/langgraph`/
  `langchain` coordinates from the start (see `holdco`/6420's own
  ADR-0001 for the upstream-rename context this build inherited).
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape (certification + progress record as two acts) | The blueprint's own text consistently names only ONE real-world act; following `sports`/8541's precedent, the either/or naming is treated as one conceptual act |
| Reusing `photo`/7420's `minor-subject-guardian-consent-unresolved?` concept | Guardian consent (parental consent for image use) and a child-performer work permit (labor-authority authorization to perform/work) are distinct real-world regulatory regimes — consent law vs. child-labor law |
| Reusing `secondary`/8521's `attendance-hours-insufficient?` literally without renaming | A domain-appropriate rename (`practice-hours-insufficient?`) better fits this vertical's own vocabulary, while the reuse is honestly documented, matching `holdco`/6420's "reused, renamed for domain fit" precedent |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8542/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8542/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8542/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"8542"`)
