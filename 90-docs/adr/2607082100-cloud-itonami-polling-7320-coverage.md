# ADR-2607082100: `cloud-itonami-isic-7320` (market research and public opinion polling) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607081700 (`cloud-itonami-isic-8569`, community learning support)
- ADR-2607081800 (`cloud-itonami-isic-6419`, community monetary intermediation)
- ADR-2607081900 (`cloud-itonami-isic-7310`, advertising)
- `cloud-itonami-isic-7320/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-7320` publishes an OSS business blueprint for
market research and public opinion polling: collecting, analyzing and
reporting on data about markets, consumer preferences and public
opinion. Like every prior vertical in this fleet, the blueprint text
alone is not an implementation — this ADR records the governed-actor
build that promotes `cloud-itonami-isic-7320` from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry, the forty-first
vertical built outside ADR-2607032000's original insurance/real-estate
batch.

## Problem

1. `cloud-itonami-isic-7320`'s blueprint described a real-world
   market-research operating model (survey intake, methodology
   assessment, unrepresentative-sample-risk screening, findings-report
   publication) but had no governed-actor implementation: no Store, no
   Governor, no rollout phasing, no tests.
2. Matching `advertising`/7310's precedent, the blueprint's own text
   consistently names only ONE real-world act ("publishing a findings
   report to a client or the public") — this build needed to honor
   that single-actuation shape.
3. The blueprint's own Trust Controls name a concrete hold condition
   not seen under this exact concept in any prior sibling: "a
   fabricated or unrepresentative sample forces a hold, not an
   override."

## Decision

1. **Single-actuation shape.** Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`'s single-
   actuation shape, `high-stakes` is the one-member set
   `#{:actuation/publish-findings-report}`.
2. **Entity and op shape.** Primary entity `survey`. Four ops:
   `:survey/intake`, `:methodology/verify`, `:risk/screen`, and
   `:actuation/publish-findings-report` (high-stakes).
3. **`sample-size-insufficient?` — 6th MINIMUM-threshold check.**
   Following `veterinary`, `funeral`, `hospital` (temporal) and
   `association`, `secondary` (non-temporal generalizations), this
   applies the same minimum-floor comparison to a survey's actual
   sample size against its own recorded minimum-required sample size,
   gating only `:actuation/publish-findings-report`.
4. **`unrepresentative-sample-risk-unresolved-violations` — 39th
   unconditional-evaluation screening grounding, a genuinely new
   concept.** Every prior sibling's governor/registry namespaces were
   grepped for "unrepresentative", "sample-size" and
   "representativeness" before this claim was finalized — zero hits.
   Grounded directly in the blueprint's own Trust Control text. Gates
   both `:risk/screen` and `:actuation/publish-findings-report`.
5. **Dedicated double-actuation-guard boolean.**
   `:findings-report-published?` on the `survey` record, never a
   single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/polling/store_contract_test.clj`. The per-entity accessor is
   safely named `survey` directly.
7. **Phase 0→3 rollout** — Phase 3 `:auto` set is `{:survey/intake}`
   only; the actuation is permanently excluded from every phase's
   `:auto` set.
8. **No bespoke domain capability lib** — runs on the generic
   robotics/identity/forms/dmn/bpmn/audit-ledger stack.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed this time**,
    matching `advertising`/7310's own experience — the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Fifty-fifth actor in this fleet (54 implemented before this build).
- Confirms the MINIMUM-threshold sufficiency check family generalizes
  to a sixth instance, genuinely distinct domain (statistical sample
  validity).
- Establishes a genuinely new unconditional-evaluation-screening
  concept (unrepresentative-sample-risk), grep-verified absent from
  every prior sibling.
- `MemStore` ‖ `DatomicStore` parity proven by contract test.
- Fleet maturity: `:implemented` 54 → 55, `:blueprint` 30 → 29,
  `:spec` 546 unchanged, total 643.
- Test status: 30 tests / 127 assertions (leaner than dual-actuation
  builds, matching the single-actuation shape), lint clean, demo
  verified end-to-end.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape (e.g. a separate "release raw dataset" actuation) | The blueprint's own text consistently names only ONE real-world act — inventing a second would not be grounded in the blueprint's own text |
| A single "survey-integrity" check merging sample-size and unrepresentative-sample-risk concerns | Sample-size is a ground-truth numeric recompute; unrepresentative-sample-risk status is an unconditionally-evaluated flag needing the screening op to self-hold — merging loses that property |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7320/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7320/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7320/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"7320"`)
