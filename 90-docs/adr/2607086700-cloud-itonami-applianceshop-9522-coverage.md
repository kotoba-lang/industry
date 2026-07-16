# ADR-2607086700: cloud-itonami-isic-9522 (repair of household appliances) deepened to `:implemented`

## Status

Accepted

## Related

- ADR-2607071849 (`cloud-itonami-isic-9521`, repairshop — origin of the Repair Shop Governor / dual-actuation shape)
- ADR-2607086600 (`cloud-itonami-isic-9512`, commrepair — origin of the governor-name-reuse precedent)
- ADR-2607086100 / 2607086000 / 2607085900 / 2607085800 / 2607085700 (memberorg/9499, partyops/9492, sportsclub/9312, recreation/9329, sportsevent/9319 — the immediately preceding builds in this fleet-expansion run)
- ADR-2607032000 (`cloud-itonami-isic-6511`, the original life-insurance reference implementation)
- the full `:adr/related` chain in the companion `.edn` file (every prior deepened vertical this fleet)

## Context

`cloud-itonami-isic-9522` ("Repair of household appliances and home and
garden equipment") was a `:blueprint`-tier stub in the
`kotoba-lang/industry` registry. Per this repository's standing
authorization to pick a new ISIC blueprint vertical and promote it to
`:implemented`, this build was selected as the second confirmation of
the governor-name-reuse precedent `commrepair/9512`'s own ADR-0001
established: after the fleet-wide governor-name-collision survey
(`sportsclub/9312`'s methodology) reached exhaustion, `commrepair/9512`
established that sharing a governor name with an already-implemented
sibling is acceptable — not a naming error — when the underlying
business archetype is genuinely the same, provided the reuse is
documented and the new build brings its own genuinely differentiated,
well-grounded check.

`9522`'s own `:itonami.blueprint/governor` keyword,
`:repair-shop-governor`, is identical to `repairshop/9521`'s and
`commrepair/9512`'s own. All three cover the same "repair shop"
business archetype — diagnose, quote/assess, repair, return — applied
to a different category of repaired item each time (consumer
electronics for `repairshop/9521`, communication equipment for
`commrepair/9512`, household appliances and home/garden equipment for
`9522`).

## Decision

Build `applianceshop` (RepairOps-LLM ⊣ Repair Shop Governor) following
the exact governed-actor architecture established by
`cloud-itonami-isic-6511` and reused by every subsequent actor in this
fleet: `applianceshop.store` (Store protocol, MemStore + DatomicStore,
proven parity via `store-contract-test`), `applianceshop.registry`
(pure DRAFT-record construction, honest reuse of `parts-cost-matches-claim?`
and the safety-test-not-passed check from `repairshop`/`commrepair`),
`applianceshop.governor` (independent HARD-check compliance layer, a
new `refrigerant-handling-certification-unconfirmed?` check, and a
`high-stakes` actuation gate), `applianceshop.phase` (0→3 rollout
table), `applianceshop.repairopsllm` (mock+llm Advisor pair),
`applianceshop.operation` (langgraph StateGraph, generic shape copied
verbatim), and `applianceshop.sim` (demo driver).

The shape is dual-actuation
(`#{:actuation/complete-repair :actuation/return-appliance}`), mirroring
`repairshop/9521`'s own dual-actuation shape exactly, since this
blueprint's own text names two distinct real-world acts: completing a
repair and returning the appliance to the customer.

The one genuinely new HARD check —
`refrigerant-handling-certification-unconfirmed-violations` — is
grounded in real, distinguishing regulatory law rather than a
mechanical noun-swap: household appliances plausibly include
refrigerant-containing major appliances (refrigerators, freezers,
window/portable AC units) alongside this blueprint's own named
examples (washing machines, lawn mowers), and refrigerant-handling
technician certification is one of the longest-standing,
best-documented certification regimes in consumer-repair law:

- US: Clean Air Act Section 608 (40 CFR Part 82), enforced by the EPA
  since 1993 — technicians servicing appliances containing refrigerant
  must hold Section 608 certification.
- UK: The Fluorinated Greenhouse Gases Regulations 2015 (F-Gas
  Regulations), enforced by the Environment Agency.
- Germany: Chemikalien-Klimaschutzverordnung (ChemKlimaschutzV),
  enforced by regional Marktüberwachungsbehörden / Umweltbundesamt.
- Japan: フロン排出抑制法 (Act on Rational Use and Proper Management of
  Fluorocarbons), jointly administered by METI and the Ministry of the
  Environment.

This is the 62nd distinct application of the unconditional-evaluation
screening discipline in this fleet (most recently
`commrepair.governor/customer-data-consent-unconfirmed-violations` at
61st), grep-verified absent fleet-wide (`refrigerant`/`epa-608`/
`f-gas`/`フロン` all return zero hits in any prior sibling).

## Consequences

- `cloud-itonami-isic-9522` moves from `:blueprint` to `:implemented`
  in `kotoba-lang/industry`'s registry (fleet maturity: 77 → 78
  implemented, 8 → 7 blueprint).
- The governor-name-reuse precedent is now confirmed twice
  (`commrepair/9512`, `applianceshop/9522`), reinforcing it as a
  repeatable, well-grounded pattern rather than a one-off exception —
  future builds against the remaining repair-shop candidates
  (`9523`/`9524`/`9529`) may reuse `:repair-shop-governor` too,
  provided each brings its own genuinely differentiated check.
- 37 tests / 188 assertions pass in `applianceshop`; lint is clean;
  the demo (`clojure -M:dev:run`) walks one clean dual-actuation
  lifecycle plus five HARD-hold scenarios end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 125
  assertions) was re-run clean before committing the promotion.
- `manifest/west.yml`'s `industry` pin was advanced via the GitHub API
  single-entry-commit path and verified canonical via
  `nbb scripts/gen-west-manifest.cljs --entry industry`.

## Scope note

`:fleet-maturity-before`/`:fleet-maturity-after` in the companion
`.edn` reflect the actual observed sum of the three maturity tiers in
`registry.edn` at build time (630 total), not
`docs/cloud-itonami.md`'s separately-tracked "Total entries: 643"
figure — the same clarification made in every prior ADR this fleet
(2607085700 through 2607086600).

## Alternatives considered

- **Small-engine emissions certification** (for the lawn-mower /
  garden-equipment side of this blueprint) was considered as the
  distinguishing check instead of refrigerant handling, but rejected:
  small-engine emissions standards (e.g. US EPA Phase 3 small
  spark-ignition engine rules) govern manufacturing and sale, not
  repair practice — there is no equivalent technician-certification
  regime analogous to Section 608 on the repair side.
- **Declining the build and leaving 9522 blocked**, treating the
  original governor-name-collision survey as final. Rejected because
  `commrepair/9512`'s own ADR-0001 already established that the
  collision constraint was a self-imposed convention, not a structural
  requirement — reapplying that reasoning here confirms it generalizes
  rather than treating it as a one-off exception.

## References

- `cloud-itonami-isic-9522/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-9521/docs/adr/0001-architecture.md` (origin of
  the dual-actuation Repair Shop Governor shape)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md` (origin of
  the governor-name-reuse precedent)
- 40 CFR Part 82 Subpart F (US EPA Section 608 refrigerant recycling
  rule)
- UK Fluorinated Greenhouse Gases Regulations 2015
- Chemikalien-Klimaschutzverordnung (Germany)
- フロン排出抑制法 (Japan)
