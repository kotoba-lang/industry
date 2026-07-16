# ADR-2607082600: `cloud-itonami-isic-8710` (residential nursing care facilities) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607082200 (`cloud-itonami-isic-7210`, R&D on natural sciences and engineering)
- ADR-2607082300 (`cloud-itonami-isic-7410`, specialized design activities)
- `cloud-itonami-isic-8710/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-8710` publishes an OSS business blueprint for
residential nursing care: combined nursing, supervisory and personal
care for residents requiring ongoing medical attention. Like every
prior vertical in this fleet, the blueprint text alone is not an
implementation — this ADR records the governed-actor build that
promotes `cloud-itonami-isic-8710` from `:blueprint` to `:implemented`
in the `kotoba-lang/industry` registry, the forty-fourth vertical
built outside ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-8710`'s blueprint described a real-world
   residential-nursing-care operating model (resident intake, care-
   plan evidence assessment, nursing-staff-credential screening,
   medication administration, incident-response finalization) but had
   no governed-actor implementation: no Store, no Governor, no
   rollout phasing, no tests.
2. The blueprint's own operator-guide text names TWO real-world acts
   ("no medication is administered and no incident response is
   finalized without human sign-off") — this build needed the dual-
   actuation-on-one-entity shape, matching the majority pattern in
   this fleet.
3. The blueprint's own Trust Controls name concrete hold conditions:
   a medication on a resident's own contraindication list, and a
   proposed dosage above their own recorded maximum — both needed
   ground-truth recomputes against the resident's own permanent
   fields, not proposal inspection.
4. Residential nursing care is the nearest neighbor to two already-
   implemented siblings (`clinic`/8620 and `hospital`/8610, plus
   `eldercare`/8730 and `veterinary`/7500) — a decision was needed on
   which of their established concepts to honestly REUSE versus which
   distinctive checks this vertical genuinely needed of its own.

## Decision

1. **Dual-actuation shape on one entity.** Matching `6512`/`6622`/
   `6520`/`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/
   `9102`/`9103`/`8890`/`8610`/`8510`/`9412`/`8720`/`8521`/`6619`/
   `3600`/`6190`/`3030`/`3830`/`9420`/`9491`/`2610`/`3512`/`8810`/
   `8691`/`8569`/`6419`'s shape, `high-stakes` is the two-member set
   `#{:actuation/administer-medication :actuation/finalize-incident-
   response}`, each with its own history collection, sequence
   counter, and dedicated double-actuation-guard boolean.
2. **Entity and op shape.** Primary entity `resident`. Five ops:
   `:resident/intake`, `:careplan/verify`, `:credential/screen`,
   `:actuation/administer-medication`, `:actuation/finalize-incident-
   response`.
3. **`medication-contraindicated?` — 3rd literal reuse of the set-
   membership/conflict "contraindicated" concept.** `clinic.registry/
   treatment-contraindicated?` established the 1st instance;
   `veterinary.registry/treatment-contraindicated?` reused it
   literally as the 2nd; `entertainment.governor/release-channel-
   restricted-violations` reused the shape under a different name as
   a 3rd instance of the broader family. `nursing.registry/
   medication-contraindicated?` is the 3rd literal reuse of
   "contraindicated" specifically — the same real-world failure mode
   genuinely recurs across every licensed-medical-care vertical, so
   reuse is honest, not a stretch. Gates only `:actuation/administer-
   medication`.
4. **`medication-dosage-exceeds-maximum?` — 8th MAXIMUM-ceiling
   check.** Following `facility`/`school`/`card`/`recovery`/`care`/
   `navigator`/`advertising` (1st-7th), this applies the same lo-
   bound-absent/hi-bound-only comparison to a resident's own recorded
   proposed dosage against their own recorded maximum-authorized
   dosage. Gates only `:actuation/administer-medication`.
5. **`credential-not-current` — honestly reused (NOT claimed new) as
   the 42nd unconditional-evaluation grounding.** This exact concept
   was already established by `clinic.governor/credential-not-
   current-violations` and reused by `hospital`/`eldercare`/
   `veterinary`/`conservation`/`museum`/`salon`/`entertainment`/
   `funeral`/`repairshop`/`registrar`/`wagering`/`facility`/
   `casework`/`parksafety` and others (~17 siblings, grep-verified).
   Grounded directly in this blueprint's own operator-guide text
   "licensed-professional sign-off required before any
   determination." Gates `:credential/screen` and both actuation
   ops.
6. **Dedicated double-actuation-guard booleans.**
   `:medication-administered?`/`:incident-response-finalized?` on the
   `resident` record, never a single `:status` value.
7. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/nursing/store_contract_test.clj`. The per-entity accessor is
   safely named `resident` directly.
8. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:resident/
   intake}` only; both actuations are permanently excluded from every
   phase's `:auto` set.
9. **No bespoke domain capability lib.** This blueprint's own
   `:itonami.blueprint/required-technologies` names no domain-
   specific capability beyond the generic stack.
10. **No `blueprint.edn` field-sync fixes needed** — the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` already matched the `kotoba-lang/industry` registry
    exactly; only the `:maturity` field itself needed adding.

## Consequences

- Fifty-eighth actor in this fleet (57 implemented before this
  build).
- Confirms the set-membership/conflict check family generalizes to a
  4th instance overall, and the literal "contraindicated" concept to
  a 3rd instance.
- Confirms the MAXIMUM-ceiling check family generalizes to an 8th
  instance.
- Honestly reuses (not "invents") the credential-not-current concept
  for the 42nd unconditional-evaluation grounding overall.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/nursing/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 57 → 58, `:blueprint` 27 → 26,
  `:spec` 546 unchanged, total 643.
- Test status: 39 tests / 191 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 106 assertions,
  all green, no pre-existing fixture referenced ISIC "8710".
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A single-actuation shape (only medication administration, or only incident-response finalization) | The blueprint's own operator-guide text names BOTH acts explicitly — omitting either would understate the blueprint's own scope |
| Inventing a new name for the credential-currency concept instead of reusing `credential-not-current` | This is genuinely the SAME concept already established by `clinic`/`hospital`/`eldercare`/`veterinary` and over a dozen siblings — inventing a new name would obscure honest reuse |
| Merging `medication-contraindicated?` and `medication-dosage-exceeds-maximum?` into one check | They are independent ground-truth recomputes over different field pairs (set membership vs. numeric ceiling) — merging would obscure which failure mode triggered a hold |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8710/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8710/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8710/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"8710"`)
