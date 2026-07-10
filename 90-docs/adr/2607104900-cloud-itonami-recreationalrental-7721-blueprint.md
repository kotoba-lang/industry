# ADR-2607104900: cloud-itonami-isic-7721 (Community Recreational and Sports Goods Rental Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104800 (vehiclerentalops/7710 -- thirty-fourth "author new
  blueprints from `:spec`" build, flagged this build as a comparable
  rental-family candidate)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the THIRTY-FIFTH "author a new blueprint from `:spec` tier"
build, following up on `ADR-2607104800`'s own Alternatives section,
which flagged `"7721"` as a comparable rental-family candidate.

### Candidate selection

`"7721"` (Renting and leasing of recreational and sports goods) was
selected: scoped to renting recreational/sports gear (skis, bicycles,
kayaks, camping equipment) to individual consumers, distinct from
`vehiclerentalops`/7710 (motor vehicles) and
`cloud-itonami-unspsc-27` ("Independent Tool Fleet Rental &
Maintenance Robotics", construction tools). Recreational-equipment
rental carries its own distinct compliance concerns: safety-critical
gear (ski bindings, climbing/watersports equipment) is subject to
product-specific safety standards (ASTM/DIN ski-binding standards,
CPSC bicycle safety standards); many jurisdictions have recreational-
activity liability statutes specifically addressing equipment-rental
waivers and assumption-of-risk disclosures (several US states have
ski-industry-specific liability limitation laws).

Redundancy screening, verified before selection: `grep -rli
"recreational.goods.rental|sports.goods.rental|equipment.rental"`
across every `blueprint.edn`, `docs/business-model.md` and `README.md`
in the fleet returned completely empty. Governor keyword
`:recreational-rental-governor` grep-verified UNIQUE fleet-wide. The
legacy placeholder `:repo` (`gftdcojp/cloud-itonami-N7721`) was
confirmed via a direct GitHub API 404 check to never have actually
existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:recreational-rental-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing recreational gear from vehicles and tools

The README's own "Scope note" section directly names
`cloud-itonami-isic-7710` and `cloud-itonami-unspsc-27`, and explains
the recreational-gear-vs-vehicle and recreational-gear-vs-tool
distinctions, matching the discipline established repeatedly this
window.

### Decision 4: reuse the labor-dispatch operating-states shape, now NINE sibling reuses

The registry's own pre-existing `:operating-states` for `"7721"`
(`:intake :register :match :dispatch :follow-up :audit`) is IDENTICAL
to the eight prior labor-dispatch builds this window -- this build
reuses that shape verbatim, adapted for safety-inspection/waiver-
scope concerns.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-7721` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 140 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N7721"` to `"cloud-itonami-7721"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-7721` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"7721"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching inventory/scheduling optimization
needs), and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104800`'s own `(is (= 34 (:blueprint m)))` assertion updated
to `(is (= 35 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"7721")))`) alongside the thirty-four prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 34 :spec 506 :total 646}`
  → `{:implemented 106 :blueprint 35 :spec 505 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 188
  assertions, all green.
- `cloud-itonami-isic-7721` joins the thirty-four prior fresh-
  blueprint picks as a thirty-fifth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- The labor-dispatch shape remains the fleet's most-reused pattern
  this window at nine instances.

## Alternatives considered

- **`"7729"` (renting and leasing of other personal and household
  goods)**: a comparable candidate (covering, among other things, the
  rent-to-own industry with its own distinctive Rental-Purchase
  Agreement Act regulatory regime in many US states); remains
  available for a future build.

## References

- `kotoba-lang/industry` registry entry `"7721"`.
- `cloud-itonami/cloud-itonami-isic-7721` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-7710/blueprint.edn` (the sibling shape this
  build reuses).
