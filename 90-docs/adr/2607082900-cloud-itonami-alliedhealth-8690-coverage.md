# ADR-2607082900: `cloud-itonami-isic-8690` (other human health activities / allied health) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607082600 (`cloud-itonami-isic-8710`, residential nursing care facilities)
- ADR-2607082800 (`cloud-itonami-isic-8541`, sports and recreation education)
- `cloud-itonami-isic-8690/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-8690` publishes an OSS business blueprint for
other human health activities: allied-health services (physiotherapy,
chiropractic, optometry, ambulance and paramedical services) not
classified as hospital or physician practice. Like every prior
vertical in this fleet, the blueprint text alone is not an
implementation — this ADR records the governed-actor build that
promotes `cloud-itonami-isic-8690` from `:blueprint` to `:implemented`
in the `kotoba-lang/industry` registry, the forty-sixth vertical
built outside ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-8690`'s blueprint described a real-world
   allied-health operating model (encounter intake, assessment
   verification, credential screening, treatment-session
   administration) but had no governed-actor implementation: no
   Store, no Governor, no rollout phasing, no tests.
2. The blueprint's own text names only ONE real-world act
   ("administering a treatment session") — this build needed the
   single-actuation shape.
3. Allied health is the nearest-neighbor domain to `clinic`/8620
   (general medical/dental practice) already implemented in this
   fleet — a decision was needed on which of `clinic`'s established
   concepts to honestly reuse versus which distinctive, genuinely new
   check this vertical needed of its own, since allied-health
   professions (physiotherapy, chiropractic, etc.) carry a real-world
   regulatory concern general medical practice does not foreground in
   the same way: a strictly bounded scope of practice per profession.

## Decision

1. **Single-actuation shape.** Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`/`polling`/
   `research`/`design`/`sports`'s single-actuation shape, `high-
   stakes` is the one-member set `#{:actuation/administer-treatment-
   session}`.
2. **Entity and op shape.** Primary entity `encounter`, matching
   `clinic.store`'s own naming precedent for the same actor shape.
   Four ops: `:encounter/intake`, `:assessment/verify`, `:credential/
   screen`, `:actuation/administer-treatment-session` (high-stakes,
   named with the LATER `:actuation/verb-noun` convention rather than
   `clinic`'s older `:treatment/administer` op name, to match this
   fleet's more recent, consistent naming style).
3. **`treatment-outside-scope-of-practice?` — a genuinely new check,
   the 5th set-membership/conflict instance and 1st inverse-polarity
   instance.** Grep-verified absent from every prior sibling before
   this claim was finalized. Reuses `clinic.registry/treatment-
   contraindicated?`'s set-membership/conflict SHAPE (single item vs.
   a set) but with the OPPOSITE polarity: absence from an allowed set
   (rather than presence in a forbidden set) is the violation. The
   FIFTH instance of the family overall (`clinic`/`veterinary`/
   `entertainment`/`nursing` established the first four, all
   'presence-in-forbidden-set'), and the FIRST 'absence-from-allowed-
   set' instance. Gates only the actuation.
4. **`credential-not-current` — honestly reused (NOT new) as the 44th
   unconditional-evaluation grounding.** A LITERAL reuse of `clinic.
   governor/credential-not-current-violations`'s own concept, already
   reused by `hospital`/`eldercare`/`veterinary`/`nursing` and ~14
   other siblings. Gates `:credential/screen` and the actuation.
5. **Dedicated double-actuation-guard boolean.** `:treated?` on the
   `encounter` record, never a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/alliedhealth/store_contract_test.clj`. The per-entity
   accessor is safely named `encounter` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:encounter/
   intake}` only; the actuation is permanently excluded from every
   phase's `:auto` set.
8. **No bespoke domain capability lib** — this blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-specific
   capability beyond the generic stack.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed** — the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Sixtieth actor in this fleet (59 implemented before this build).
- Confirms the set-membership/conflict check family generalizes to a
  5th instance, and establishes its first inverse ('absence-from-
  allowed-set') polarity.
- Establishes a genuinely NEW check concept (scope-of-practice), grep-
  verified absent from every prior sibling.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/alliedhealth/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 59 → 60, `:blueprint` 25 → 24,
  `:spec` 546 unchanged, total 643.
- Test status: 30 tests / 133 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 108 assertions,
  all green, no pre-existing fixture referenced ISIC "8690".
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape | The blueprint's own text consistently names only ONE real-world act |
| Reusing `treatment-contraindicated?` instead of inventing scope-of-practice | Contraindication is a patient-specific medical-conflict concern (already established); scope-of-practice is a distinct regulatory-compliance concern -- conflating them would lose independent testability of each failure mode |
| Reusing `clinic`'s exact `:treatment/administer` op name | This fleet's naming convention has since converged on `:actuation/verb-noun` for both `:op` and `:stake` -- matching the newer convention keeps this build consistent with its immediate predecessors |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8690/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8690/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8690/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"8690"`)
