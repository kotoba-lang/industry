# ADR-2607071640: `cloud-itonami-isic-7120` (technical testing and analysis) deepened to `:implemented` -- fifth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618
  (`6612`/`6492`/`6920`/`6611`, the first four verticals built outside
  ADR-2607032000's original insurance/real-estate batch);
  ADR-2607032000 (the original batch, fully closed); `cloud-itonami-
  isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/`6820`/`6612`/
  `6492`/`6920`/`6611` ADR-0001s (the governed-actor pattern this
  decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `6612`/`6492`/`6920`/`6611`, this ADR
  records the FIFTH promotion outside ADR-2607032000's original batch:
  `cloud-itonami-isic-7120` (technical testing and analysis, ISIC
  division 71) -- the first technical-testing/professional-scientific-
  services vertical in this fleet, and a deliberate move out of the
  finance/insurance thread that had dominated the twelve prior builds.

## Problem

`cloud-itonami-isic-7120` published a business/operator-model
blueprint (TestLab-LLM ⊣ Test Integrity Governor, `:blueprint`
maturity) but had no governed actor implementation. Deepening it
required:

1. **Jurisdiction lab-accreditation/certification-standard
   correctness** -- an official spec-basis citation from a real
   accreditation body (NITE/A2LA/UKAS/DAkkS), never fabricated.
2. **Measurement-tolerance correctness** -- a pure ground-truth
   recompute (the SAME shape `credit.governor`'s/`accounting.
   governor`'s/`marketadmin.governor`'s checks establish), but the
   FIRST check in this fleet to combine a MINIMUM and a MAXIMUM bound
   in ONE comparison, rather than every prior check's single-direction
   threshold.
3. **Instrument-calibration currency** -- reuses the unconditional-
   evaluation discipline for a further application.
4. **Single actuation, with a dedicated-boolean guard design informed
   by three prior builds** -- issuing a certification, with the
   double-certification guard checking a dedicated `:certified?`
   boolean (informed by `6492`'s status-lifecycle bug and `6920`'s/
   `6611`'s deliberate avoidance of it), not a `:status` value.

See `cloud-itonami-isic-7120`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-7120` gains **TestLab-LLM ⊣ Test Integrity
   Governor** -- `testlab.*` namespaces, modeled closely on all twelve
   prior actors' Store/Registry/Governor/Phase/Advisor/Operation/Sim
   shape and the SAME generic langgraph-clj StateGraph.
2. `within-tolerance?`/`out-of-tolerance-violations` is the FIRST
   check in this fleet's shared vocabulary to combine a MINIMUM and a
   MAXIMUM bound in one comparison (`protocol-min <= measured-value <=
   protocol-max`) -- synthesizing `marketadmin.registry/listing-
   standard-met?`'s MINIMUM-only floor and `casualty.governor`'s/
   `realty.governor`'s/`credit.governor`'s MAXIMUM-only caps into one
   two-sided range check, proving the pure-ground-truth-recompute
   family generalizes to a combined-direction shape.
3. Calibration screening reuses the unconditional-evaluation
   discipline (`casualty.governor/sanctions-violations`'s original
   fix) for a further domain: a stale instrument calibration HARD-
   holds both the screening op itself and the certification-issuance
   op.
4. Double-certification guard checks a dedicated `:certified?`
   boolean, not `:status` -- the SAME design choice `accounting.
   governor`'s/`marketadmin.governor`'s guards make, now applied
   correctly across a THIRD consecutive build, each explicitly
   informed by `6492`'s status-lifecycle bug rather than re-derived by
   shape-analogy.
5. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"7120"`, fleet-wide maturity counts move from 18
   implemented / 79 blueprint / 546 spec to 19 implemented / 78
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
6. `test/testlab/*` -- 30 tests / 127 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean lifecycle (a
   certification-issuance cycle) plus five HARD-hold cases (no
   spec-basis, a measured value below its own protocol's tolerance
   range, a measured value above its own protocol's tolerance range, a
   stale instrument calibration, a double certification) that never
   reach a human at all -- all correct on the FIRST demo run.

## Consequences

- (+) Technical testing/analysis gets the same governed, auditable-
  actor treatment as the twelve prior actors, and this fleet now has
  FIVE concrete precedents (`6612`, `6492`, `6920`, `6611`, `7120`) for
  extending past ADR-2607032000's original scope, into genuinely
  different domains (finance, professional services, and now
  technical/scientific services).
- (+) `within-tolerance?`/`out-of-tolerance-violations` is a genuine
  structural contribution to this fleet's shared vocabulary of
  governor-check shapes: the first two-sided range check, proving the
  pure-ground-truth-recompute family generalizes beyond a single
  inequality direction.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/testlab/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The dedicated-boolean double-actuation-guard lesson (from
  `6492`'s bug) has now been applied correctly BY DESIGN across THREE
  consecutive builds (`6920`, `6611`, `7120`), each explicitly citing
  the prior lesson rather than re-deriving it by pattern-matching --
  the discipline this fleet's own ADR history says is necessary
  (`6492`'s own ADR-0001 documents a case where analogy alone was
  insufficient).
- (+) Both the demo and the full test suite passed clean on the first
  run -- no bug this time, unlike `6492`/`6920`.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `testlab.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `within-tolerance?` models only whether the point measured value
  itself falls within the protocol's own tolerance range, not a full
  measurement-uncertainty analysis -- see `cloud-itonami-isic-7120`'s
  own ADR-0001 and README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 19 actors now `:implemented` out of 643 total registry
  entries; 78 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618 | ❌ | All four of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`; mixing a different ISIC division (71, vs. those four's 64/66/69) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-7120` at `:blueprint` only | ❌ | The standing direction continues past `6612`/`6492`/`6920`/`6611`; technical testing/analysis is a natural, well-precedented next domain, and a deliberate diversification away from the finance/insurance thread this fleet had concentrated in for twelve consecutive builds |
| See `cloud-itonami-isic-7120`'s own ADR-0001 Alternatives table for build-level decisions | -- | (two-sided vs. two-separate-checks tolerance modeling, measurement-uncertainty scope, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618
  (`6612`/`6492`/`6920`/`6611`, first four post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-7120/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
