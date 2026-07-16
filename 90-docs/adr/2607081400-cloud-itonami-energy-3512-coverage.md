# ADR-2607081400: `cloud-itonami-isic-3512` (community renewable energy operations) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607080700 (`cloud-itonami-isic-6190`, telecom access)
- ADR-2607080800 (`cloud-itonami-isic-3030`, aerospace manufacturing)
- ADR-2607080900 (`cloud-itonami-isic-3830`, materials recovery)
- ADR-2607081000 (`cloud-itonami-isic-7020`, management consultancy)
- ADR-2607081100 (`cloud-itonami-isic-9420`, trade union)
- ADR-2607081200 (`cloud-itonami-isic-9491`, religious congregation)
- ADR-2607081300 (`cloud-itonami-isic-2610`, semiconductor fab)
- `cloud-itonami-isic-3512/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-3512` publishes an OSS business blueprint for
community renewable-energy operations: helping schools, cooperatives,
municipalities and small firms run solar/storage assets, meter energy
flows, forecast demand, and publish auditable community-benefit
reports. Like every prior vertical in this fleet, the blueprint text
alone is not an implementation — this ADR records the governed-actor
build that promotes `cloud-itonami-isic-3512` from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry, the
thirty-fifth vertical built outside ADR-2607032000's original
insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-3512`'s blueprint described a real-world
   distributed-energy-resource operating model (metering, dispatch
   recommendations, tariff/settlement reporting, community-benefit
   claims) but had no governed-actor implementation: no Store, no
   Governor, no rollout phasing, no tests.
2. The blueprint's own Trust Controls name four distinct hold
   conditions (fabricated grid-policy citation, incomplete evidence,
   out-of-range battery state-of-charge, unresolved grid-instability
   flag) and two high-stakes actuations (dispatch, settlement) —
   these needed a concrete, testable HARD-check mapping, not just
   prose.
3. `blueprint.edn` carried a stale pre-rename `:itonami.blueprint/id`
   (`"cloud-itonami-3512"`, missing the `isic-` infix) and was
   entirely missing `:required-technologies`/`:optional-technologies`,
   both needing to be reconciled against the `kotoba-lang/industry`
   registry's own stated values before the promotion could be
   considered internally consistent.
4. As the fleet's third infrastructure/utility vertical (after
   `3600`'s water-safety operations and `6190`'s telecom access), this
   build needed to demonstrate a genuinely distinct domain concern
   (grid-interconnected distributed-energy-resource safety) rather
   than reusing an existing infra vertical's shape wholesale.

## Decision

1. **Third infrastructure/utility vertical.** Following `3600` and
   `6190`, `cloud-itonami-isic-3512` distinguishes itself via
   grid-interconnected distributed-energy-resource operation: battery
   state-of-charge safety and grid-instability containment.
2. **Entity and op shape.** Primary entity `site`. Five ops:
   `:site/intake`, `:tariff/verify`, `:demand/screen`,
   `:actuation/dispatch-battery` (high-stakes), and
   `:actuation/finalize-settlement` (high-stakes) — a
   dual-actuation-on-one-entity shape grounded directly in the
   blueprint's own Core Contract and Trust Controls text.
3. **`battery-soc-out-of-range?` — 5th two-sided range check.**
   Following `testlab`, `conservation`, `water` and `aerospace`, this
   applies the same lo/hi-bounds comparison to a site's measured
   battery state-of-charge against its recorded safe operating range,
   gating only `:actuation/dispatch-battery`.
4. **`grid-instability-flag-unresolved-violations` — 33rd
   unconditional-evaluation screening grounding, genuinely new
   concept.** Grep-verified against every prior sibling's
   `governor.cljc` before the claim was finalized (zero hits for
   `grid-instability`/`curtailment`). Gates both `:demand/screen`
   (the screening op itself) and `:actuation/finalize-settlement`.
5. **Dedicated double-actuation-guard booleans.**
   `:battery-dispatched?`/`:settlement-finalized?` on the `site`
   record, never a single `:status` value.
6. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/energy/store_contract_test.clj`.
7. **Phase 0→3 rollout** — Phase 3 `:auto` set is `{:site/intake}`
   only; both actuations are permanently excluded from every phase's
   `:auto` set, enforced independently by both `energy.phase` and
   `energy.governor`'s `high-stakes` set.
8. **No bespoke domain capability lib** — runs on the generic
   robotics/telemetry/optimization/dmn/bpmn/audit-ledger/forms stack.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser so a malformed
   response degrades to a safe low-confidence noop.
10. **`blueprint.edn` field-sync fixes** — corrected the stale
    `:itonami.blueprint/id` and added the missing
    `:required-technologies`/`:optional-technologies` fields to match
    the `kotoba-lang/industry` registry's own entry for `"3512"`.

## Consequences

- Forty-ninth actor in this fleet (48 implemented before this build),
  and the third infrastructure/utility vertical.
- Confirms the two-sided range check family generalizes to a fifth,
  genuinely distinct domain (battery-management safety).
- Establishes a genuinely new unconditional-evaluation-screening
  concept (grid-instability-flag), grep-verified absent from every
  prior sibling.
- `MemStore` ‖ `DatomicStore` parity proven by contract test, the
  same `:db-api`-driven swap pattern every sibling actor uses.
- Two pre-existing `blueprint.edn` inconsistencies fixed as in-scope
  minor consistency work.
- Fleet maturity: `:implemented` 48 → 49, `:blueprint` 49 → 48,
  `:spec` 546 unchanged, total 643.
- Test status: 36 tests / 176 assertions, lint clean, demo verified
  end-to-end.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Single merged "grid-policy-violation" check for battery-SOC + grid-instability | Battery SOC is a ground-truth numeric recompute; grid-instability is an unconditionally-evaluated flag needing the screening op to self-hold — merging loses that property (same reasoning as `fab`'s yield-rate/process-defect distinction) |
| Model "public impact claims" as a third actuation | Folding the impact-claim concern into `:actuation/finalize-settlement`'s evidence-incomplete check captures the same Trust Control without inventing a third, less-established actuation shape |
| Reuse `3600`/`6190`'s existing infra-vertical shape wholesale | The battery-SOC/grid-instability concern is genuinely distinct from water-quality/threshold-breach and numbering/billing concerns — warrants its own checks, not a copy |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-3512/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-3512/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-3512/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"3512"`)
