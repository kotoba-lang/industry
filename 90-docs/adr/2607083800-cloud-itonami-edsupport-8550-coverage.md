# ADR-2607083800: `cloud-itonami-isic-8550` (educational support activities) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607083500 (`cloud-itonami-isic-7420`, photographic activities)
- ADR-2607083600 (`cloud-itonami-isic-9609`, other personal service activities n.e.c.)
- `cloud-itonami-isic-8550/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-8550` publishes an OSS business blueprint for
educational support activities: non-instructional support for
education such as educational testing, guidance counseling, and
student-exchange placement services. Like every prior vertical in
this fleet, the blueprint text alone is not an implementation — this
ADR records the governed-actor build that promotes `cloud-itonami-
isic-8550` from `:blueprint` to `:implemented` in the `kotoba-lang/
industry` registry, the fifty-first vertical built outside
ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-8550`'s blueprint described a real-world
   educational-support operating model (client intake, assessment
   verification, integrity/background-check screening, placement/
   referral finalization) but had no governed-actor implementation:
   no Store, no Governor, no rollout phasing, no tests.
2. The blueprint's own text names only ONE real-world act
   ("finalizing a placement or referral") — this build needed the
   single-actuation shape.
3. Educational testing/counseling carries a genuine, real-world
   compliance concern distinct from every prior sibling: test-
   administration integrity (proctoring/security) has no existing
   dedicated check in this fleet, despite this fleet already having
   47 prior unconditional-evaluation groundings for other concerns.
4. This fleet already has a "background check not cleared" concept
   (`school`/8510, reused by `sports`/8541 and `personalservice`/
   9609) — this build's own background-check screening needed to
   honestly characterize its own reuse rather than overclaim novelty.
5. During drafting, an early version of `edsupport.governor`'s ns
   docstring embedded literal, unescaped double-quote characters
   inside a Clojure string literal, breaking the reader — this needed
   catching before any test run, not discovering it via a mysterious
   test failure.

## Decision

1. **Single-actuation shape.** Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`/`polling`/
   `research`/`design`/`sports`/`alliedhealth`/`photo`/
   `personalservice`'s single-actuation shape, `high-stakes` is the
   one-member set `#{:actuation/finalize-placement}`.
2. **Entity and op shape.** Primary entity `client`. Five ops:
   `:client/intake`, `:assessment/verify`, `:integrity/screen`,
   `:background-check/screen`, `:actuation/finalize-placement`
   (high-stakes). Two screening ops (rather than the more common one)
   because this build introduces two independent unconditional-
   evaluation concerns.
3. **`assessment-administration-irregularity-unresolved-violations`
   — the 49th unconditional-evaluation grounding, a genuinely new
   concept.** Grep-verified absent (zero hits for "proctor"/"exam-
   integrity"/"test-security"/"testing-integrity" across every prior
   sibling). Grounded in AERA/APA/NCME's Standards for Educational and
   Psychological Testing (test-security provisions) and the real-
   world precedent of ETS's Office of Testing Integrity. Gates
   `:integrity/screen` and the actuation.
4. **`background-check-not-cleared-violations` — an honest FOURTH
   literal reuse, not claimed as new.** `school.governor` established
   this concept first; `sports.governor` reused it literally as the
   second instance; `personalservice.governor` as the third; this
   build's reuse is the fourth, the 50th distinct application of the
   unconditional-evaluation discipline overall. Grounded in real
   safeguarding law (UK's Keeping Children Safe in Education DBS-check
   mandate; Germany's erweitertes Führungszeugnis requirement). Gates
   `:background-check/screen` and the actuation.
5. **Dedicated double-actuation-guard boolean.** `:placement-
   finalized?` on the `client` record, never a single `:status`
   value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/edsupport/store_contract_test.clj`. The per-entity accessor
   is safely named `client` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:client/
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

- Sixty-fifth actor in this fleet (64 implemented before this build).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (assessment-administration-irregularity-unresolved),
  grep-verified absent from every prior sibling before the claim was
  finalized.
- Documents an honest FOURTH literal reuse of the background-check-
  not-cleared concept (school 1st, sports 2nd, personalservice 3rd,
  edsupport 4th), not claimed as new.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/edsupport/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 64 → 65, `:blueprint` 20 → 19,
  `:spec` 546 unchanged, total 643.
- Test status: 28 tests / 136 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 113 assertions,
  all green, no pre-existing fixture referenced ISIC "8550".
- A docstring authoring pitfall (embedded unescaped literal double
  quotes breaking a Clojure string) was caught via `clojure -M:lint`
  before any test run and fixed by rephrasing without embedded
  quotes — recorded as a build-process lesson for future siblings'
  long-form docstrings.
- This build's `deps.edn` used the CURRENT `kotoba-lang/langgraph`/
  `langchain` coordinates from the start (see `holdco`/6420's own
  ADR-0001 for the upstream-rename context this build inherited).
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A single combined screening op covering both concerns | The two concerns are independently groundable in different real-world regimes (test-security standards vs. student-safeguarding law); two separate dedicated ops more precisely follow the "screen the screening op directly" discipline |
| Reusing `care.registry/caregiver-workload-exceeds-maximum?`'s caseload-ratio shape for a counselor-caseload check | Would have added no new check-family instance; the testing-integrity concept was grep-verified absent fleet-wide and better serves this build's novelty goal |
| A dual-actuation shape (placement + referral as two acts) | The blueprint's own text consistently names only ONE real-world act |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8550/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8550/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8550/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"8550"`)
