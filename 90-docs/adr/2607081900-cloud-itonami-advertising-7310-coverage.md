# ADR-2607081900: `cloud-itonami-isic-7310` (advertising) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607081600 (`cloud-itonami-isic-8691`, health access navigation)
- ADR-2607081700 (`cloud-itonami-isic-8569`, community learning support)
- ADR-2607081800 (`cloud-itonami-isic-6419`, community monetary intermediation)
- `cloud-itonami-isic-7310/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-7310` publishes an OSS business blueprint for
advertising: creating and placing advertising campaigns for clients
across media. Like every prior vertical in this fleet, the blueprint
text alone is not an implementation — this ADR records the governed-
actor build that promotes `cloud-itonami-isic-7310` from `:blueprint`
to `:implemented` in the `kotoba-lang/industry` registry, the
fortieth vertical built outside ADR-2607032000's original insurance/
real-estate batch.

## Problem

1. `cloud-itonami-isic-7310`'s blueprint described a real-world
   advertising-agency operating model (campaign intake, media-plan
   assessment, misleading-claim-risk screening, campaign placement)
   but had no governed-actor implementation: no Store, no Governor, no
   rollout phasing, no tests.
2. Unlike most recent siblings, the blueprint's own README,
   business-model.md and operator-guide.md consistently name only ONE
   real-world act ("placing/publishing a campaign on the client's
   behalf") — this build needed to decide whether to honor that
   single-actuation shape or force a second actuation not grounded in
   the text.
3. The blueprint's own Trust Controls name a concrete hold condition
   not seen under this exact name in any prior sibling: "a fabricated
   media-buy or misleading-claim risk forces a hold, not an override."

## Decision

1. **Single-actuation shape.** Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`'s single-actuation shape,
   `high-stakes` is the one-member set `#{:actuation/place-campaign}`.
2. **Entity and op shape.** Primary entity `campaign`. Four ops:
   `:campaign/intake`, `:media-plan/verify`, `:risk/screen`, and
   `:actuation/place-campaign` (high-stakes).
3. **`media-spend-exceeds-authorized-budget?` — 7th MAXIMUM-ceiling
   check.** Following `facility`, `school`, `card`, `recovery`,
   `care` and `navigator`, this applies the same ceiling-only
   comparison to a campaign's proposed media spend against its own
   recorded authorized budget, gating only `:actuation/place-
   campaign`.
4. **`misleading-claim-risk-unresolved-violations` — 38th
   unconditional-evaluation screening grounding, a genuinely new
   concept.** Every prior sibling's `governor.cljc` was grepped for
   "misleading" before this claim was finalized — one hit
   (`formation.governor`), read directly and confirmed to be an
   unrelated docstring mention about a misleading audit-trail record,
   not a misleading-advertising-claim concept. Grounded directly in
   the blueprint's own Trust Control text. Gates both `:risk/screen`
   and `:actuation/place-campaign`.
5. **Dedicated double-actuation-guard boolean.** `:campaign-placed?`
   on the `campaign` record, never a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/advertising/store_contract_test.clj`. The per-entity accessor
   is safely named `campaign` directly.
7. **Phase 0→3 rollout** — Phase 3 `:auto` set is `{:campaign/intake}`
   only; the actuation is permanently excluded from every phase's
   `:auto` set.
8. **No bespoke domain capability lib** — runs on the generic
   robotics/identity/forms/dmn/bpmn/audit-ledger stack.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed this time.** Unlike
    most prior promotions this window, this repo's `blueprint.edn`
    already had the correct `isic-` prefixed `:id` and correctly
    populated `:required-technologies`/`:optional-technologies`
    matching the `kotoba-lang/industry` registry's own entry exactly —
    confirmed by reading `blueprint.edn` before editing, rather than
    assuming a fix was needed. Only the `:maturity` field itself
    needed adding.

## Consequences

- Fifty-fourth actor in this fleet (53 implemented before this
  build).
- Confirms the MAXIMUM-ceiling check family generalizes to a
  seventh, genuinely distinct domain (media-budget authorization).
- Establishes a genuinely new unconditional-evaluation-screening
  concept (misleading-claim-risk), grep-verified absent from every
  prior sibling.
- `MemStore` ‖ `DatomicStore` parity proven by contract test.
- Fleet maturity: `:implemented` 53 → 54, `:blueprint` 31 → 30,
  `:spec` 546 unchanged, total 643.
- Test status: 30 tests / 127 assertions (leaner than dual-actuation
  builds, matching the single-actuation shape), lint clean, demo
  verified end-to-end.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape (e.g. a separate "publish creative asset" actuation) | The blueprint's own text consistently names only ONE real-world act — inventing a second would not be grounded in the blueprint's own text |
| A single "campaign-safety" check merging budget and misleading-claim concerns | Authorized-budget is a ground-truth numeric recompute; misleading-claim-risk status is an unconditionally-evaluated flag needing the screening op to self-hold — merging loses that property |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7310/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7310/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7310/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"7310"`)
