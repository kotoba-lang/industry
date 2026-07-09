# ADR-2607101800: cloud-itonami-isic-3510 (Community Grid Transmission and Distribution Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607101600 (plumbingops/4322 -- eighth "author new blueprints
  from `:spec`" build)
- ADR-2607101500 (electricalops/4321)
- ADR-2607101400 (basicchemops/2011 -- where `"3510"` was first
  flagged and deferred)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the NINTH "author a new blueprint from `:spec` tier" build,
and resolves the longest-standing deferred candidate: `"3510"`
(Electric power generation, transmission and distribution) was first
flagged at `ADR-2607101400`, deferred again implicitly at
`ADR-2607101500` and `ADR-2607101600`, each time pending careful
distinguishing framing against `cloud-itonami-isic-3512`'s own
adjacent territory.

### Resolving the deferral: transmission/distribution, not generation

`cloud-itonami-isic-3512` ("Community Renewable Energy Operations",
`:implemented`, `:grid-policy-governor`) covers community-scale
electricity GENERATION -- a solar/wind cooperative. `"3510"`'s own
ISIC Rev.4 class text is broader ("generation, transmission AND
distribution"), which is exactly the redundancy risk flagged earlier.
This build resolves it by scoping the blueprint EXPLICITLY to the
transmission/distribution ("wires") side of the value chain --
substations, transmission lines, distribution feeders, interconnection
and wheeling-service management -- a business genuinely distinct from
generation in most real electricity markets (transmission/distribution
utilities are frequently separate regulated entities from generation
companies, especially in deregulated markets: FERC regulates
transmission federally in the US while generation is often merchant/
competitive; Japan's 電気事業法 separately licenses 送配電事業者 from 発電事業者;
the UK licenses DNOs -- Distribution Network Operators -- separately
from generators; Germany's EnWG separately regulates
Übertragungsnetzbetreiber/Verteilnetzbetreiber from Erzeuger). This is
a real, well-precedented distinction, not a forced one.

### A fourth distinct operating-states shape

`"3510"`'s own registry-declared operating-states (`:intake :observe
:recommend :dispatch :settle :audit`) is a FOURTH genuinely distinct
shape for this fleet's own `:spec`-authoring scope, after the services
shape (`securityops`/8010 etc.), the manufacturing shape
(`meddeviceops`/2660, `basicchemops`/2011) and the permit-based
construction/trade shape (`electricalops`/4321, `plumbingops`/4322).
This one is a grid-operations/market-settlement lifecycle: observe
telemetry -> recommend a dispatch action -> dispatch -> settle
(wheeling/interconnection charges) -- a structurally different
governed decision point (a dispatch that could violate a grid
reliability standard) from any prior pick.

The legacy placeholder `:repo` (`gftdcojp/cloud-itonami-D3510`) was
confirmed via a direct GitHub API 404 check to never have actually
existed. No bespoke capability library exists for grid transmission/
distribution specifically.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:grid-transmission-governor` -- grep-verified unique fleet-wide

Distinct from `cloud-itonami-isic-3512`'s own `:grid-policy-governor`
-- a deliberate choice not to reuse a similar-sounding keyword from a
directly adjacent vertical, to avoid confusion between the generation
and transmission/distribution businesses.

### Decision 3: explicit scope note distinguishing from generation

The README's own text carries an explicit "Scope note:
transmission/distribution, not generation" section naming
`cloud-itonami-isic-3512` directly and explaining the value-chain
distinction -- following the SAME discipline `foodserviceops`/5610
established when distinguishing itself from the existing accommodation
vertical, and `hazwasteops`/3812 established when distinguishing
itself from the two existing waste-adjacent verticals.

### Decision 4: grid-operations/market-settlement framing, a fourth genuinely new shape

The "Core Contract" and Trust Controls text follows this vertical's
own registry-declared operating-states directly (interconnection
request and telemetry observation, a dispatch recommendation, gated
dispatch, settlement) rather than reusing any of the three prior
shapes -- see the "A fourth distinct operating-states shape" section
above.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-3510` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 114 prior/sibling actors, rather than the legacy
`D####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-D3510"` to `"cloud-itonami-3510"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-3510` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"3510"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607101600`'s own `(is (= 8 (:blueprint m)))` assertion updated to
`(is (= 9 (:blueprint m)))`, continuing the "not a fixed invariant"
framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"3510")))`) alongside the eight prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 8 :spec 532 :total 646}`
  → `{:implemented 106 :blueprint 9 :spec 531 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 162
  assertions, all green.
- `cloud-itonami-isic-3510` joins the eight prior fresh-blueprint picks
  as a ninth candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- Confirms that a candidate deferred multiple rounds in a row can still
  be resolved cleanly once the right distinguishing framing is found --
  the deferral itself (rather than a rushed, under-justified pick)
  bought the time needed to arrive at a genuinely defensible scope.
- `"6120"` (wireless telecommunications) remains the LAST candidate
  still flagged as deferred from earlier rounds, pending its own
  careful redundancy-avoiding framing against the existing telecom
  verticals.

## Alternatives considered

- **Picking the full, unscoped `"3510"` ISIC class text (generation +
  transmission + distribution)**: rejected -- would have genuinely
  overlapped with `cloud-itonami-isic-3512`'s own generation scope.
  Scoping explicitly to transmission/distribution resolves this
  cleanly and matches real-world market structure.
- **Reusing `cloud-itonami-isic-3512`'s own `:grid-policy-governor`**:
  rejected -- these are distinct businesses (generation vs. wires) and
  should not share a governor identity.

## References

- `kotoba-lang/industry` registry entry `"3510"`.
- `cloud-itonami/cloud-itonami-isic-3510` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-3512/blueprint.edn` (confirmed the genuine
  distinction, not redundancy).
