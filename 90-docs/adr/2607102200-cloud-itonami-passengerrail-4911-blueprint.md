# ADR-2607102200: cloud-itonami-isic-4911 (Community Passenger Rail Transport) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607102100 (aviationops/5110 -- eleventh "author new blueprints
  from `:spec`" build, first transport-mode pick)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWELFTH "author a new blueprint from `:spec` tier" build,
continuing the transport-mode family `ADR-2607102100` opened.
`ADR-2607102100`'s own Consequences section explicitly noted passenger
rail (`"4911"`) and passenger water transport (`"5011"`/`"5021"`) as
untouched candidates in the same family -- this build picks up
passenger rail.

### Candidate selection

`"4911"` (Passenger rail transport, interurban) was selected: a
universally well-known, richly regulated transport mode (rail safety
law -- Japan's 鉄道事業法 with MLIT oversight and post-JR福知山線 safety-
management-system reforms, the US's FRA under 49 CFR Parts 200-299,
the UK's ORR under the Railways and Other Guided Transport Systems
(Safety) Regulations 2006, Germany's EBA under the AEG), no redundancy
with any existing vertical (confirmed via grep -- no rail-related
governor keyword exists anywhere in the fleet), and a natural
robotics-premise fit (track inspection, rolling-stock maintenance,
signal-system testing robots). The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-H4911`) was confirmed via a direct GitHub API
404 check to never have actually existed. No bespoke capability
library exists for rail operations specifically.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:rail-safety-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: reuse the booking/transit/delivery/reconciliation shape established at ADR-2607102100

The "Core Contract" and Trust Controls text follows the SAME framing
`aviationops`/5110 established (`:intake :book :transit :deliver
:reconcile :audit`), adapted for rail-specific safety concerns (a
service dispatch outside a verified safety-management-system scope,
a maintenance release without inspection, rather than an out-of-
certificate flight dispatch) -- the same legitimate shape-reuse
precedent `plumbingops`/4322 and `aviationops`/5110 both established.

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-4911` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 117 prior/sibling actors, rather than the legacy
`H####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-H4911"` to `"cloud-itonami-4911"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-4911` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"4911"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607102100`'s own `(is (= 11 (:blueprint m)))` assertion updated
to `(is (= 12 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-
state corroboration assertion (`(is (= :blueprint (industry/maturity
"4911")))`) alongside the eleven prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 11 :spec 529 :total 646}`
  → `{:implemented 106 :blueprint 12 :spec 528 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 165
  assertions, all green.
- `cloud-itonami-isic-4911` joins the eleven prior fresh-blueprint
  picks as a twelfth candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- Passenger water transport (`"5011"`/`"5021"`) remains the last
  untouched candidate in the transport-mode family this pass and the
  prior one opened.

## Alternatives considered

- **Passenger water transport (`"5011"`/`"5021"`)**: a comparably
  strong candidate; set aside in favor of rail for this pass, remains
  available for a future build.

## References

- `kotoba-lang/industry` registry entry `"4911"`.
- `cloud-itonami/cloud-itonami-isic-4911` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-5110/blueprint.edn` (the sibling shape this
  build reuses).
