# ADR-2607103200: cloud-itonami-isic-5222 (Community Port Authority and Harbor Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607103100 (airportops/5223 -- eighteenth "author new
  blueprints from `:spec`" build, second transport-support pick)
- ADR-2607102900 (cargohandlingops/5224 -- opened the "transport
  support" family)
- ADR-2607102400 (ferryops/5011) and the petroleum-fleet's own
  ADR-2607100400 (registered `5020`, the marine tanker actor this
  build distinguishes itself from)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the NINETEENTH "author a new blueprint from `:spec` tier"
build, and the THIRD "transport support" pick, continuing the family
`ADR-2607102900` opened and `ADR-2607103100` extended into air. This
build extends it into water: port authority and harbor operations.

### Candidate selection

`"5222"` (Service activities incidental to water transportation) was
selected: a richly and independently regulated activity (IMO SOLAS
Chapter V governs vessel traffic services; the International
Convention on Salvage 1989 governs salvage operations; Japan's
港湾法 Port and Harbor Law licenses port authorities separately from
shipping lines and terminal operators; the UK's Harbours Act 1964 and
Port Marine Safety Code place statutory navigation-safety duties on
harbor authorities distinct from vessel owners; the US Ports and
Waterways Safety Act gives the Coast Guard Captain of the Port
authority over navigation safety independent of any carrier;
pilotage is frequently licensed as its own profession, e.g. the UK's
Pilotage Act 1987), with a natural robotics-premise fit (aids-to-
navigation inspection, berth/anchorage monitoring, buoy tending).

Redundancy screening, verified before selection: `grep -rli
"pilotage|harbor.master|port.authority|salvage|lighthouse"` across
every `blueprint.edn`, `docs/business-model.md` and `README.md` in
the fleet found exactly one hit -- `cloud-itonami-isic-6629`'s own
mention of "salvage deductions", an insurance general-average
accounting term, entirely unrelated to maritime salvage operations.
Directly read and confirmed non-redundant. Distinct from `cloud-
itonami-isic-5011`/`5020` (marine CARRIERS -- vessel operators moving
goods/people aboard their own vessel) and `cloud-itonami-isic-5224`
(cargo handling -- a terminal SERVICE that loads/unloads cargo on
behalf of multiple carriers): port authority operations are the
navigation-safety authority governing vessel movement, berthing and
pilotage within a port, independent of any single vessel operator or
cargo terminal. Governor keyword `:port-authority-governor`
grep-verified UNIQUE fleet-wide. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-H5222`) was confirmed via a direct GitHub
API 404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:port-authority-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing this vertical from both marine carriers and cargo handling

Following the same discipline established repeatedly this scope
(`ADR-2607101800`, `ADR-2607102000`, `ADR-2607102400`, `ADR-2607102900`,
`ADR-2607103100`): the README's own "Scope note" section directly
names both `cloud-itonami-isic-5011`/`5020` (carriers) and
`cloud-itonami-isic-5224` (cargo handling) and explains the
value-chain/licensing distinction against each, rather than merely
asserting non-redundancy against a single neighbor.

### Decision 4: reuse the booking/transit/delivery/reconciliation shape

The "Core Contract" and Trust Controls text follows the SAME framing
established across every transport-family build this scope
(`:intake :book :transit :deliver :reconcile :audit`, matching the
registry's own pre-existing `:operating-states` for `"5222"`),
reframed for navigation-safety concerns (a vessel movement outside a
verified navigation-safety scope, a pilotage assignment without a
completed fitness check, a salvage coordination record without
verified evidence).

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-5222` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 124 prior/sibling actors, rather than the legacy
`H####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-H5222"` to `"cloud-itonami-5222"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-5222` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"5222"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching berth-allocation/pilotage-scheduling
optimization needs), and its explicit `:maturity :spec` override
REMOVED so `maturity`/`maturity-of` now auto-derive `:blueprint` from
the real `:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607103100`'s own `(is (= 18 (:blueprint m)))` assertion updated
to `(is (= 19 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"5222")))`) alongside the eighteen prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 18 :spec 522 :total 646}`
  → `{:implemented 106 :blueprint 19 :spec 521 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 172
  assertions, all green.
- `cloud-itonami-isic-5222` joins the eighteen prior fresh-blueprint
  picks as a nineteenth candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- The "transport support" family now spans all three modes with a
  live example each: air (`5223`), water (`5222`) and cargo handling
  (`5224`, itself mode-agnostic). `"5221"` (land) and `"5229"` (other)
  remain the last untouched siblings in this specific family.

## Alternatives considered

- **`"5221"` (service activities incidental to land transportation)**:
  a comparable candidate in the same family; set aside for a future
  build so this pass could close out the water-transport gap next to
  the now-published air-transport sibling.
- **`"5310"`/`"5320"` (postal/courier)**: still deferred per
  `ADR-2607102900`'s own reasoning -- postal activities carry a
  distinct universal-service-obligation regulatory framing warranting
  separate careful treatment.

## References

- `kotoba-lang/industry` registry entry `"5222"`.
- `cloud-itonami/cloud-itonami-isic-5222` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-5223/blueprint.edn` and `cloud-itonami-isic-5224/
  blueprint.edn` (the sibling shape and scope-note pattern this build
  reuses).
