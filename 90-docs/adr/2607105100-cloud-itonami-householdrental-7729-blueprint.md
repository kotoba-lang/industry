# ADR-2607105100: cloud-itonami-isic-7729 (Community Household Goods Rental Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104900 (recreationalrentalops/7721 -- thirty-fifth "author
  new blueprints from `:spec`" build, flagged this build as a
  comparable rental-family candidate)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the THIRTY-SIXTH "author a new blueprint from `:spec` tier"
build, following up on `ADR-2607104900`'s own Alternatives section,
which flagged `"7729"` as a comparable rental-family candidate.

### Candidate selection

`"7729"` (Renting and leasing of other personal and household goods)
was selected: scoped to personal/household goods (furniture,
appliances, consumer electronics) rented to individual consumers,
frequently under a rent-to-own structure -- distinct from
`vehiclerentalops`/7710 (motor vehicles), `recreationalrentalops`/7721
(recreational/sports goods) and `cloud-itonami-unspsc-27`
(construction tools). Rent-to-own carries its own distinct legal
regime, separate from ordinary consumer credit/lending law: most US
states have a specific Rental-Purchase Agreement Act governing
rent-to-own transactions as a series of short-term rentals with a
purchase option rather than as an installment loan, and the US
Federal Trade Commission has issued specific guidance requiring
total-cost-of-ownership disclosure for rent-to-own agreements.

Redundancy screening, verified before selection: `grep -rli "rent.to.
own|rental.purchase|household.goods.rental|furniture.rental"` across
every `blueprint.edn`, `docs/business-model.md` and `README.md` in
the fleet returned completely empty. Governor keyword
`:household-rental-governor` grep-verified UNIQUE fleet-wide. The
legacy placeholder `:repo` (`gftdcojp/cloud-itonami-N7729`) was
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

### Decision 2: `:household-rental-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing household goods from vehicles, recreational gear and tools

The README's own "Scope note" section directly names
`cloud-itonami-isic-7710`, `cloud-itonami-isic-7721` and
`cloud-itonami-unspsc-27`, and explains the household-goods
distinction with specific citations to Rental-Purchase Agreement Act
regimes and FTC guidance, matching the discipline established
repeatedly this window.

### Decision 4: reuse the labor-dispatch operating-states shape, now TEN sibling reuses

The registry's own pre-existing `:operating-states` for `"7729"`
(`:intake :register :match :dispatch :follow-up :audit`) is IDENTICAL
to the nine prior labor-dispatch builds this window -- this build
reuses that shape verbatim, adapted for rental-purchase-agreement/
disclosure-scope concerns.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-7729` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 141 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N7729"` to `"cloud-itonami-7729"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-7729` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"7729"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching delivery/pickup scheduling
optimization needs), and its explicit `:maturity :spec` override
REMOVED so `maturity`/`maturity-of` now auto-derive `:blueprint` from
the real `:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104900`'s own `(is (= 35 (:blueprint m)))` assertion updated
to `(is (= 36 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"7729")))`) alongside the thirty-five prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 35 :spec 505 :total 646}`
  → `{:implemented 106 :blueprint 36 :spec 504 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 189
  assertions, all green.
- `cloud-itonami-isic-7729` joins the thirty-five prior fresh-
  blueprint picks as a thirty-sixth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- The labor-dispatch shape remains the fleet's most-reused pattern
  this window at ten instances, and closes out the rental-family
  trio (`7710`/`7721`/`7729`) opened at `ADR-2607104800`.

## Alternatives considered

- No other candidate was seriously considered for this build;
  `"7729"` was a direct, pre-flagged extension of the rental family
  established this window.

## References

- `kotoba-lang/industry` registry entry `"7729"`.
- `cloud-itonami/cloud-itonami-isic-7729` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-7721/blueprint.edn` (the sibling shape this
  build reuses).
