# ADR-2607104600: cloud-itonami-isic-7911 (Community Travel Agency Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104500 (officesupportops/8219 -- thirty-first "author new
  blueprints from `:spec`" build)
- ADR-2607102100 (aviationops/5110), ADR-2607102200 (railops/4911),
  ADR-2607102800 (freightrailops/4912), ADR-2607102400
  (ferryops/5011) -- the transport-CARRIER verticals this build
  distinguishes itself from
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the THIRTY-SECOND "author a new blueprint from `:spec` tier"
build. Continuing the portfolio-diversification discipline
established at `ADR-2607103500`, this build moves away from the
labor-dispatch-shape services thread (`8010`/`8020`/`8130`/`8121`/
`8220`/`8219`) into a fresh travel/tourism domain.

### Candidate selection

`"7911"` (Travel agency activities) was selected: scoped to booking
and selling third-party travel products (flights, accommodation,
transfers) as an intermediary on behalf of travelers, richly and
independently regulated ("Seller of Travel" statutes in California,
Florida, Washington, Iowa and Hawaii requiring registration and often
bonding/trust-account arrangements; ARC accreditation for US agencies
issuing airline tickets, IATA accreditation internationally; the EU's
Package Travel Directive (2015/2302) explicitly distinguishing
retailer/intermediary liability from organizer liability).

Distinct from every transport-CARRIER vertical in this fleet
(`aviationops`/5110, `railops`/4911, `freightrailops`/4912,
`ferryops`/5011, and the marine-tanker/petroleum-fleet's own `5020`):
a travel agency books travel WITH those carriers on a traveler's
behalf, without operating any transport itself. Also distinct from
the natural sibling `"7912"` (Tour operator activities, still
`:spec`, untouched): a travel agent RETAILS third-party travel
products and bears intermediary liability, while a tour operator
DESIGNS and ASSEMBLES package tours under its own brand and bears
organizer liability -- the exact distinction the EU's Package Travel
Directive makes explicit.

Redundancy screening, verified before selection: `grep -rli "travel.
agency|tour.operator|travel.booking|excursion"` across every
`blueprint.edn`, `docs/business-model.md` and `README.md` in the
fleet returned completely empty. Governor keyword
`:travel-agency-governor` grep-verified UNIQUE fleet-wide. The legacy
placeholder `:repo` (`gftdcojp/cloud-itonami-N7911`) was confirmed via
a direct GitHub API 404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:travel-agency-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing booking intermediary from both carriers and the future tour-operator sibling

The README's own "Scope note" section directly names every relevant
carrier vertical and `cloud-itonami-isic-7912`, and explains the
booking-intermediary-vs-carriage and retailer-vs-organizer
distinctions with specific regulatory citations, matching the
discipline established repeatedly this window.

### Decision 4: reuse the labor-dispatch operating-states shape

The registry's own pre-existing `:operating-states` for `"7911"`
(`:intake :register :match :dispatch :follow-up :audit`) matches the
SAME labor-dispatch shape reused across six prior builds this
window -- this build reuses it verbatim, adapted for bonding/
consumer-protection-scope concerns (a booking outside verified
bonding/registration scope, a ticket issuance without a completed
payment-verification check).

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-7911` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 137 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N7911"` to `"cloud-itonami-7911"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-7911` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"7911"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching itinerary/routing optimization
needs), and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104500`'s own `(is (= 31 (:blueprint m)))` assertion updated
to `(is (= 32 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"7911")))`) alongside the thirty-one prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 31 :spec 509 :total 646}`
  → `{:implemented 106 :blueprint 32 :spec 508 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 185
  assertions, all green.
- `cloud-itonami-isic-7911` joins the thirty-one prior fresh-blueprint
  picks as a thirty-second candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- `"7912"` (tour operator) is now explicitly flagged as an available
  future low-friction sibling pick, matching the pattern established
  for `4321`/`4322`, `6020`/`6010`, `3312`/`3313` and `1811`/`1812`.

## Alternatives considered

- **`"7710"` (renting and leasing of motor vehicles)**: a comparable
  fresh-domain candidate; set aside in favor of `7911`'s richer,
  more concrete regulatory distinction against its own natural
  sibling (`7912`).

## References

- `kotoba-lang/industry` registry entry `"7911"`.
- `cloud-itonami/cloud-itonami-isic-7911` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-8219/blueprint.edn` (the sibling shape this
  build reuses).
