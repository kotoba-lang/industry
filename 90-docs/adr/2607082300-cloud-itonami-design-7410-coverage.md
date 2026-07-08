# ADR-2607082300: `cloud-itonami-isic-7410` (specialized design activities) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607082100 (`cloud-itonami-isic-7320`, market research and public opinion polling)
- ADR-2607082200 (`cloud-itonami-isic-7210`, R&D on natural sciences and engineering)
- `cloud-itonami-isic-7410/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-7410` publishes an OSS business blueprint for
specialized design activities: fashion, industrial, graphic and
interior design services for clients. Like every prior vertical in
this fleet, the blueprint text alone is not an implementation — this
ADR records the governed-actor build that promotes
`cloud-itonami-isic-7410` from `:blueprint` to `:implemented` in the
`kotoba-lang/industry` registry, the forty-third vertical built
outside ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-7410`'s blueprint described a real-world
   design-studio operating model (project intake, design-professional-
   standards evidence assessment, IP/licensing-conflict screening,
   deliverable release) but had no governed-actor implementation: no
   Store, no Governor, no rollout phasing, no tests.
2. Matching `advertising`/7310's, `polling`/7320's and `research`/
   7210's precedent, the blueprint's own text consistently names only
   ONE real-world act ("releasing a final deliverable to a client") —
   this build needed to honor that single-actuation shape.
3. The blueprint's own Trust Controls name a concrete hold condition
   not seen under this exact concept in any prior sibling: "an
   IP/licensing conflict forces a hold, not an override."
4. A project's own deliverable could, in principle, include elements
   beyond what it is actually licensed to contain — a ground-truth
   recompute against the project's own recorded fields was needed, not
   a creative-merit judgment.

## Decision

1. **Single-actuation shape.** Matching `leasing`/`underwriting`/
   `testlab`/`clinic`/`veterinary`/`funeral`/`parksafety`/`salon`/
   `entertainment`/`facility`/`consulting`/`advertising`/`polling`/
   `research`'s single-actuation shape, `high-stakes` is the one-member
   set `#{:actuation/release-deliverable}`.
2. **Entity and op shape.** Primary entity `project`. Four ops:
   `:project/intake`, `:brief/verify`, `:risk/screen`, and
   `:actuation/release-deliverable` (high-stakes).
3. **`deliverable-scope-exceeded?` — 6th set-containment/subset
   check.** Following `registrar`/`casework`/`secondary` (1st-3rd,
   "sufficiency" polarity) and `consulting`/`congregation` (4th-5th,
   "permission/boundary" polarity), this recomputes `(not (set/subset?
   deliverable-elements licensed-scope-elements))` directly from the
   project's own recorded fields — the 6th instance overall, 3rd in
   the "permission/boundary" polarity. Gates only `:actuation/release-
   deliverable`.
4. **`ip-licensing-conflict-unresolved-violations` — 41st
   unconditional-evaluation screening grounding, a genuinely new
   concept.** Every prior sibling's governor/registry namespaces were
   grepped for "ip-licensing", "licensing-conflict" and "intellectual-
   property" before this claim was finalized — zero hits. Explicitly
   distinct from `adjustment`/`intermediation`/`brokerage`/
   `consulting`'s existing "conflict of interest" concept (a
   professional's divided loyalty, not a deliverable's own IP/
   licensing clearance). Grounded directly in the blueprint's own
   Trust Control text. Gates both `:risk/screen` and `:actuation/
   release-deliverable`.
5. **Dedicated double-actuation-guard boolean.**
   `:deliverable-released?` on the `project` record, never a single
   `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/design/store_contract_test.clj`. The per-entity accessor is
   safely named `project` directly.
7. **Phase 0→3 rollout** — Phase 3 `:auto` set is `{:project/intake}`
   only; the actuation is permanently excluded from every phase's
   `:auto` set.
8. **No bespoke domain capability lib at all.** Unlike `banking`
   (`:banking`/`:swift`) or `research`/`aerospace`/`fab` (`:cae`/
   `:eda`), this blueprint's own `:itonami.blueprint/required-
   technologies` names no domain-specific capability beyond the
   generic stack — there was no capability-lib decision to make.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed this time**, matching
    `advertising`/7310's, `polling`/7320's and `research`/7210's own
    experience — the `isic-` prefixed `:id` and `:required-
    technologies`/`:optional-technologies` already matched the
    `kotoba-lang/industry` registry exactly; only the `:maturity`
    field itself needed adding.

## Consequences

- Fifty-seventh actor in this fleet (56 implemented before this
  build).
- Confirms the set-containment/subset check family generalizes to a
  6th instance, and its "permission/boundary" polarity to a 3rd
  instance.
- Establishes a genuinely new unconditional-evaluation-screening
  concept (ip-licensing-conflict-unresolved), grep-verified absent
  from every prior sibling.
- `MemStore` ‖ `DatomicStore` parity proven by contract test.
- Fleet maturity: `:implemented` 56 → 57, `:blueprint` 28 → 27,
  `:spec` 546 unchanged, total 643.
- Test status: 30 tests / 128 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing (per the banking/6419-
  established discipline) — 7 tests / 105 assertions, all green, no
  pre-existing fixture referenced ISIC "7410".
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A dual-actuation shape (e.g. a separate "issue invoice" actuation) | The blueprint's own text consistently names only ONE real-world act — inventing a second would not be grounded in the blueprint's own text |
| Merging `deliverable-scope-exceeded?` and `ip-licensing-conflict-unresolved` into one check | The former is a ground-truth set-containment recompute; the latter is an unconditionally-evaluated flag needing the screening op to self-hold — merging loses that property |
| Reusing the existing "conflict of interest" concept for IP/licensing disputes | That concept is about a professional's divided loyalty between parties, not an unresolved IP/licensing clearance dispute on a deliverable's own content — a genuinely different failure mode, confirmed via grep to have zero prior instances |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7410/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7410/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7410/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"7410"`)
