# ADR-2607104000: cloud-itonami-isic-3312 (Community Industrial Machinery Repair Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607103900 (buildingcleaningops/8121 -- twenty-fifth "author
  new blueprints from `:spec`" build)
- ADR-2607103700 (rollingstockops/3020) -- the prior manufacturing-
  lifecycle-shape build this one also reuses
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTY-SIXTH "author a new blueprint from `:spec` tier"
build.

### Candidate selection: 8129 screened first, deferred on genuine overlap

`"8129"` (Other building and industrial cleaning activities) was the
natural low-friction sibling flagged in `ADR-2607103900`'s own
Alternatives section, and was screened FIRST this build. `grep -rli
"chimney|disinfection|decontaminat|industrial.clean|specialized.clean|
window.clean"` across the fleet surfaced a genuine, direct overlap:
`cloud-itonami-unspsc-73` ("Independent Industrial Cleaning &
Certification Robotics") already covers scheduled industrial
equipment/facility cleaning with governed post-clean sensor
certification for manufacturing plants and food/pharma facilities --
directly read its own `docs/business-model.md` and confirmed this
substantially overlaps the "industrial cleaning" component of ISIC
`"8129"`. Rather than force a narrow carve-out this pass, `"8129"` was
DEFERRED (not rejected) for future careful scoping, consistent with
this fleet's own established "defer, don't force" discipline
(`ADR-2607100700`'s original instance, later resolved at
`ADR-2607101300`).

### Candidate selection: 3312 picked instead

`"3312"` (Repair of machinery) was selected instead: scoped to
diagnostic, overhaul and certified repair of EXISTING industrial
equipment (pumps, compressors, turbines, manufacturing machinery),
richly and independently regulated (the ASME "R" stamp for authorized
repair of pressure vessels/boilers in the US; the EU Pressure
Equipment Directive (PED)-compliant repair procedures; OEM certified-
service-provider programs that gate warranty validity on an
authorized repair process), with a natural robotics-premise fit
(diagnostic scanning, teardown/reassembly assist, non-destructive-
testing inspection).

Redundancy screening, verified before selection: `grep -rli
"machinery.repair|industrial.equipment.repair|equipment.maintenance.
and.repair"` across every `blueprint.edn`, `docs/business-model.md`
and `README.md` in the fleet returned completely empty. Distinct from
`cloud-itonami-isic-3020` and every other manufacturing vertical in
this fleet (which build NEW equipment) and from `cloud-itonami-
unspsc-73` (which cleans and certifies contamination-free equipment,
not functional repair) -- both directly confirmed non-redundant.
Governor keyword `:machinery-repair-governor` grep-verified UNIQUE
fleet-wide. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-C3312`) was confirmed via a direct GitHub API
404 check to never have actually existed.

### Note: GitHub Contents API outage worked around

The usual `gh api "repos/com-junkawasaki/root/contents/90-docs/adr?
ref=main"` command used to freshly verify the next-available ADR id
returned a transient GitHub 500 "Unicorn" error page (likely a
response-size/timeout issue as the ADR directory has grown large).
Worked around by using the Git Trees API instead (`git/trees/main` ->
`90-docs` subtree -> `adr` subtree), which returned the directory
listing successfully and confirmed `"2607104000"` as the next
available id. No change to the fresh-verification discipline itself,
just an alternate API path for the same check.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:machinery-repair-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing repair from manufacturing and cleaning

The README's own "Scope note" section directly names `cloud-itonami-
isic-3020` and `cloud-itonami-unspsc-73`, and explains the repair-vs-
manufacture and repair-vs-cleaning distinctions with specific
certification-regime citations, matching the discipline established
repeatedly this window.

### Decision 4: reuse the manufacturing-lifecycle shape established by rollingstockops/3020

The registry's own pre-existing `:operating-states` for `"3312"`
(`:spec :design :produce :inspect :package :audit`) matches the SAME
manufacturing-lifecycle shape used for `rollingstockops`/3020,
`meddeviceops`/2660 and `basicchemops`/2011 -- this build reuses it
verbatim, adapted for repair-authority/certification concerns.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-3312` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 131 prior/sibling actors, rather than the legacy
`C####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-C3312"` to `"cloud-itonami-3312"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-3312` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"3312"` registry entry had its `:repo` and
`:business-id` fields updated, and its explicit `:maturity :spec`
override REMOVED so `maturity`/`maturity-of` now auto-derive
`:blueprint` from the real `:repo` presence. `:optional-technologies`
was already empty and stays empty (no clear optimization target
identified for this vertical at blueprint stage).

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607103900`'s own `(is (= 25 (:blueprint m)))` assertion updated
to `(is (= 26 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"3312")))`) alongside the twenty-five prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 25 :spec 515 :total 646}`
  → `{:implemented 106 :blueprint 26 :spec 514 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 179
  assertions, all green.
- `cloud-itonami-isic-3312` joins the twenty-five prior fresh-blueprint
  picks as a twenty-sixth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- `"8129"` remains explicitly deferred for a future build requiring a
  narrow scoping pass against `cloud-itonami-unspsc-73`'s existing
  industrial-cleaning coverage.

## Alternatives considered

- **`"8129"` (other building/industrial cleaning activities)**:
  screened first this build; deferred on a genuine overlap risk
  against `cloud-itonami-unspsc-73` rather than forced.
- **`"3313"` (repair of electronic and optical equipment)**: the
  immediately adjacent registry entry and a comparable candidate;
  remains available for a future low-friction sibling pick.

## References

- `kotoba-lang/industry` registry entry `"3312"`.
- `cloud-itonami/cloud-itonami-isic-3312` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-3020/blueprint.edn` (the sibling shape this
  build reuses).
