# ADR-2607104800: cloud-itonami-isic-7710 (Community Vehicle Rental Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104700 (touroperatorops/7912 -- thirty-third "author new
  blueprints from `:spec`" build, closed the travel low-friction-
  sibling pair)
- The petroleum-fleet's own `cloud-itonami-isic-4920` (road freight,
  `:implemented`) -- the carrier vertical this build distinguishes
  itself from
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the THIRTY-FOURTH "author a new blueprint from `:spec` tier"
build. Continuing the portfolio-diversification discipline
established at `ADR-2607103500`, this build moves away from the
just-closed travel family (`7911`/`7912`) into a fresh vehicle-rental
domain.

### Candidate selection

`"7710"` (Renting and leasing of motor vehicles) was selected: scoped
to self-drive rental of cars, vans and light trucks to consumers and
businesses, richly and independently regulated (recalled vehicles
must be repaired before re-rental in the US under the Raechel and
Jacqueline Houck Safe Rental Car Act of 2015, enforced by NHTSA; the
US Graves Amendment shields rental companies from vicarious-liability
claims arising solely from vehicle ownership; state-specific rental-
agreement consumer-protection and damage-waiver-disclosure statutes
also apply).

Distinct from `cloud-itonami-isic-4920` (road-freight, `:implemented`,
part of the petroleum-fleet build): that vertical moves goods aboard
its own vehicles under its own drivers, whereas a vehicle rental
company hands the vehicle to the RENTER, who drives it themselves,
and never itself performs carriage. Also distinct from
`cloud-itonami-unspsc-27` ("Independent Tool Fleet Rental &
Maintenance Robotics"), which rents construction TOOLS and equipment,
not motor vehicles.

Redundancy screening, verified before selection: `grep -rli "vehicle.
rental|car.rental|renting.and.leasing.of.motor"` across every
`blueprint.edn`, `docs/business-model.md` and `README.md` in the
fleet returned completely empty. Governor keyword
`:vehicle-rental-governor` grep-verified UNIQUE fleet-wide. The legacy
placeholder `:repo` (`gftdcojp/cloud-itonami-N7710`) was confirmed via
a direct GitHub API 404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:vehicle-rental-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing self-drive rental from carriage and tool rental

The README's own "Scope note" section directly names
`cloud-itonami-isic-4920` and `cloud-itonami-unspsc-27`, and explains
the renter-drives-vs-carrier-drives and vehicle-vs-tool distinctions
with specific regulatory citations, matching the discipline
established repeatedly this window.

### Decision 4: reuse the labor-dispatch operating-states shape

The registry's own pre-existing `:operating-states` for `"7710"`
(`:intake :register :match :dispatch :follow-up :audit`) matches the
SAME labor-dispatch shape reused across seven prior builds this
window -- this build reuses it verbatim, adapted for fleet-safety/
recall-compliance-scope concerns (a rental release outside verified
recall-compliance scope, a damage-waiver record without a completed
inspection).

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-7710` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 139 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N7710"` to `"cloud-itonami-7710"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-7710` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"7710"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching fleet-utilization/routing optimization
needs), and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104700`'s own `(is (= 33 (:blueprint m)))` assertion updated
to `(is (= 34 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"7710")))`) alongside the thirty-three prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 33 :spec 507 :total 646}`
  → `{:implemented 106 :blueprint 34 :spec 506 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 187
  assertions, all green.
- `cloud-itonami-isic-7710` joins the thirty-three prior fresh-
  blueprint picks as a thirty-fourth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- The labor-dispatch shape is now reused across EIGHT builds this
  window (`8010`/`8020`/`8130`/`8121`/`8220`/`8219`/`7911`/`7912`/
  `7710`), the fleet's most-reused pattern by a wide margin.

## Alternatives considered

- **`"7721"` (renting and leasing of recreational and sports goods)**
  and **`"7729"` (renting and leasing of other personal and household
  goods)**: comparable rental-family candidates; `"7710"` was
  preferred for its stronger, more concrete recall-compliance
  regulatory story (NHTSA Safe Rental Car Act).

## References

- `kotoba-lang/industry` registry entry `"7710"`.
- `cloud-itonami/cloud-itonami-isic-7710` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-7912/blueprint.edn` (the sibling shape this
  build reuses).
