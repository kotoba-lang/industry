# ADR-2607103800: cloud-itonami-isic-8130 (Community Landscape Care and Maintenance Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607103700 (rollingstockops/3020 -- twenty-third "author new
  blueprints from `:spec`" build, first manufacturing pick after the
  transport-support family closed)
- ADR-2607103500 (securitysystemsops/8020) and ADR-2607100500
  (securityops/8010) -- the two prior labor-dispatch-shape builds this
  one reuses
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTY-FOURTH "author a new blueprint from `:spec` tier"
build. Continuing the portfolio-diversification discipline established
at `ADR-2607103500` (deliberately picking fresh domains rather than
exhausting one family before moving on), this build picks a
service-labor vertical distinct from both the recently closed
transport-support family and the just-published rolling-stock
manufacturing pick.

### Candidate selection

`"8130"` (Landscape care and maintenance service activities) was
selected: scoped to ONGOING care and maintenance of existing grounds
(mowing, trimming, irrigation management, chemical treatment
application) -- deliberately distinct from landscape ARCHITECTURE and
design, a separately licensed profession already covered elsewhere in
this fleet's specialized-design-activities scope (`cloud-itonami-isic-
7410`, `:implemented`). Chemical application carries its own
independent licensing regime (the US EPA/state-level Commercial
Pesticide Applicator license; Japan's 農薬取締法 Agricultural Chemicals
Regulation Act governing commercial pesticide application; state-level
irrigator licenses such as Texas's Irrigator License where irrigation
systems are installed or serviced), giving this vertical the same
caliber of regulatory grounding as the fleet's other labor-dispatch
picks.

Redundancy screening, verified before selection: `grep -rli
"landscap"` across every `blueprint.edn`, `docs/business-model.md`
and `README.md` in the fleet found exactly one hit --
`cloud-itonami-unspsc-27`'s own tool-fleet-rental business, which
lists "landscaping firms" only as a CUSTOMER segment (renting tools
FROM the rental business), not itself a landscaping service provider.
Directly read and confirmed non-redundant. Governor keyword
`:landscape-care-governor` grep-verified UNIQUE fleet-wide. The
legacy placeholder `:repo` (`gftdcojp/cloud-itonami-N8130`) was
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

### Decision 2: `:landscape-care-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing care/maintenance from design

The README's own "Scope note" section explains the design-vs-
maintenance distinction directly, citing the fleet's own existing
`7410` design-activities actor as the adjacent-but-separate business,
matching the discipline established repeatedly this window.

### Decision 4: reuse the labor-dispatch operating-states shape established by securityops/8010 and securitysystemsops/8020

The registry's own pre-existing `:operating-states` for `"8130"`
(`:intake :register :match :dispatch :follow-up :audit`) is IDENTICAL
to both `"8010"`'s and `"8020"`'s own -- this build reuses that shape
verbatim, adapted for landscape-care-specific safety concerns (a
chemical application outside verified applicator-license scope, an
irrigation change without a completed water-compliance check). This
is now the THIRD sibling in the fleet reusing this exact shape.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-8130` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 129 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N8130"` to `"cloud-itonami-8130"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-8130` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"8130"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching crew-routing/scheduling optimization
needs), and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607103700`'s own `(is (= 23 (:blueprint m)))` assertion updated
to `(is (= 24 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"8130")))`) alongside the twenty-three prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 23 :spec 517 :total 646}`
  → `{:implemented 106 :blueprint 24 :spec 516 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 177
  assertions, all green.
- `cloud-itonami-isic-8130` joins the twenty-three prior fresh-blueprint
  picks as a twenty-fourth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- Establishes the labor-dispatch shape (`:intake :register :match
  :dispatch :follow-up :audit`) as a THREE-time-reused pattern
  (`8010`/`8020`/`8130`), alongside the FIVE-time-reused booking/
  transit/delivery/reconciliation shape from the transport family --
  both now proven reusable well beyond their originating pair.

## Alternatives considered

- **`"8110"` (combined facilities support activities)** and **`"8129"`
  (other building and industrial cleaning activities)**: comparable
  fresh-domain candidates in the same broad services section; `8130`
  was preferred for its more concrete, independently verifiable
  applicator-licensing regulatory story.

## References

- `kotoba-lang/industry` registry entry `"8130"`.
- `cloud-itonami/cloud-itonami-isic-8130` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-8020/blueprint.edn` (the sibling shape this
  build reuses).
