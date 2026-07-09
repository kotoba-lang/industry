# ADR-2607104200: cloud-itonami-isic-1811 (Community Printing Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104100 (electronicrepairops/3313 -- twenty-seventh "author
  new blueprints from `:spec`" build, closed the repair-services
  micro-family)
- ADR-2607103700 (rollingstockops/3020), ADR-2607101400
  (basicchemops/2011), ADR-2607100800 (meddeviceops/2660) -- the
  manufacturing-lifecycle-shape builds this one also reuses
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTY-EIGHTH "author a new blueprint from `:spec` tier"
build. Continuing the portfolio-diversification discipline
established at `ADR-2607103500` (after closing a micro-family, pick a
fresh domain rather than continuing to exhaust adjacent siblings),
this build moves away from the just-closed repair-services pair
(`3312`/`3313`) into printing/publishing.

### Candidate selection

`"1811"` (Printing) was selected: scoped to the press-production
business itself -- operating printing presses and integrated
finishing/binding equipment -- richly and independently regulated
(ISO 12647 process-control standards for color management; G7 Master
Certification from Idealliance for print-quality qualification; OSHA
machine-guarding requirements specific to printing-press operation;
environmental/VOC regulations governing ink/solvent handling under
EPA and EU industrial-emissions frameworks; FSC chain-of-custody
certification for sustainably sourced paper stock), with a natural
robotics-premise fit (press-feed, in-line quality-inspection cameras,
finishing/binding assist).

Redundancy screening, verified before selection: `grep -rli
"commercial.print|printing.press|print.production"` across every
`blueprint.edn`, `docs/business-model.md` and `README.md` in the
fleet returned completely empty. Distinct from `cloud-itonami-isic-
1812` ("Service activities related to printing", still `:spec`,
untouched): that class covers independent third-party pre-press/
post-press services performed as a separate business, not an
in-house press operation -- a genuine sibling for a future
low-friction pick. Governor keyword `:printing-governor`
grep-verified UNIQUE fleet-wide. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-C1811`) was confirmed via a direct GitHub API
404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:printing-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing press production from print-support services

The README's own "Scope note" section directly names `cloud-itonami-
isic-1812` and explains the in-house-press-vs-independent-service
distinction, matching the discipline established repeatedly this
window, and explicitly flags `1812` as an available future
low-friction sibling.

### Decision 4: reuse the manufacturing-lifecycle shape

The registry's own pre-existing `:operating-states` for `"1811"`
(`:spec :design :produce :inspect :package :audit`) matches the SAME
manufacturing-lifecycle shape used for `basicchemops`/2011,
`meddeviceops`/2660, `rollingstockops`/3020 and the repair-services
pair (`3312`/`3313`) -- this build reuses it verbatim, adapted for
color/print-specification concerns.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-1811` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 133 prior/sibling actors, rather than the legacy
`C####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-C1811"` to `"cloud-itonami-1811"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-1811` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"1811"` registry entry had its `:repo` and
`:business-id` fields updated, and its explicit `:maturity :spec`
override REMOVED so `maturity`/`maturity-of` now auto-derive
`:blueprint` from the real `:repo` presence. `:optional-technologies`
was already empty and stays empty.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104100`'s own `(is (= 27 (:blueprint m)))` assertion updated
to `(is (= 28 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"1811")))`) alongside the twenty-seven prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 27 :spec 513 :total 646}`
  → `{:implemented 106 :blueprint 28 :spec 512 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 181
  assertions, all green.
- `cloud-itonami-isic-1811` joins the twenty-seven prior fresh-
  blueprint picks as a twenty-eighth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- `"1812"` (printing-support services) is now explicitly flagged as an
  available future low-friction sibling pick, matching the pattern
  established for `3312`/`3313` and `4321`/`4322`.

## Alternatives considered

- **`"7710"` (renting and leasing of motor vehicles)**: a comparable
  fresh-domain candidate; `"1811"` was preferred for its stronger,
  more concrete manufacturing-lifecycle regulatory story and natural
  robotics-premise fit.

## References

- `kotoba-lang/industry` registry entry `"1811"`.
- `cloud-itonami/cloud-itonami-isic-1811` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-2011/blueprint.edn` (the sibling shape this
  build reuses).
