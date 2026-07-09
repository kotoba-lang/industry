# ADR-2607104300: cloud-itonami-isic-1812 (Community Print Support Services Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104200 (printingops/1811 -- twenty-eighth "author new
  blueprints from `:spec`" build, flagged this build as its own
  low-friction sibling)
- ADR-2607102900 (cargohandlingops/5224 -- established the "service
  serving multiple X" pattern this build also uses)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTY-NINTH "author a new blueprint from `:spec` tier"
build, and a low-friction sibling pick following the pattern
established at `ADR-2607101600` (electrical/plumbing trades),
`ADR-2607102600` (TV/radio broadcasting) and `ADR-2607104100`
(machinery/electronic repair): `ADR-2607104200`'s own Consequences
section explicitly flagged `"1812"` as the natural next pick alongside
`printingops`/1811.

### Candidate selection

`"1812"` (Service activities related to printing) was selected:
scoped to independent pre-press services (plate-making, color
proofing) and independent post-press/bindery services (cutting,
folding, binding, laminating) that frequently serve MULTIPLE print
shops -- distinct from `printingops`/1811's own integrated in-house
press-production business. This mirrors the same "service serving
multiple X" value-chain pattern this fleet already established at
`ADR-2607102900` (cargo handling serving multiple carriers). Bindery/
cutting equipment carries its own well-documented machine-guarding
hazard category under OSHA and equivalent international workplace-
safety frameworks, distinct from press-line safety concerns; pre-press
proofing follows the same ISO 12647 process-control standards as
press production but applied at the proofing stage.

Redundancy screening, verified before selection: `grep -rli "pre.
press|plate.making|book.bind|print.finishing"` across every
`blueprint.edn`, `docs/business-model.md` and `README.md` in the
fleet found only `printingops`/1811's own README, which explicitly
names `"1812"` in its "Scope note" section as the sibling distinction
-- expected, not real overlap. Governor keyword
`:print-support-governor` grep-verified UNIQUE fleet-wide. The legacy
placeholder `:repo` (`gftdcojp/cloud-itonami-C1812`) was confirmed via
a direct GitHub API 404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:print-support-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword; distinct from `printingops`/1811's own
`:printing-governor`.

### Decision 3: explicit "Scope note" distinguishing independent print-support from integrated press production

The README's own "Scope note" section directly names `cloud-itonami-
isic-1811`, quotes its scope (integrated in-house press-production
business), and explains the independent-service-vs-in-house
distinction with a specific citation to the `5224` cargo-handling
precedent, matching the discipline established repeatedly this
window.

### Decision 4: reuse the manufacturing-lifecycle shape

The registry's own pre-existing `:operating-states` for `"1812"`
(`:spec :design :produce :inspect :package :audit`) matches the SAME
manufacturing-lifecycle shape used for `printingops`/1811 -- this
build reuses it verbatim, adapted for job-specification/equipment-
safety concerns.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-1812` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 134 prior/sibling actors, rather than the legacy
`C####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-C1812"` to `"cloud-itonami-1812"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-1812` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"1812"` registry entry had its `:repo` and
`:business-id` fields updated, and its explicit `:maturity :spec`
override REMOVED so `maturity`/`maturity-of` now auto-derive
`:blueprint` from the real `:repo` presence. `:optional-technologies`
was already empty and stays empty.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104200`'s own `(is (= 28 (:blueprint m)))` assertion updated
to `(is (= 29 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"1812")))`) alongside the twenty-eight prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 28 :spec 512 :total 646}`
  → `{:implemented 106 :blueprint 29 :spec 511 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 182
  assertions, all green.
- `cloud-itonami-isic-1812` joins the twenty-eight prior fresh-
  blueprint picks as a twenty-ninth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- Closes the printing-family low-friction-sibling pair (`1811`/`1812`),
  matching the same two-build cadence as `4321`/`4322` and `6020`/
  `6010`.

## Alternatives considered

- No alternative candidate was seriously considered for this build;
  `"1812"` was a direct, pre-flagged low-friction extension of the
  established printing pattern (`1811`), matching the same discipline
  used for `plumbingops`/4322 <- `electricalops`/4321,
  `radiobroadcastops`/6010 <- `tvbroadcastops`/6020, and
  `electronicrepairops`/3313 <- `machineryrepairops`/3312.

## References

- `kotoba-lang/industry` registry entry `"1812"`.
- `cloud-itonami/cloud-itonami-isic-1812` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-1811/blueprint.edn` (the sibling shape this
  build reuses).
