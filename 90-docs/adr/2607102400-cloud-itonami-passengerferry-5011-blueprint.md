# ADR-2607102400: cloud-itonami-isic-5011 (Community Passenger Ferry Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607102200 (railops/4911 -- twelfth "author new blueprints from
  `:spec`" build, second transport-mode pick)
- ADR-2607102100 (aviationops/5110 -- first transport-mode pick,
  opened this family)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the THIRTEENTH "author a new blueprint from `:spec` tier"
build, and closes out the transport-mode family `ADR-2607102100`
opened: `"5011"` (Sea and coastal passenger water transport) was the
last untouched candidate `ADR-2607102200`'s own Consequences section
flagged.

### Candidate selection and redundancy screening

`cloud-itonami-isic-5020` was checked directly (`blueprint.edn`) and
confirmed to be a marine-CARGO/tanker actor (`:marine-cargo-governor`,
the "project-internal isic-rev5 code" petroleum-tanker build mentioned
in the petroleum-fleet ADR history) -- a genuinely distinct business
from scheduled PASSENGER ferry service. Passenger-vessel safety
certification (this build's own domain) is a materially different
regulatory regime from cargo-vessel certification in every
jurisdiction checked (Japan's 船舶安全法 passenger-vessel provisions vs.
cargo-vessel provisions under the same act but different survey
requirements; the US's USCG 46 CFR Subchapter H for passenger vessels
vs. cargo-vessel subchapters; the UK's MCA Merchant Shipping
(Passenger Ship Construction) Regulations vs. cargo-ship regulations;
Germany's BSH under the Schiffssicherheitsgesetz with separate
passenger/cargo survey regimes). No redundancy confirmed via grep
across every `blueprint.edn` for any other maritime/ferry-related
governor keyword. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-H5011`) was confirmed via a direct GitHub API
404 check to never have actually existed. No bespoke capability
library exists for ferry operations specifically.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:maritime-safety-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit scope note distinguishing from marine cargo

The README's own text carries an explicit "Scope note: passenger
water transport, not marine cargo" section naming
`cloud-itonami-isic-5020` directly -- following the SAME discipline
`gridtransmissionops`/3510 and `mobilenetworkops`/6120 both
established when distinguishing themselves from adjacent verticals.

### Decision 4: reuse the booking/transit/delivery/reconciliation shape, closing out the transport-mode family

The "Core Contract" and Trust Controls text follows the SAME framing
`aviationops`/5110 and `railops`/4911 both established, adapted for
maritime-specific safety concerns (a sailing dispatch outside a
verified passenger-certificate scope, a maintenance release without
inspection) -- the third and final reuse of this shape in the
transport-mode family this build closes out.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-5011` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 118 prior/sibling actors, rather than the legacy
`H####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-H5011"` to `"cloud-itonami-5011"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-5011` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"5011"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607102200`'s own `(is (= 12 (:blueprint m)))` assertion updated
to `(is (= 13 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-
state corroboration assertion (`(is (= :blueprint (industry/maturity
"5011")))`) alongside the twelve prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 12 :spec 528 :total 646}`
  → `{:implemented 106 :blueprint 13 :spec 527 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 166
  assertions, all green (also incidentally confirmed the fleet's own
  concurrent `industry.cljc` -> `industry.clj` rename, a maintenance
  change unrelated to this build, did not affect the `kotoba.industry`
  namespace's own public API).
- `cloud-itonami-isic-5011` joins the twelve prior fresh-blueprint
  picks as a thirteenth candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- The three-mode transport family this scope opened at
  `ADR-2607102100` (air, rail, water) is now complete -- the next
  build will need a fresh domain scan.

## Alternatives considered

- **`"5021"` (Inland passenger water transport)**: a comparably strong
  candidate (river/lake ferries rather than sea/coastal); set aside in
  favor of `"5011"` for this pass since sea/coastal service is the more
  universally recognized ferry-operations pattern, remains available
  for a future build if a genuine distinction from `"5011"` is worth
  drawing out.
- **Reusing `cloud-itonami-isic-5020`'s own `:marine-cargo-governor`**:
  rejected -- passenger and cargo vessel certification are governed by
  materially different regimes and should not share a governor.

## References

- `kotoba-lang/industry` registry entry `"5011"`.
- `cloud-itonami/cloud-itonami-isic-5011` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-5020/blueprint.edn` (confirmed the genuine
  distinction, not redundancy).
- `cloud-itonami-isic-5110/blueprint.edn` and `cloud-itonami-isic-4911/
  blueprint.edn` (the sibling shapes this build reuses).
