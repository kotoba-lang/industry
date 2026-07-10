# ADR-2607104700: cloud-itonami-isic-7912 (Community Tour Operator Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104600 (travelagencyops/7911 -- thirty-second "author new
  blueprints from `:spec`" build, flagged this build as its own
  low-friction sibling)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the THIRTY-THIRD "author a new blueprint from `:spec` tier"
build, and a low-friction sibling pick following the pattern
established at `ADR-2607101600` (electrical/plumbing trades),
`ADR-2607102600` (TV/radio broadcasting), `ADR-2607104100` (machinery/
electronic repair) and `ADR-2607104300` (printing/print support):
`ADR-2607104600`'s own Consequences section explicitly flagged `"7912"`
as the natural next pick alongside `travelagencyops`/7911.

### Candidate selection

`"7912"` (Tour operator activities) was selected: scoped to
DESIGNING and ASSEMBLING package tours from multiple travel
components (flights, accommodation, transfers, excursions) sold
under the operator's own brand, bearing organizer liability --
distinct from `travelagencyops`/7911's own booking-intermediary
business bearing intermediary liability. The EU's Package Travel
Directive (2015/2302) makes this distinction explicit and imposes
package-organizer-specific obligations (insolvency protection for
prepaid amounts, liability for the proper performance of all travel
services in the package) that do not apply to a pure booking
intermediary. UK tour operators selling flight-inclusive packages
require ATOL bonding; several US states' Seller of Travel statutes
also distinguish package organizers from retail agents for
registration/bonding purposes.

Redundancy screening, verified before selection: `grep -rli "tour.
operator|package.tour|excursion"` across every `blueprint.edn`, `docs/
business-model.md` and `README.md` in the fleet found only
`travelagencyops`/7911's own README, which explicitly names `"7912"`
in its own "Scope note" section as the sibling distinction --
expected, not real overlap. Governor keyword `:tour-operator-governor`
grep-verified UNIQUE fleet-wide. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-N7912`) was confirmed via a direct GitHub API
404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:tour-operator-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword; distinct from `travelagencyops`/7911's own
`:travel-agency-governor`.

### Decision 3: explicit "Scope note" distinguishing package organizer from booking intermediary

The README's own "Scope note" section directly names `cloud-itonami-
isic-7911`, quotes its own intermediary-liability scope, and explains
the organizer-vs-intermediary liability distinction with specific
regulatory citations (EU Package Travel Directive, UK ATOL, US Seller
of Travel statutes), matching the discipline established repeatedly
this window.

### Decision 4: reuse the labor-dispatch operating-states shape

The registry's own pre-existing `:operating-states` for `"7912"`
(`:intake :register :match :dispatch :follow-up :audit`) is IDENTICAL
to `travelagencyops`/7911's own -- this build reuses that shape
verbatim, adapted for bonding/insolvency-protection-scope concerns (a
package released outside verified bonding scope, a component booking
without a completed availability/payment check).

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-7912` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 138 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N7912"` to `"cloud-itonami-7912"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-7912` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"7912"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching itinerary/component-scheduling
optimization needs), and its explicit `:maturity :spec` override
REMOVED so `maturity`/`maturity-of` now auto-derive `:blueprint` from
the real `:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104600`'s own `(is (= 32 (:blueprint m)))` assertion updated
to `(is (= 33 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"7912")))`) alongside the thirty-two prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 32 :spec 508 :total 646}`
  → `{:implemented 106 :blueprint 33 :spec 507 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 186
  assertions, all green.
- `cloud-itonami-isic-7912` joins the thirty-two prior fresh-blueprint
  picks as a thirty-third candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- Closes the travel-family low-friction-sibling pair (`7911`/`7912`),
  matching the same two-build cadence as `4321`/`4322`, `6020`/`6010`,
  `3312`/`3313` and `1811`/`1812`.

## Alternatives considered

- No alternative candidate was seriously considered for this build;
  `"7912"` was a direct, pre-flagged low-friction extension of the
  established travel pattern (`7911`), matching the same discipline
  used repeatedly this window.

## References

- `kotoba-lang/industry` registry entry `"7912"`.
- `cloud-itonami/cloud-itonami-isic-7912` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-7911/blueprint.edn` (the sibling shape this
  build reuses).
