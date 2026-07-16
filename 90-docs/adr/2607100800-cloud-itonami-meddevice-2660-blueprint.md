# ADR-2607100800: cloud-itonami-isic-2660 (Community Medical Device Manufacturing) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607100700 (investigationops/8030 -- third "author new blueprints
  from `:spec`" build)
- ADR-2607100600 (foodserviceops/5610)
- ADR-2607100500 (securityops/8010)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 98 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the FOURTH "author a new blueprint from `:spec` tier" build,
and the FIRST to pick a manufacturing-sector vertical rather than a
services/dispatch-shaped one. Every prior fresh-blueprint pick this
scope (`securityops`/8010, `foodserviceops`/5610, `investigationops`/
8030) followed the SAME `intake -> dispatch/report -> audit`
operating-states shape. `"2660"` (Manufacture of irradiation,
electromedical and electrotherapeutic equipment, ISIC Rev.4 class,
section C) instead follows a manufacturing lifecycle:
`[:spec :design :produce :inspect :package :audit]`.

### Candidate selection

`"2660"` was selected for a genuinely rich, well-known,
internationally-distinct regulatory domain -- medical device
certification (FDA 510(k)/PMA in the US, EU MDR, Japan's PMD Act/薬機法,
same EU MDR baseline for Germany) -- with the STRICTEST regulatory
scrutiny tier within medical devices generally applying to
electromedical/irradiation/electrotherapeutic equipment specifically
(imaging, radiotherapy, monitoring, electrotherapy devices are
typically Class II/III, unlike simpler general medical/dental
instruments). A clean robotics-premise fit: production, calibration
and inspection robots performing the physical manufacturing/QC work.
No redundancy with `cloud-itonami-isic-8690`/`8691`'s own healthcare-
SERVICE governors (`:allied-health-governor`, `:health-access-
governor`) -- those cover clinical/allied-health SERVICE delivery, not
device MANUFACTURING. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-C2660`) was confirmed via a direct GitHub API
404 check to never have actually existed. No bespoke capability
library exists for medical device manufacturing specifically
(`kotoba-lang/device` is Kotoba's own generic hardware-capability
layer for `aiueos` apps -- bluetooth/wifi/display/camera capability
tokens -- entirely unrelated to this domain).

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:medical-device-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: manufacturing-lifecycle framing, not dispatch/report

The "Core Contract" and Trust Controls text is adapted to this
vertical's own real operating-states shape
(`:spec :design :produce :inspect :package :audit`) rather than
reusing the `intake -> dispatch -> report` framing verbatim. The
governed "real-world act" analog to a service vertical's own
actuation op is releasing a device batch to market without passing
verification -- the point where a defect could reach and harm a
patient -- so the blueprint's own Trust Controls name this explicitly
("a device batch cannot be released outside its verified
specification").

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-2660` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 101 prior/sibling actors, rather than the legacy
`C####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-C2660"` to `"cloud-itonami-2660"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-2660` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"2660"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607100700`'s own `(is (= 3 (:blueprint m)))` assertion updated to
`(is (= 4 (:blueprint m)))`, continuing the "not a fixed invariant"
framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"2660")))`) alongside the three prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 98 :blueprint 3 :spec 542 :total 643}`
  → `{:implemented 98 :blueprint 4 :spec 541 :total 643}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 149
  assertions, all green.
- `cloud-itonami-isic-2660` joins `cloud-itonami-isic-8010`,
  `cloud-itonami-isic-5610` and `cloud-itonami-isic-8030` as a fourth
  candidate available for a future `:blueprint`->`:implemented`
  promotion pass, and the FIRST manufacturing-lifecycle-shaped one
  among them.
- Establishes that this `:spec`-authoring scope is not confined to the
  services/dispatch shape every prior pick this scope happened to
  share -- manufacturing-sector verticals with their own distinct
  operating-states shape are equally in scope, so long as the
  robotics-premise fit and regulatory grounding are genuine.

## Alternatives considered

- **`"3250"` (Manufacture of medical and dental instruments and
  supplies)**: considered as a lighter-weight medical-device
  alternative (only requires `:cae`, not `:eda`+`:cae`), but `"2660"`
  was preferred for its richer, stricter regulatory tier
  (electromedical/irradiation equipment vs. simpler instruments) and
  its more sophisticated required technology stack, better matching
  this fleet's own differentiator caliber.
- **Reusing the dispatch/report shape from prior picks**: rejected --
  would have misrepresented this vertical's own real operating-states
  lifecycle (`:spec :design :produce :inspect :package :audit`, already
  present in the registry entry itself), a manufacturing flow with no
  natural "dispatch a mission" step.

## References

- `kotoba-lang/industry` registry entry `"2660"`.
- `cloud-itonami/cloud-itonami-isic-2660` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `kotoba-lang/technology` registry entries for `:eda`
  (`kotoba-lang/eda`) and `:cae` (`kotoba-lang/cae-solver`).
