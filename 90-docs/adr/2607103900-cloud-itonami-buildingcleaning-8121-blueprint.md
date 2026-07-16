# ADR-2607103900: cloud-itonami-isic-8121 (Community Building Cleaning Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607103800 (landscapeops/8130 -- twenty-fourth "author new
  blueprints from `:spec`" build)
- ADR-2607103500 (securitysystemsops/8020) and ADR-2607100500
  (securityops/8010) -- the two prior labor-dispatch-shape builds this
  one also reuses
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTY-FIFTH "author a new blueprint from `:spec` tier"
build. Continuing the portfolio-diversification discipline and the
labor-dispatch-shape services vein this window has developed
(`securityops`/8010, `securitysystemsops`/8020, `landscapeops`/8130),
this build extends that vein into general building-cleaning services.

### Candidate selection

`"8121"` (General cleaning of buildings) was selected: scoped to
routine janitorial and floor-care service for commercial and
institutional buildings (vacuuming, mopping, floor scrubbing/waxing,
restroom/common-area servicing, supply restocking). Deliberately
distinct from `"8129"` (other/specialized/industrial cleaning
activities, still `:spec`, untouched) and `cloud-itonami-isic-8130`
(landscape care, already published this window). Building-cleaning
carries its own safety/compliance regime (OSHA's Bloodborne Pathogens
Standard and Hazard Communication Standard for chemical handling;
ISSA's Cleaning Industry Management Standard as the common industry
certification framework; prevailing-wage/living-wage rules many
jurisdictions apply specifically to janitorial contracts on public
buildings), and a strong robotics-premise fit -- autonomous floor
scrubbing/vacuuming is one of the most naturally roboticized service
categories in commercial real-world practice today.

Redundancy screening, verified before selection: `grep -rli
"janitorial|building.clean|floor.clean|commercial.clean"` across
every `blueprint.edn`, `docs/business-model.md` and `README.md` in
the fleet returned completely empty. Governor keyword
`:building-cleaning-governor` grep-verified UNIQUE fleet-wide. The
legacy placeholder `:repo` (`gftdcojp/cloud-itonami-N8121`) was
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

### Decision 2: `:building-cleaning-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing general cleaning from specialized cleaning and landscape care

The README's own "Scope note" section directly names `cloud-itonami-
isic-8129` and `cloud-itonami-isic-8130` and explains the routine-vs-
specialized and building-vs-grounds distinctions, matching the
discipline established repeatedly this window.

### Decision 4: reuse the labor-dispatch operating-states shape, now FOUR sibling reuses

The registry's own pre-existing `:operating-states` for `"8121"`
(`:intake :register :match :dispatch :follow-up :audit`) is IDENTICAL
to `"8010"`'s, `"8020"`'s and `"8130"`'s own -- this build reuses that
shape verbatim, adapted for chemical-handling/access-scope concerns (a
chemical-handling task outside verified safety-data-sheet scope, an
access request outside verified building scope). This is now the
FOURTH sibling in the fleet reusing this exact shape.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-8121` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 130 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N8121"` to `"cloud-itonami-8121"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-8121` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"8121"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching crew-routing/scheduling optimization
needs), and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607103800`'s own `(is (= 24 (:blueprint m)))` assertion updated
to `(is (= 25 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"8121")))`) alongside the twenty-four prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 24 :spec 516 :total 646}`
  → `{:implemented 106 :blueprint 25 :spec 515 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 178
  assertions, all green.
- `cloud-itonami-isic-8121` joins the twenty-four prior fresh-blueprint
  picks as a twenty-fifth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- The labor-dispatch shape (`:intake :register :match :dispatch
  :follow-up :audit`) is now the fleet's most-reused pattern this
  window at four instances (`8010`/`8020`/`8130`/`8121`), edging past
  the booking/transit/delivery/reconciliation shape's five instances
  from the transport family.

## Alternatives considered

- **`"8129"` (other/specialized/industrial cleaning activities)**: the
  natural sibling of `"8121"`; set aside as a future low-friction
  sibling pick, following the same pattern established at
  `ADR-2607101600` (electrical/plumbing) and `ADR-2607102600` (TV/
  radio broadcasting).

## References

- `kotoba-lang/industry` registry entry `"8121"`.
- `cloud-itonami/cloud-itonami-isic-8121` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-8130/blueprint.edn` (the sibling shape this
  build reuses).
