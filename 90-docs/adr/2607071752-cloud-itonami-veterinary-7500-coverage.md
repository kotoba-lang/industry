# ADR-2607071752: `cloud-itonami-isic-7500` (veterinary activities) deepened to `:implemented` -- ninth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`, the first
  eight verticals built outside ADR-2607032000's original insurance/
  real-estate batch); ADR-2607032000 (the original batch, fully
  closed); `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/
  `6520`/`6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/
  `8530`/`9200` ADR-0001s (the governed-actor pattern this decision
  continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9200`, this ADR records the NINTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-7500` (veterinary activities, ISIC division 75) -- the first
  veterinary/animal-health vertical in this fleet, deliberately close
  in SHAPE to `8620`'s medical/dental practice (single actuation,
  contraindication screening) but genuinely distinct in regulatory
  grounding, and introducing a food-safety/temporal concept `8620` had
  no analog for.

## Problem

A veterinary practice's treatment-administration workflow bundles
several distinct concerns under one governed workflow:

1. **Jurisdiction veterinary-licensing correctness** -- an official
   spec-basis citation from a real veterinary-licensing authority,
   never fabricated.
2. **Contraindication safety** -- reuses `clinic.registry/treatment-
   contraindicated?`'s set-membership/conflict shape VERBATIM for the
   veterinary domain (the identical clinical-safety concept applies to
   animal patients as to human patients).
3. **Food-safety withdrawal sufficiency** -- a genuinely NEW real-
   world regulatory concept for this fleet: for a food-producing
   animal, does its own planned-harvest timeline leave enough time for
   a drug's required withdrawal period to fully elapse? Combines a
   MINIMUM-threshold pure-ground-truth recompute (`marketadmin.
   governor`'s/`registrar.governor`'s shape) with a type-tag gate
   (`accounting.governor`'s shape) in one check.
4. **Clinician credential currency** -- reuses the unconditional-
   evaluation discipline for a SEVENTH distinct grounding.
5. **Single actuation, with a lesson applied proactively** -- the
   withdrawal-period check guards on `:food-producing?` FIRST,
   deliberately informed by `6920`'s NullPointerException bug BEFORE
   writing any code -- not discovered as a bug afterward.

See `cloud-itonami-isic-7500`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-7500` gains **VetOps-LLM ⊣ Veterinary Care
   Governor** -- `veterinary.*` namespaces, modeled closely on all
   sixteen prior actors' Store/Registry/Governor/Phase/Advisor/
   Operation/Sim shape and the SAME generic langgraph-clj StateGraph.
2. `treatment-contraindicated?` is a direct, unmodified reuse of
   `clinic.governor/contraindicated-violations`'s set-membership/
   conflict shape -- the identical clinical-safety concept applies to
   animal patients as to human patients, so no reinvention was
   warranted.
3. `withdrawal-period-insufficient?`/`withdrawal-period-insufficient-
   violations` is the FIRST check in this fleet to model a food-
   safety/temporal-sufficiency concept, combining a MINIMUM-threshold
   pure-ground-truth recompute with a type-tag gate in ONE check --
   two previously-separate patterns in this fleet's shared vocabulary,
   combined for the first time.
4. The withdrawal-period check's implementation guards on
   `:food-producing?` FIRST (via `and`'s short-circuit), the SAME
   discipline `cloud-itonami-isic-6920`'s ADR-0001 documents as the
   fix for its own NullPointerException bug -- applied here PROACTIVELY
   before any code was written, the strongest demonstration yet that
   this fleet's documented lessons transfer.
5. Credential screening reuses the unconditional-evaluation discipline
   (`casualty.governor/sanctions-violations`'s original fix) for a
   SEVENTH distinct grounding in this fleet.
6. Single actuation event (`:actuation/administer-treatment`),
   matching `6511`'s/`6621`'s/`6629`'s/`6612`'s/`6492`'s/`7120`'s/
   `8620`'s single-actuation shape.
7. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"7500"`, fleet-wide maturity counts move from 22
   implemented / 75 blueprint / 546 spec to 23 implemented / 74
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
8. `test/veterinary/*` -- 33 tests / 139 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean lifecycle (a
   treatment-administration cycle for a food-producing animal with
   sufficient withdrawal margin) plus five HARD-hold cases (no
   spec-basis, a contraindicated treatment, an insufficient withdrawal
   period, a lapsed clinician license, a double administration) that
   never reach a human at all -- all correct on the FIRST demo run.

## Consequences

- (+) Veterinary/animal-health gets the same governed, auditable-actor
  treatment as the sixteen prior actors, and this fleet now has NINE
  concrete precedents for extending past ADR-2607032000's original
  scope, into genuinely different domains -- while proving the
  architecture generalizes across near-identical-shape domains (human
  vs. animal medicine) without becoming a copy-paste exercise.
- (+) `withdrawal-period-insufficient?`/`withdrawal-period-
  insufficient-violations` is a genuine structural contribution: the
  first check in this fleet to model a food-safety/temporal-
  sufficiency concept, combining two previously-separate check
  patterns (minimum-threshold recompute + type-tag gate) into one.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  veterinary/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern every sibling actor uses.
- (+) The `6920`-informed guard-the-type-tag-first discipline was
  applied PROACTIVELY here (before any code was written), the clearest
  evidence yet that this fleet's own documented lessons genuinely
  transfer across builds rather than only being cited after the fact.
- (+) Both the demo and the full test suite passed clean on the first
  run -- no bug this time, unlike `6492`/`6920`.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `veterinary.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `withdrawal-period-insufficient?` models only a single flat
  withdrawal-period figure per case, not a full withdrawal-interval/
  residue-testing regime -- see `cloud-itonami-isic-7500`'s own
  ADR-0001 and README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 23 actors now `:implemented` out of 643 total registry
  entries; 74 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All eight of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`; mixing a different ISIC division (75, distinct from those eight's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-7500` at `:blueprint` only | ❌ | The standing direction continues past `9200`; veterinary practice is a natural next domain that also introduces a genuinely new regulatory concept (food-safety withdrawal periods) this fleet had not yet modeled |
| See `cloud-itonami-isic-7500`'s own ADR-0001 Alternatives table for build-level decisions | -- | (whether to model withdrawal periods at all, withdrawal-regime scope, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`, first
  eight post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-7500/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
