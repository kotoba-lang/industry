# ADR-2607104100: cloud-itonami-isic-3313 (Community Electronic and Optical Equipment Repair Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104000 (machineryrepairops/3312 -- twenty-sixth "author new
  blueprints from `:spec`" build, flagged this build as its own
  low-friction sibling)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTY-SEVENTH "author a new blueprint from `:spec` tier"
build, and a low-friction sibling pick following the pattern
established at `ADR-2607101600` (electrical/plumbing trades) and
`ADR-2607102600` (TV/radio broadcasting): `ADR-2607104000`'s own
References section explicitly flagged `"3313"` as the natural next
pick alongside `machineryrepairops`/3312.

### Candidate selection

`"3313"` (Repair of electronic and optical equipment) was selected:
scoped to diagnostic, calibration and certified repair of electronic
instruments, test/measurement equipment and optical devices --
distinct from `machineryrepairops`/3312's own mechanical
industrial-machinery scope, and from the fleet's manufacturing
verticals (which build NEW equipment). This class carries its own
distinct compliance concerns: calibration services frequently require
ISO/IEC 17025 laboratory accreditation; component-level rework
follows IPC/WHMA-A-620 and IPC-7711/7721 industry rework/repair
standards; right-to-repair legislation (various US state acts, the
EU's Right to Repair Directive 2024/1799) increasingly shapes access
to parts, diagnostics and service documentation specifically for this
equipment class.

Redundancy screening, verified before selection: `grep -rli
"electronic.equipment.repair|optical.equipment.repair|repair.of.
electronic|calibration.service"` across every `blueprint.edn`, `docs/
business-model.md` and `README.md` in the fleet returned completely
empty. Governor keyword `:electronic-repair-governor` grep-verified
UNIQUE fleet-wide. The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-C3313`) was confirmed via a direct GitHub API
404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:electronic-repair-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword; distinct from `machineryrepairops`/3312's own
`:machinery-repair-governor`.

### Decision 3: explicit "Scope note" distinguishing this vertical from machinery repair and manufacturing

The README's own "Scope note" section directly names `cloud-itonami-
isic-3312` and `cloud-itonami-isic-2660`, and explains the
electronic-vs-mechanical and repair-vs-manufacture distinctions,
matching the discipline established repeatedly this window.

### Decision 4: reuse the manufacturing-lifecycle shape

The registry's own pre-existing `:operating-states` for `"3313"`
(`:spec :design :produce :inspect :package :audit`) matches the SAME
manufacturing-lifecycle shape used for `machineryrepairops`/3312,
`rollingstockops`/3020, `meddeviceops`/2660 and `basicchemops`/2011 --
this build reuses it verbatim, adapted for calibration-certification
concerns.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-3313` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 132 prior/sibling actors, rather than the legacy
`C####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-C3313"` to `"cloud-itonami-3313"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-3313` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"3313"` registry entry had its `:repo` and
`:business-id` fields updated, and its explicit `:maturity :spec`
override REMOVED so `maturity`/`maturity-of` now auto-derive
`:blueprint` from the real `:repo` presence. `:optional-technologies`
was already empty and stays empty.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104000`'s own `(is (= 26 (:blueprint m)))` assertion updated
to `(is (= 27 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"3313")))`) alongside the twenty-six prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 26 :spec 514 :total 646}`
  → `{:implemented 106 :blueprint 27 :spec 513 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 180
  assertions, all green.
- `cloud-itonami-isic-3313` joins the twenty-six prior fresh-blueprint
  picks as a twenty-seventh candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- The manufacturing-lifecycle shape is now reused across FOUR
  verticals this window (`2011`/`2660`/`3020`/`3312`/`3313` -- five,
  in fact), confirming it generalizes cleanly across both new-
  manufacture and repair/overhaul businesses.

## Alternatives considered

- No alternative candidate was seriously considered for this build;
  `"3313"` was a direct, pre-flagged low-friction extension of the
  established repair-services pattern (`3312`), matching the same
  discipline used for `plumbingops`/4322 <- `electricalops`/4321 and
  `radiobroadcastops`/6010 <- `tvbroadcastops`/6020.

## References

- `kotoba-lang/industry` registry entry `"3313"`.
- `cloud-itonami/cloud-itonami-isic-3313` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-3312/blueprint.edn` (the sibling shape this
  build reuses).
