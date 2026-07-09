# ADR-2607102100: cloud-itonami-isic-5110 (Community Passenger Air Transport) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607102000 (mobilenetworkops/6120 -- tenth "author new
  blueprints from `:spec`" build, resolved the LAST flagged deferral)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the ELEVENTH "author a new blueprint from `:spec` tier" build,
and the FIRST in this scope with no flagged-deferral predecessor to
pick up -- `ADR-2607102000` confirmed every candidate previously
flagged as deferred (`"3812"`, `"3510"`, `"6120"`) had been resolved.
This build required a fresh candidate scan across the remaining
`:spec`-tier pool.

### Candidate selection

A fresh scan of remaining 4-digit-class `:spec`-tier entries, filtered
for service/professional/transport domains, surfaced several
untouched transport modes (passenger rail, water, air) since the
fleet's only prior transport pick (`freightops`/4920) covers road
freight specifically. `"5110"` (Passenger air transport) was selected:
a universally well-known, extremely well-regulated industry (aviation
safety law -- Japan's 航空法 with JCAB/MLIT oversight and Air Operator
Certificate requirements, the US's FAA under 14 CFR Part 121, the
UK's CAA Air Operator Certificate regime, Germany's LBA under EASA
regulation 965/2012), no redundancy with any existing transport
vertical (confirmed via grep across every `blueprint.edn` -- no
aviation/airline-related governor keyword exists), and a natural
robotics-premise fit (ground handling, baggage handling, maintenance-
inspection robots). The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-H5110`) was confirmed via a direct GitHub API
404 check to never have actually existed. No bespoke capability
library exists for airline operations specifically; `:logistics` is
required for the SAME booking/transit/delivery/reconciliation
contracts `freightops`/4920 and travel-agency-shaped verticals already
use.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:aviation-safety-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: booking/transit/delivery/reconciliation framing, reusing an established shape

The "Core Contract" and Trust Controls text follows this vertical's
own registry-declared operating-states directly (`:intake :book
:transit :deliver :reconcile :audit`), the SAME shape already used by
`freightops`/4920 and other logistics-flavored verticals -- a
legitimate reuse for a genuinely distinct regulatory domain (airline
certification vs. road-freight operations), matching the precedent
`investigationops`/8030 and `plumbingops`/4322 both established.

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-5110` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 116 prior/sibling actors, rather than the legacy
`H####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-H5110"` to `"cloud-itonami-5110"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-5110` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"5110"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607102000`'s own `(is (= 10 (:blueprint m)))` assertion updated
to `(is (= 11 (:blueprint m)))`, continuing the "not a fixed invariant"
framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"5110")))`) alongside the ten prior live-state corroboration tests and
the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 10 :spec 530 :total 646}`
  → `{:implemented 106 :blueprint 11 :spec 529 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 164
  assertions, all green.
- `cloud-itonami-isic-5110` joins the ten prior fresh-blueprint picks
  as an eleventh candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- Passenger rail (`"4911"`) and passenger water transport (`"5011"`/
  `"5021"`) remain untouched candidates for a future pass in the same
  transport-mode family.

## Alternatives considered

- **Passenger rail transport (`"4911"`)**: a comparably strong
  candidate (rail safety law is equally rich and well-known); set
  aside in favor of aviation for this pass, remains available for a
  future build.
- **Reusing `freightops`/4920's own governor identity**: rejected --
  passenger air transport and road freight are governed by entirely
  different certification regimes and should not share a governor.

## References

- `kotoba-lang/industry` registry entry `"5110"`.
- `cloud-itonami/cloud-itonami-isic-5110` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-4920/blueprint.edn` (confirmed no overlap with
  the existing road-freight vertical).
