# ADR-2607103300: cloud-itonami-isic-5221 (Community Land Transport Support Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607103200 (portauthorityops/5222 -- nineteenth "author new
  blueprints from `:spec`" build, third transport-support pick)
- ADR-2607103100 (airportops/5223) and ADR-2607102900
  (cargohandlingops/5224 -- opened the "transport support" family)
- ADR-2607102800 (freightrailops/4912) -- one of the CARRIER verticals
  this build distinguishes itself from
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTIETH "author a new blueprint from `:spec` tier"
build, and the FOURTH "transport support" pick, closing out the mode
trio (air, water, land) `ADR-2607102900` opened and `ADR-2607103100`/
`ADR-2607103200` extended into air and water respectively.

### Candidate selection

`"5221"` (Service activities incidental to land transportation) was
selected: a richly and independently regulated activity (toll-road
concessions under Japan's 道路整備特別措置法 Road Improvement Special
Measures Act; US state DOT toll-authority statutes and EU PPP
concession frameworks; state-licensed towing operators such as
California's Consumer Automotive Recovery Program or the UK's
DVSA-recognized recovery operators; bus terminal operators regulated
separately from bus carriers in most jurisdictions), with a natural
robotics-premise fit (toll-gantry/lane inspection, vehicle-recovery
dispatch support, terminal-berth monitoring).

This candidate required closer redundancy screening than most,
because `cloud-itonami-cofog-04.5` ("Independent Road & Bridge
Inspection Robotics") own docs explicitly list "toll-road and bridge
authorities" as one of its CUSTOMERS. Directly read before selection:
`cofog-04.5`'s own text describes a THIRD-PARTY inspection vendor that
SELLS pavement/bridge condition surveys to toll-road authorities
(among municipal public-works departments, engineering consultancies
and rural counties) -- it does not operate a toll road, bus terminal
or towing service itself. `"5221"` is scoped to the SEPARATE business
of actually OPERATING land-transport-support infrastructure and
services, matching the same "shared generic term, different business"
pattern validated twice already this scope (`ADR-2607102900`'s
`5020`/`5224` pair and `ADR-2607103100`'s `5110`/`5223` pair). Also
distinct from every CARRIER vertical this scope (`4911`, `4912`,
`4920`): land-transport support serves MULTIPLE carriers and
motorists at a fixed toll plaza/terminal/depot, not carriage between
two points. Governor keyword `:land-transport-support-governor`
grep-verified UNIQUE fleet-wide. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-H5221`) was confirmed via a direct GitHub API
404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:land-transport-support-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing this vertical from both carriers and a COFOG inspection vendor

Following the same discipline established repeatedly this scope: the
README's own "Scope note" section directly names `cloud-itonami-isic-
4911`/`4912`/`4920` (carriers) and `cloud-itonami-cofog-04.5`
(inspection vendor), and explains the value-chain/licensing
distinction against each, rather than merely asserting non-redundancy.

### Decision 4: reuse the booking/transit/delivery/reconciliation shape

The "Core Contract" and Trust Controls text follows the SAME framing
established across every transport-family build this scope
(`:intake :book :transit :deliver :reconcile :audit`, matching the
registry's own pre-existing `:operating-states` for `"5221"`),
reframed for toll/terminal/recovery concerns (a dispatch outside a
verified safety scope, a recovery job without a completed vehicle-
condition check, a terminal-slot record without verified evidence).

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-5221` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 125 prior/sibling actors, rather than the legacy
`H####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-H5221"` to `"cloud-itonami-5221"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-5221` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"5221"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching lane/terminal-slot optimization
needs), and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607103200`'s own `(is (= 19 (:blueprint m)))` assertion updated
to `(is (= 20 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"5221")))`) alongside the nineteen prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 19 :spec 521 :total 646}`
  → `{:implemented 106 :blueprint 20 :spec 520 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 173
  assertions, all green.
- `cloud-itonami-isic-5221` joins the nineteen prior fresh-blueprint
  picks as a twentieth candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- The "transport support" mode trio (air `5223`, water `5222`, land
  `5221`) plus cargo handling (`5224`, mode-agnostic) is now complete.
  Only `"5229"` (other transportation support activities) and
  `"5310"`/`"5320"` (postal/courier, still deliberately deferred per
  `ADR-2607102900`) remain in this specific family.
- Confirms the "shared generic term, different business" pattern
  generalizes to a THIRD pair (`cofog-04.5`/`5221`), now spanning two
  different distinguishing axes: carrier-vs-terminal-service
  (`5020`/`5224`, `5110`/`5223`) and vendor-vs-operator
  (`cofog-04.5`/`5221`).

## Alternatives considered

- **`"5229"` (other transportation support activities)**: a
  comparable candidate; set aside for a future build since it is the
  vaguest, catch-all member of this family and deserves independent
  scoping work rather than being rushed to complete the trio in this
  same pass.
- **`"5310"`/`"5320"` (postal/courier)**: still deferred per
  `ADR-2607102900`'s own reasoning.

## References

- `kotoba-lang/industry` registry entry `"5221"`.
- `cloud-itonami/cloud-itonami-isic-5221` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-5222/blueprint.edn` and `cloud-itonami-isic-5223/
  blueprint.edn` (the sibling shape and scope-note pattern this build
  reuses).
