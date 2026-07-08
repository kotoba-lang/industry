# ADR-2607082200: `cloud-itonami-isic-7210` (R&D on natural sciences and engineering) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607081800 (`cloud-itonami-isic-6419`, community monetary intermediation)
- ADR-2607081900 (`cloud-itonami-isic-7310`, advertising)
- ADR-2607082100 (`cloud-itonami-isic-7320`, market research and public opinion polling)
- `cloud-itonami-isic-7210/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-7210` publishes an OSS business blueprint for
research and experimental development on natural sciences and
engineering: systematic creative work to increase scientific/
technical knowledge and its application. Like every prior vertical in
this fleet, the blueprint text alone is not an implementation — this
ADR records the governed-actor build that promotes
`cloud-itonami-isic-7210` from `:blueprint` to `:implemented` in the
`kotoba-lang/industry` registry, the forty-second vertical built
outside ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-7210`'s blueprint described a real-world R&D-
   lab operating model (study intake, protocol assessment, data-
   reproducibility-risk screening, findings-report publication) but
   had no governed-actor implementation: no Store, no Governor, no
   rollout phasing, no tests.
2. Matching `advertising`/7310's and `polling`/7320's precedent, the
   blueprint's own text consistently names only ONE real-world act
   ("publishing/submitting a findings report") — this build needed to
   honor that single-actuation shape.
3. The blueprint's own Trust Controls name a concrete hold condition
   not seen under this exact concept in any prior sibling:
   "fabricated or unreproducible data forces a hold, not an
   override."
4. The blueprint's own `:required-technologies` uniquely names `:cae`
   — a decision was needed on whether to add a real backing library
   dependency or follow the fleet's established self-contained
   convention (as `aerospace`/`fab` already did for their own `:cae`/
   `:eda` requirements).

## Decision

1. **Single-actuation shape.** Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`/`polling`'s
   single-actuation shape, `high-stakes` is the one-member set
   `#{:actuation/publish-findings-report}`.
2. **Entity and op shape.** Primary entity `study`. Four ops:
   `:study/intake`, `:protocol/verify`, `:risk/screen`, and
   `:actuation/publish-findings-report` (high-stakes).
3. **`replication-count-insufficient?` — 7th MINIMUM-threshold
   check.** Following `veterinary`, `funeral`, `hospital` (temporal)
   and `association`, `secondary`, `polling` (non-temporal
   generalizations), this applies the same minimum-floor comparison
   to a study's actual replication count against its own recorded
   minimum-required replication count, gating only `:actuation/
   publish-findings-report`.
4. **`data-reproducibility-risk-unresolved-violations` — 40th
   unconditional-evaluation screening grounding, a genuinely new
   concept.** Every prior sibling's governor/registry namespaces were
   grepped for "reproduc" and "replicat" before this claim was
   finalized — zero hits. Grounded directly in the blueprint's own
   Trust Control text. Gates both `:risk/screen` and `:actuation/
   publish-findings-report`.
5. **Dedicated double-actuation-guard boolean.**
   `:findings-report-published?` on the `study` record, never a
   single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/research/store_contract_test.clj`. The per-entity accessor is
   safely named `study` directly.
7. **Phase 0→3 rollout** — Phase 3 `:auto` set is `{:study/intake}`
   only; the actuation is permanently excluded from every phase's
   `:auto` set.
8. **No bespoke domain capability lib as a code dependency**, despite
   `blueprint.edn` naming `:cae` — following the same posture `banking`
   (`:banking`/`:swift`) and `aerospace`/`fab` (`:cae`/`:eda`) already
   established: implement the specific ground-truth check a governor
   needs directly rather than add an external dependency for a
   scaffold this narrow in scope.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed this time**,
    matching `advertising`/7310's and `polling`/7320's own experience
    — the `isic-` prefixed `:id` and `:required-technologies`/
    `:optional-technologies` (including `:cae`) already matched the
    `kotoba-lang/industry` registry exactly; only the `:maturity`
    field itself needed adding.

## Consequences

- Fifty-sixth actor in this fleet (55 implemented before this build).
- Confirms the MINIMUM-threshold sufficiency check family generalizes
  to a seventh instance, genuinely distinct domain (experimental
  reproducibility).
- Establishes a genuinely new unconditional-evaluation-screening
  concept (data-reproducibility-risk), grep-verified absent from
  every prior sibling.
- `MemStore` ‖ `DatomicStore` parity proven by contract test.
- Fleet maturity: `:implemented` 55 → 56, `:blueprint` 29 → 28,
  `:spec` 546 unchanged, total 643.
- Test status: 30 tests / 127 assertions (leaner than dual-actuation
  builds, matching the single-actuation shape), lint clean, demo
  verified end-to-end.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape (e.g. a separate "submit grant report" actuation) | The blueprint's own text consistently names only ONE real-world act — inventing a second would not be grounded in the blueprint's own text |
| A single "research-integrity" check merging replication-count and data-reproducibility-risk concerns | Replication-count is a ground-truth numeric recompute; data-reproducibility-risk status is an unconditionally-evaluated flag needing the screening op to self-hold — merging loses that property |
| Adding a `:cae` backing library as a real dependency | Matching `aerospace`/`fab`'s own precedent, this governed-actor scaffold is narrow enough that a plain numeric ground-truth check suffices; a production operator would add the library at the integration layer |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7210/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7210/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7210/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"7210"`)
