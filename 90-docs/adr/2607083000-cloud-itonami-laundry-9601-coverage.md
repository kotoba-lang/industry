# ADR-2607083000: `cloud-itonami-isic-9601` (washing and dry-cleaning) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607082800 (`cloud-itonami-isic-8541`, sports and recreation education)
- ADR-2607082900 (`cloud-itonami-isic-8690`, other human health activities / allied health)
- `cloud-itonami-isic-9601/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-9601` publishes an OSS business blueprint for
washing and (dry-)cleaning of textile and fur products: laundry and
dry-cleaning services for customers. Like every prior vertical in
this fleet, the blueprint text alone is not an implementation — this
ADR records the governed-actor build that promotes `cloud-itonami-
isic-9601` from `:blueprint` to `:implemented` in the `kotoba-lang/
industry` registry, the forty-seventh vertical built outside
ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-9601`'s blueprint described a real-world
   laundry/dry-cleaning operating model (garment intake, care-plan
   assessment, solvent-handling-certification screening, cleaning-
   process application, garment return) but had no governed-actor
   implementation: no Store, no Governor, no rollout phasing, no
   tests.
2. The blueprint's own text names TWO real-world acts ("applying a
   cleaning process or returning a garment") — this build needed the
   dual-actuation-on-one-entity shape.
3. Garment care carries a genuine, real-world compliance concern
   distinct from every prior sibling: a garment's own care label
   names cleaning processes that must NOT be applied (e.g. "do not
   bleach") — a decision was needed on how to model this ground truth
   independently of the advisor's own proposal.
4. This is the first genuinely non-licensed-individual-professional
   domain in a long run of recent builds (nursing, sports, allied
   health) — a decision was needed on how to frame the reused
   "certification currency" concept honestly for an operator/
   facility-level environmental certification rather than an
   individual professional license.

## Decision

1. **Dual-actuation shape on one entity.** Matching `6512`/`6622`/
   `6520`/`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/
   `9102`/`9103`/`8890`/`8610`/`8510`/`9412`/`8720`/`8521`/`6619`/
   `3600`/`6190`/`3030`/`3830`/`9420`/`9491`/`2610`/`3512`/`8810`/
   `8691`/`8569`/`6419`'s shape, `high-stakes` is the two-member set
   `#{:actuation/apply-cleaning-process :actuation/return-garment}`,
   each with its own history collection, sequence counter, and
   dedicated double-actuation-guard boolean.
2. **Entity and op shape.** Primary entity `garment`. Five ops:
   `:garment/intake`, `:careplan/verify`, `:certification/screen`,
   `:actuation/apply-cleaning-process`, `:actuation/return-garment`.
3. **`cleaning-process-forbidden-by-care-label?` — a genuinely new
   check, the 6th set-membership/conflict instance.** Grep-verified
   absent from every prior sibling before this claim was finalized.
   Reuses `clinic.registry/treatment-contraindicated?`'s set-
   membership/conflict SHAPE with the SAME 'presence-in-forbidden-
   set' polarity `contraindicated?` uses -- returning to the original
   polarity (after `alliedhealth`'s inverse-polarity instance) for a
   new domain concept: a garment's own care label names forbidden
   cleaning processes. The 6th instance of the family overall
   (`clinic`/`veterinary`/`entertainment`/`nursing`/`alliedhealth`
   established the first five). Gates only `:actuation/apply-
   cleaning-process`.
4. **`certification-not-current` — a concept reuse renamed for this
   domain, the 45th unconditional-evaluation grounding.** The
   underlying SHAPE (credential/certification-currency, evaluated
   unconditionally so the screening op itself can HARD-hold on its
   own finding) is the same widely-established pattern this fleet
   uses; renamed `certification-not-current` (rather than
   `credential-not-current`) since laundry/dry-cleaning solvent-
   handling compliance is typically an operator/facility-level
   environmental certification, not an individual professional
   license -- an honest naming distinction, not a new discipline.
   Gates `:certification/screen` and both actuation ops.
5. **Dedicated double-actuation-guard booleans.**
   `:cleaning-applied?`/`:garment-returned?` on the `garment` record,
   never a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/laundry/store_contract_test.clj`. The per-entity accessor is
   safely named `garment` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:garment/
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

- Sixty-first actor in this fleet (60 implemented before this build).
- Confirms the set-membership/conflict check family generalizes to a
  6th instance, returning to the 'presence-in-forbidden-set' polarity
  for a new domain.
- Establishes a genuinely NEW check concept (care-label-forbidden
  cleaning process), grep-verified absent from every prior sibling.
- Extends the "concept reuse, honestly renamed for domain fit"
  discipline (`certification-not-current` vs. `credential-not-
  current`) as a distinct pattern from either pure novelty or pure
  literal reuse.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/laundry/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 60 → 61, `:blueprint` 24 → 23,
  `:spec` 546 unchanged, total 643.
- Test status: 36 tests / 179 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 109 assertions,
  all green, no pre-existing fixture referenced ISIC "9601".
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A single-actuation shape | The blueprint's own text names BOTH acts explicitly ("applying a cleaning process or returning a garment") |
| Reusing `credential-not-current` literally instead of renaming | Laundry/dry-cleaning solvent-handling compliance is typically an operator/facility-level environmental certification, not an individual professional license -- the domain-appropriate name keeps the distinction honest |
| Reusing the inverse ('absence-from-allowed-set') polarity `alliedhealth` established | Garment care labels conventionally name FORBIDDEN processes, not an allowed-processes whitelist -- the original polarity is the more natural mapping onto real care-label practice |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-9601/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9601/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-9601/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"9601"`)
