# ADR-2607083300: `cloud-itonami-isic-6420` (activities of holding companies) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607082900 (`cloud-itonami-isic-8690`, other human health activities / allied health)
- ADR-2607083000 (`cloud-itonami-isic-9601`, washing and dry-cleaning)
- `cloud-itonami-isic-6420/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-6420` publishes an OSS business blueprint for
holding-company administration: owning and managing controlling
equity interests in subsidiary companies without engaging in their
operations. Like every prior vertical in this fleet, the blueprint
text alone is not an implementation — this ADR records the governed-
actor build that promotes `cloud-itonami-isic-6420` from `:blueprint`
to `:implemented` in the `kotoba-lang/industry` registry, the forty-
eighth vertical built outside ADR-2607032000's original insurance/
real-estate batch.

## Problem

1. `cloud-itonami-isic-6420`'s blueprint described a real-world
   holding-company operating model (subsidiary equity-position
   intake, ownership-structure disclosure, beneficial-ownership
   screening, distribution disbursement, ownership-change recording)
   but had no governed-actor implementation: no Store, no Governor,
   no rollout phasing, no tests.
2. The blueprint's own text names TWO real-world acts ("disbursing a
   dividend/distribution or recording an ownership-structure change")
   — this build needed the dual-actuation-on-one-entity shape.
3. Holding-company distribution law carries a genuine, real-world
   compliance concern distinct from every prior sibling: a company
   may not legally disburse a distribution exceeding its own
   distributable reserves (a solvency/capital-maintenance test in
   corporate law).
4. Holding-company administration also carries a beneficial-ownership
   transparency concern, distinct from ordinary sanctions/AML
   screening already established in this fleet — confirming WHO
   ultimately owns/controls the entity, a genuinely different real-
   world regulatory requirement.

## Decision

1. **Dual-actuation shape on one entity.** Matching `6512`/`6622`/
   `6520`/`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/
   `9102`/`9103`/`8890`/`8610`/`8510`/`9412`/`8720`/`8521`/`6619`/
   `3600`/`6190`/`3030`/`3830`/`9420`/`9491`/`2610`/`3512`/`8810`/
   `8691`/`8569`/`6419`/`9601`'s shape, `high-stakes` is the two-
   member set `#{:actuation/disburse-distribution :actuation/record-
   ownership-change}`, each with its own history collection, sequence
   counter, and dedicated double-actuation-guard boolean.
2. **Entity and op shape.** Primary entity `position`. Five ops:
   `:position/intake`, `:disclosure/verify`, `:beneficial-ownership/
   screen`, `:actuation/disburse-distribution`, `:actuation/record-
   ownership-change`.
3. **`distribution-amount-exceeds-distributable-reserves?` — a
   genuinely new check, the 9th MAXIMUM-ceiling instance.** Grep-
   verified absent from every prior sibling before this claim was
   finalized. Following `facility`/`school`/`card`/`recovery`/`care`/
   `navigator`/`advertising`/`nursing` (1st-8th), this applies the
   same lo-bound-absent/hi-bound-only comparison to a position's own
   recorded proposed distribution amount against its own recorded
   distributable-reserves ceiling — grounded in real corporate-
   distribution law (Delaware GCL §170, UK Companies Act 2006 Part
   23, Japan Companies Act §461, Germany's AktG §57-58). Gates only
   `:actuation/disburse-distribution`.
4. **`beneficial-ownership-verification-unresolved` — a genuinely new
   check, the 46th unconditional-evaluation grounding.** Grep-
   verified absent from every prior sibling. Grounded in real
   beneficial-ownership-transparency law (US Corporate Transparency
   Act/FinCEN, UK PSC register, Germany's Transparenzregister).
   Explicitly distinct from `sanctions-violations` (counterparty
   sanctions-list screening). Gates `:beneficial-ownership/screen`
   and both actuation ops.
5. **Dedicated double-actuation-guard booleans.**
   `:distribution-disbursed?`/`:ownership-change-recorded?` on the
   `position` record, never a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/holdco/store_contract_test.clj`. The per-entity accessor is
   safely named `position` directly.
7. **Phase 0→3 rollout** — Phase 3's `:auto` set is `{:position/
   intake}` only; both actuations are permanently excluded from every
   phase's `:auto` set.
8. **No bespoke domain capability lib as a code dependency (despite
   blueprint.edn requiring `:securities`)** — matching `banking`/
   `research`/`aerospace`/`fab`'s own precedent, implementing the
   specific ground-truth checks directly rather than adding a real
   `kotoba-lang/securities` dependency.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **No `blueprint.edn` field-sync fixes needed** — the `isic-`
    prefixed `:id` and `:required-technologies`/`:optional-
    technologies` (including `:securities`) already matched the
    `kotoba-lang/industry` registry exactly; only the `:maturity`
    field itself needed adding.

## Consequences

- Sixty-second actor in this fleet (61 implemented before this
  build).
- Confirms the MAXIMUM-ceiling check family generalizes to a 9th
  instance, genuinely distinct domain (corporate distribution law).
- Establishes a genuinely NEW unconditional-evaluation-screening
  concept (beneficial-ownership-verification-unresolved), grep-
  verified absent from every prior sibling.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/holdco/
  store_contract_test.clj`.
- Fleet maturity: `:implemented` 61 → 62, `:blueprint` 23 → 22,
  `:spec` 546 unchanged, total 643.
- Test status: 36 tests / 181 assertions, lint clean, demo verified
  end-to-end.
- `kotoba-lang/industry`'s full test suite re-run with the local-root
  `technology` override before committing — 7 tests / 110 assertions,
  all green, no pre-existing fixture referenced ISIC "6420".
- **Fleet-wide finding (not fixed in this build):** this repo's
  `deps.edn` initially referenced stale `io.github.com-junkawasaki/
  langgraph-clj`/`langchain-clj` coordinates and `:local/root` paths.
  These projects were transferred and renamed upstream to
  `io.github.kotoba-lang/langgraph`/`langchain` (checked out at
  `orgs/kotoba-lang/langgraph`/`langchain`; internal namespaces
  unchanged). This repo's own `deps.edn` was corrected as part of
  this build, but the SAME stale reference likely exists in every
  prior `cloud-itonami-isic-*` sibling built before this transfer —
  a dedicated fleet-wide follow-up is warranted, out of scope for
  this single-vertical build. See child-repo ADR-0001 for detail.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| A single-actuation shape | The blueprint's own text names BOTH acts explicitly |
| Reusing `sanctions-violations` for the beneficial-ownership concept | Genuinely different regulatory concern (confirming ultimate ownership/control, not sanctions-list screening) |
| Adding `:securities` as a real backing library dependency | Matching `banking`/`research`/`aerospace`/`fab`'s own precedent, this scaffold is narrow enough that plain ground-truth checks suffice |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6420/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-6420/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-6420/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"6420"`)
