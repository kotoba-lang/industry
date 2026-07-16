# ADR-2607071654: `cloud-itonami-isic-8620` (medical and dental practice activities) deepened to `:implemented` -- sixth vertical outside the original batch

- Status: Accepted (2026-07-07)
- Related: ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640 (`6612`/`6492`/`6920`/`6611`/`7120`, the first five
  verticals built outside ADR-2607032000's original insurance/real-
  estate batch); ADR-2607032000 (the original batch, fully closed);
  `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/`6530`/
  `6820`/`6612`/`6492`/`6920`/`6611`/`7120` ADR-0001s (the governed-
  actor pattern this decision continues); langgraph-clj ADR-0001
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `7120`, this ADR records the SIXTH
  promotion outside ADR-2607032000's original batch: `cloud-itonami-
  isic-8620` (medical and dental practice activities, ISIC division
  86) -- the first healthcare vertical in this fleet, continuing the
  deliberate diversification beyond finance/insurance and
  professional/technical services that began with `7120`.

## Problem

`cloud-itonami-isic-8620` published a business/operator-model
blueprint (ClinicOps-LLM ⊣ Clinical Practice Governor, `:blueprint`
maturity) but had no governed actor implementation. Deepening it
required:

1. **Jurisdiction medical/dental-licensing correctness** -- an official
   spec-basis citation from a real licensing authority, never
   fabricated.
2. **Contraindication safety** -- a pure ground-truth recompute (the
   SAME shape `credit.governor`'s/`accounting.governor`'s/`marketadmin.
   governor`'s/`testlab.governor`'s checks establish), but the FIRST
   check in this fleet to be a SET-MEMBERSHIP/conflict test rather
   than an arithmetic comparison.
3. **Clinician credential currency** -- reuses the unconditional-
   evaluation discipline for a fourth distinct domain.
4. **Single actuation, with a dedicated-boolean guard design informed
   by four prior builds** -- administering a treatment, with the
   double-administration guard checking a dedicated `:treated?`
   boolean (informed by `6492`'s status-lifecycle bug and `6920`'s/
   `6611`'s/`7120`'s deliberate avoidance of it), not a `:status`
   value.

See `cloud-itonami-isic-8620`'s own `docs/adr/0001-architecture.md`
for the full design and distinctive checks (this superproject ADR
records the fleet-level context and registry/maturity bookkeeping; the
child repo's own ADR is the authoritative architecture record).

## Decision

1. `cloud-itonami-isic-8620` gains **ClinicOps-LLM ⊣ Clinical Practice
   Governor** -- `clinic.*` namespaces, modeled closely on all thirteen
   prior actors' Store/Registry/Governor/Phase/Advisor/Operation/Sim
   shape and the SAME generic langgraph-clj StateGraph.
2. `treatment-contraindicated?`/`contraindicated-violations` is the
   FIRST check in this fleet's shared vocabulary to be a set-
   membership/conflict test (does the proposed treatment appear in the
   patient's own recorded contraindication set) rather than every
   prior pure-ground-truth-recompute check's arithmetic comparison
   (threshold, range, or equality) -- proving the family generalizes
   beyond numeric comparisons entirely.
3. Credential screening reuses the unconditional-evaluation discipline
   (`casualty.governor/sanctions-violations`'s original fix) for a
   fourth distinct grounding in this fleet: a lapsed clinician license
   HARD-holds both the screening op itself and the treatment-
   administration op.
4. Double-administration guard checks a dedicated `:treated?` boolean,
   not `:status` -- the SAME design choice `accounting.governor`'s/
   `marketadmin.governor`'s/`testlab.governor`'s guards make, now
   applied correctly across a FOURTH consecutive build, each
   explicitly informed by `6492`'s status-lifecycle bug rather than
   re-derived by shape-analogy.
5. `kotoba-lang/industry`'s registry: `:maturity :blueprint` →
   `:implemented` for `"8620"`, fleet-wide maturity counts move from 19
   implemented / 78 blueprint / 546 spec to 20 implemented / 77
   blueprint / 546 spec (out of 643 total), `test/kotoba/industry_test.
   clj`'s `maturity-summary` assertion updated to match.
6. `test/clinic/*` -- 29 tests / 127 assertions, lint-clean, demo
   (`clojure -M:dev:run`) runs end-to-end: one clean lifecycle (a
   treatment-administration cycle) plus four HARD-hold cases (no
   spec-basis, a proposed treatment on the patient's own
   contraindication list, a lapsed clinician license, a double
   administration) that never reach a human at all -- all correct on
   the FIRST demo run.

## Consequences

- (+) Medical/dental practice gets the same governed, auditable-actor
  treatment as the thirteen prior actors, and this fleet now has SIX
  concrete precedents (`6612`, `6492`, `6920`, `6611`, `7120`, `8620`)
  for extending past ADR-2607032000's original scope, into genuinely
  different domains (finance, professional services, technical/
  scientific services, and now healthcare).
- (+) `treatment-contraindicated?`/`contraindicated-violations` is a
  genuine structural contribution to this fleet's shared vocabulary of
  governor-check shapes: the first set-membership/conflict check,
  proving the pure-ground-truth-recompute family generalizes beyond
  arithmetic comparisons entirely.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/clinic/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The dedicated-boolean double-actuation-guard lesson (from
  `6492`'s bug) has now been applied correctly BY DESIGN across FOUR
  consecutive builds (`6920`, `6611`, `7120`, `8620`), each explicitly
  citing the prior lesson rather than re-deriving it by pattern-
  matching.
- (+) Both the demo and the full test suite passed clean on the first
  run -- no bug this time, unlike `6492`/`6920`.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `clinic.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `treatment-contraindicated?` models only whether the proposed
  treatment itself is a member of the patient's own recorded
  contraindication set, not a full drug-interaction/allergy cross-
  reference database -- see `cloud-itonami-isic-8620`'s own ADR-0001
  and README coverage table for the full honest-scope accounting.
- Fleet-wide: 20 actors now `:implemented` out of 643 total registry
  entries; 77 remain `:blueprint`, 546 remain `:spec`-only. The next
  "pick a new ISIC blueprint vertical" firing remains free to select
  from ANY remaining `:blueprint`-tier `cloud-itonami-*` entry.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/ADR-2607071640 | ❌ | All five of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`; mixing a different ISIC division (86, vs. those five's 64/66/69/71) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-8620` at `:blueprint` only | ❌ | The standing direction continues past `7120`; medical/dental practice is a natural, well-precedented next domain, continuing the deliberate diversification into healthcare, a division this fleet had not yet touched |
| See `cloud-itonami-isic-8620`'s own ADR-0001 Alternatives table for build-level decisions | -- | (set-membership vs. proposal-based contraindication check, drug-interaction-database scope, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640 (`6612`/`6492`/`6920`/`6611`/`7120`, first five post-
  batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8620/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
