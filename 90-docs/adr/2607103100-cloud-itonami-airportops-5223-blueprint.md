# ADR-2607103100: cloud-itonami-isic-5223 (Community Airport Operations and Ground Handling) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607102900 (cargohandlingops/5224 -- seventeenth "author new
  blueprints from `:spec`" build, opened the "transport support" family)
- ADR-2607102100 (aviationops/5110 -- the airline vertical this build
  distinguishes itself from)
- ADR-2607102800 (freightrailops/4912)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the EIGHTEENTH "author a new blueprint from `:spec` tier"
build, and the SECOND "transport support" pick following the family
`ADR-2607102900` opened with cargo handling. `ADR-2607102900`'s own
Consequences section explicitly named `"5221"`, `"5222"`, `"5223"`,
`"5229"`, `"5310"` and `"5320"` as untouched siblings in the same
transport-support family; this build picks up `"5223"`.

### Candidate selection

`"5223"` (Service activities incidental to air transportation) was
selected: a richly and independently regulated activity (ICAO Annex
14 aerodrome certification; the US FAA's Part 139 airport
certification; the EU's Regulation (EU) 139/2014 aerodrome-operations
rules and Directive 96/67/EC governing ground-handling market access;
Japan's 空港法 with Civil Aviation Bureau oversight; separately
certified air traffic control providers such as the UK's NATS under
CAA oversight, the US FAA's Air Traffic Organization, or Japan's own
ATC under JCAB), with a natural robotics-premise fit (runway/taxiway
inspection, baggage/cargo tugs, pushback, de-icing robots).

This candidate required closer redundancy screening than most, because
`cloud-itonami-isic-5110`'s own published docs (`README.md`, `docs/
business-model.md`) explicitly list "ground handling" as one of the
airline's OWN offerings. Directly read before selection: `5110`'s own
text uses "ground handling" only in the sense of an airline turning
around ITS OWN aircraft (an internal function of the carrier) --
matching the same "shared generic term, different business" pattern
already validated at `ADR-2607102900` (`5020`'s own "cargo handling"
mention vs. the separate `5224` terminal-service business). `"5223"`
is scoped to the SEPARATE, independently licensed business of airport
operation, ATC services and third-party ground handling serving
MULTIPLE airlines -- a fundamentally different, separately certified
business from any single airline. No `blueprint.edn`/governor-keyword
collision found (`airport-operations-governor`/`ground-handling-
governor`/`air-traffic-governor` all grep-empty fleet-wide). The
legacy placeholder `:repo` (`gftdcojp/cloud-itonami-H5223`) was
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

### Decision 2: `:airport-operations-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing this vertical from `aviationops`/5110's own ground-handling mention

Following the same discipline established at `ADR-2607101800` (`3510`
vs `3512`), `ADR-2607102000` (`6120` vs `6190`), `ADR-2607102400`
(`5011` vs `5020`) and `ADR-2607102900` (`5224` vs `5020`): the
README's own "Scope note" section directly names `cloud-itonami-isic-
5110`, quotes the specific overlapping term ("ground handling"), and
explains the value-chain/licensing distinction, rather than merely
asserting non-redundancy.

### Decision 4: reuse the booking/transit/delivery/reconciliation shape

The "Core Contract" and Trust Controls text follows the SAME framing
established across every transport-family build this scope
(`:intake :book :transit :deliver :reconcile :audit`, matching the
registry's own pre-existing `:operating-states` for `"5223"`),
reframed for airfield/ATC concerns (an airfield movement outside a
verified certification scope, a turnaround dispatched without a
completed safety inspection, an ATC clearance record without verified
evidence).

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-5223` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 123 prior/sibling actors, rather than the legacy
`H####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-H5223"` to `"cloud-itonami-5223"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-5223` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"5223"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching slot/turnaround-optimization needs),
and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607102900`'s own `(is (= 17 (:blueprint m)))` assertion updated
to `(is (= 18 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"5223")))`) alongside the seventeen prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 17 :spec 523 :total 646}`
  → `{:implemented 106 :blueprint 18 :spec 522 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 171
  assertions, all green.
- `cloud-itonami-isic-5223` joins the seventeen prior fresh-blueprint
  picks as an eighteenth candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- Confirms the "shared generic term, different business" pattern
  (established at `ADR-2607102900` for `5020`/`5224`) generalizes to a
  SECOND pair (`5110`/`5223`) -- a reusable discipline for the
  remaining transport-support siblings (`5221` land, `5222` water,
  `5229` other) which likely need the same treatment against their own
  carrier neighbors.

## Alternatives considered

- **`"5221"`/`"5222"` (service activities incidental to land/water
  transportation)**: comparable candidates in the same family; `"5223"`
  was chosen next because its regulatory grounding (airport/ATC
  certification) is unusually well-documented and its redundancy risk
  against `5110` was the most concrete to verify directly. The others
  remain available for future builds.
- **`"5310"`/`"5320"` (postal/courier)**: still deferred per
  `ADR-2607102900`'s own reasoning -- postal activities carry a
  distinct universal-service-obligation regulatory framing warranting
  separate careful treatment.

## References

- `kotoba-lang/industry` registry entry `"5223"`.
- `cloud-itonami/cloud-itonami-isic-5223` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-5224/blueprint.edn` (the sibling shape and
  scope-note pattern this build reuses).
