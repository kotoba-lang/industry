# ADR-2607103700: cloud-itonami-isic-3020 (Community Railway Rolling Stock Manufacturing) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607103500 (securitysystemsops/8020 -- twenty-second "author
  new blueprints from `:spec`" build, first non-transport pick)
- ADR-2607102200 (railops/4911) and ADR-2607102800
  (freightrailops/4912) -- the OPERATOR verticals this build
  distinguishes itself from
- ADR-2607100800 (meddeviceops/2660) and ADR-2607101400
  (basicchemops/2011) -- the two prior manufacturing-lifecycle-shape
  builds this one follows
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTY-THIRD "author a new blueprint from `:spec` tier"
build, and the FIRST MANUFACTURING pick since `meddeviceops`/2660
(`ADR-2607100800`) and `basicchemops`/2011 (`ADR-2607101400`) earlier
in this window's own arc.

### Candidate selection

`"3020"` (Manufacture of railway locomotives and rolling stock) was
selected: a richly and independently regulated manufacturing activity
(the EU's Technical Specifications for Interoperability under
Directive (EU) 2016/797; Japan's 鉄道車両の設計認可 rolling-stock design
authorization under the Railway Business Act, distinct from the
operator-facing 鉄道事業法 licensing that governs `4911`/`4912`; the US
FRA's rolling-stock approval under 49 CFR Parts 238/239), with a
natural robotics-premise fit (welding, fabrication, assembly-line
inspection, non-destructive-testing scan robots) and a genuine
type-approval/design-authority story matching the caliber of the
fleet's other manufacturing picks.

This candidate required direct verification against two close
siblings already in the fleet: `cloud-itonami-isic-4911` (passenger
rail) and `cloud-itonami-isic-4912` (freight rail). Both operators'
own README/docs mention "rolling-stock maintenance"/"rolling-stock
inspection" as one of THEIR OWN offerings. Directly read before
selection: in both cases this is an OPERATOR'S OWN upkeep of vehicles
it already owns and runs in revenue service -- neither vertical
manufactures new rolling stock or claims any design-authority/type-
approval scope. `"3020"` is scoped to the SEPARATE business of BUILDING
locomotives and rolling stock, sold to MULTIPLE rail operators --
confirmed non-redundant. Governor keyword
`:rolling-stock-manufacturing-governor` grep-verified UNIQUE
fleet-wide. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-C3020` -- a THIRD legacy placeholder prefix
variant observed this window, after H and N) was confirmed via a
direct GitHub API 404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:rolling-stock-manufacturing-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword; distinct from `railops`/4911's own
`:rail-safety-governor` and `freightrailops`/4912's own
`:rail-freight-governor`.

### Decision 3: explicit "Scope note" distinguishing this vertical from both rail operators

Following the same discipline established repeatedly this scope: the
README's own "Scope note" section directly names `cloud-itonami-isic-
4911` and `cloud-itonami-isic-4912`, quotes their shared "rolling-
stock maintenance" language, and explains the manufacturer-vs-
operator distinction with specific regulatory citations, rather than
merely asserting non-redundancy.

### Decision 4: reuse the manufacturing-lifecycle shape established by meddeviceops/2660 and basicchemops/2011

The registry's own pre-existing `:operating-states` for `"3020"`
(`:spec :design :produce :inspect :package :audit`) matches the SAME
manufacturing-lifecycle shape used for `meddeviceops`/2660 and
`basicchemops`/2011 -- this build reuses it verbatim, adapted for
design-authority/type-approval concerns (a production step outside
verified design-authority scope, a delivery release without a
completed inspection/type-approval pass) rather than batch-release
concerns.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-3020` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 128 prior/sibling actors, rather than the legacy
`C####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-C3020"` to `"cloud-itonami-3020"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-3020` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"3020"` registry entry had its `:repo` and `:business-id`
fields updated, and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence. `:optional-technologies` was already empty and
stays empty (no clear optimization target identified for this
vertical at blueprint stage).

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607103500`'s own `(is (= 22 (:blueprint m)))` assertion updated
to `(is (= 23 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"3020")))`) alongside the twenty-two prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 22 :spec 518 :total 646}`
  → `{:implemented 106 :blueprint 23 :spec 517 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 176
  assertions, all green.
- `cloud-itonami-isic-3020` joins the twenty-two prior fresh-blueprint
  picks as a twenty-third candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- Confirms the "operator maintains, doesn't manufacture" pattern is a
  reliable general-purpose screen: any transport-mode manufacturing
  class can be safely checked against its own operator sibling's
  "maintenance" language the same way this build checked `4911`/`4912`.

## Alternatives considered

- **`"3040"` (manufacture of military fighting vehicles)**, the
  immediately adjacent registry entry: not considered, consistent
  with this fleet's established ethical-screening precedent against
  weapons-adjacent manufacturing (`ADR-2607101400`'s own rejection of
  ISIC 2520).
- **`"8129"`/`"8130"` (other cleaning activities / landscape care)**:
  viable non-manufacturing alternatives considered briefly; `3020`
  was preferred for its stronger, more concrete regulatory grounding
  and its fit with the fleet's existing manufacturing-lifecycle
  pattern.

## References

- `kotoba-lang/industry` registry entry `"3020"`.
- `cloud-itonami/cloud-itonami-isic-3020` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-2011/blueprint.edn` (the sibling shape this
  build reuses).
