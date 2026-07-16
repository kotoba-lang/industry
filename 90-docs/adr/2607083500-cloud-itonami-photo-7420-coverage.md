# ADR-2607083500: `cloud-itonami-isic-7420` (photographic activities) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607083000 (`cloud-itonami-isic-9601`, washing and dry-cleaning)
- ADR-2607083300 (`cloud-itonami-isic-6420`, activities of holding companies)
- `cloud-itonami-isic-7420/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-7420` publishes an OSS business blueprint for
photographic activities: portrait, commercial and event photography,
film processing, and related services. Like every prior vertical in
this fleet, the blueprint text alone is not an implementation — this
ADR records the governed-actor build that promotes `cloud-itonami-
isic-7420` from `:blueprint` to `:implemented` in the `kotoba-lang/
industry` registry, the forty-ninth vertical built outside
ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-7420`'s blueprint described a real-world
   photographic-studio operating model (engagement intake, shoot-plan
   assessment, guardian-consent screening, final-image-set delivery)
   but had no governed-actor implementation: no Store, no Governor,
   no rollout phasing, no tests.
2. The blueprint's own text names only ONE real-world act
   ("delivering/licensing a final image set to a client") — this
   build needed the single-actuation shape.
3. Photography carries a genuine, real-world compliance concern
   distinct from every prior sibling: delivering a commercial image
   set requires every photographed subject who needs one to have
   actually signed a model release — a coverage/sufficiency test,
   distinct from `design`/7410's IP-licensing-scope concept.
4. Photography of minors carries a heightened, distinctly regulated
   consent concern (guardian consent) — this fleet already uses
   "guardian-consent-record" as a generic evidence-checklist item
   (`learning`/8569), but no prior sibling has built a DEDICATED
   unconditional-evaluation check around it.

## Decision

1. **Single-actuation shape.** Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`/`polling`/
   `research`/`design`/`sports`/`alliedhealth`'s single-actuation
   shape, `high-stakes` is the one-member set `#{:actuation/deliver-
   image-set}`.
2. **Entity and op shape.** Primary entity `engagement`. Four ops:
   `:engagement/intake`, `:shootplan/verify`, `:consent/screen`,
   `:actuation/deliver-image-set` (high-stakes).
3. **`model-release-coverage-insufficient?` — the 7th set-
   containment/subset instance, 4th "sufficiency" polarity.** Grep-
   verified consistent with prior precedent. Following `registrar`/
   `casework`/`secondary` (1st-3rd, "sufficiency"), `consulting`/
   `congregation`/`design` (4th-6th, "permission/boundary"), this
   recomputes `(not (set/subset? subjects-requiring-release subjects-
   with-signed-release))` directly from the engagement's own recorded
   fields. Gates only `:actuation/deliver-image-set`.
4. **`minor-subject-guardian-consent-unresolved` — a genuinely new
   check, the 47th unconditional-evaluation grounding.** Grep-verified
   absent as a dedicated CHECK FUNCTION from every prior sibling
   (only a plain evidence-checklist STRING item exists elsewhere).
   Grounded in real image-rights law protecting minors (Germany's KUG
   §22-23, US state right-of-publicity statutes) and this blueprint's
   own Trust Control "a consent/model-release gap forces a hold, not
   an override." Gates `:consent/screen` and the actuation.
5. **Dedicated double-actuation-guard boolean.** `:image-set-
   delivered?` on the `engagement` record, never a single `:status`
   value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/photo/store_contract_test.clj`. The per-entity accessor is
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

- Sixty-third actor in this fleet (62 implemented before this build).
- Confirms the set-containment/subset check family generalizes to a
  7th instance, and its "sufficiency" polarity to a 4th instance.
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (minor-subject-guardian-consent-unresolved) as a dedicated
  check function, grep-verified previously present only as a generic
  evidence-checklist string.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/photo/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 62 → 63, `:blueprint` 22 → 21,
  `:spec` 546 unchanged, total 643.
- Test status: 30 tests / 134 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 111 assertions,
  all green, no pre-existing fixture referenced ISIC "7420".
- This build's `deps.edn` used the CURRENT `kotoba-lang/langgraph`/
  `langchain` coordinates from the start (see `holdco`/6420's own
  ADR-0001 for the upstream-rename context this build inherited).
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape | The blueprint's own text consistently names only ONE real-world act |
| Reusing `design`/7410's IP-licensing-conflict concept for model-release coverage | Model-release coverage is a subject-consent concern (does the photographed person's own consent cover this use), genuinely distinct from a deliverable's own IP/licensing-clearance conflict |
| Treating guardian-consent purely as a generic evidence-checklist item | This blueprint's own Trust Control text singles out "a consent/model-release gap" as forcing a hold -- a dedicated, unconditionally-evaluated check more precisely matches that emphasis |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7420/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7420/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7420/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"7420"`)
