# ADR-2607104500: cloud-itonami-isic-8219 (Community Office Support Services Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607104400 (callcentreops/8220 -- thirtieth "author new
  blueprints from `:spec`" build, flagged this build as a comparable
  BPO-adjacent candidate)
- ADR-2607104300 (printsupportops/1812) -- the vertical this build
  distinguishes itself from
- ADR-2607100500 (securityops/8010), ADR-2607103500
  (securitysystemsops/8020), ADR-2607103800 (landscapeops/8130),
  ADR-2607103900 (buildingcleaningops/8121) -- the prior labor-
  dispatch-shape builds this one also reuses
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the THIRTY-FIRST "author a new blueprint from `:spec` tier"
build, following up on `ADR-2607104400`'s own Alternatives section,
which flagged `"8219"` as a comparable BPO-adjacent candidate.

### Candidate selection

`"8219"` (Photocopying, document preparation and other specialized
office support activities) was selected: scoped to retail office/
business-support services (copy shops, document-typing/preparation
services, mailing/business-support services), richly and
independently regulated -- unauthorized-practice-of-law statutes
restrict document preparers from giving legal advice (several US
states, e.g. California, require registration as a Legal Document
Assistant for fee-based legal-document preparation); copyright/fair-
use compliance applies when photocopying published works; and data-
privacy obligations apply to the personal and financial documents
customers bring in.

This candidate required direct verification against a close sibling:
`printsupportops`/1812 (`ADR-2607104300`) covers pre-press and post-
press/bindery SERVICES for the commercial printing industry
(industrial equipment serving print shops), a fundamentally different
customer base and equipment class from retail copy shops and
document-preparation services serving individuals and small
businesses. Redundancy screening, verified before selection: `grep
-rli "photocopy|document.preparation|office.support"` across every
`blueprint.edn`, `docs/business-model.md` and `README.md` in the
fleet returned completely empty. Governor keyword
`:office-support-governor` grep-verified UNIQUE fleet-wide. The
legacy placeholder `:repo` (`gftdcojp/cloud-itonami-N8219`) was
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

### Decision 2: `:office-support-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit "Scope note" distinguishing retail office support from industrial print bindery

The README's own "Scope note" section directly names `cloud-itonami-
isic-1812`, quotes its scope (industrial pre-press/bindery services
serving print shops), and explains the retail-vs-industrial and
customer-base distinctions, matching the discipline established
repeatedly this window.

### Decision 4: reuse the labor-dispatch operating-states shape, now SIX sibling reuses

The registry's own pre-existing `:operating-states` for `"8219"`
(`:intake :register :match :dispatch :follow-up :audit`) is IDENTICAL
to `securityops`/8010, `securitysystemsops`/8020, `landscapeops`/8130,
`buildingcleaningops`/8121 and `callcentreops`/8220's own -- this
build reuses that shape verbatim, adapted for document-handling/
preparation-scope concerns. This is now the SIXTH sibling in the
fleet reusing this exact shape.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-8219` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 136 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N8219"` to `"cloud-itonami-8219"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-8219` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"8219"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching job-scheduling/staff-routing
optimization needs), and its explicit `:maturity :spec` override
REMOVED so `maturity`/`maturity-of` now auto-derive `:blueprint` from
the real `:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607104400`'s own `(is (= 30 (:blueprint m)))` assertion updated
to `(is (= 31 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"8219")))`) alongside the thirty prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 30 :spec 510 :total 646}`
  → `{:implemented 106 :blueprint 31 :spec 509 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 184
  assertions, all green.
- `cloud-itonami-isic-8219` joins the thirty prior fresh-blueprint
  picks as a thirty-first candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- The labor-dispatch shape remains the fleet's most-reused pattern
  this window at six instances (`8010`/`8020`/`8130`/`8121`/`8220`/
  `8219`).

## Alternatives considered

- No other candidate was seriously considered for this build;
  `"8219"` was a direct, pre-flagged extension of the just-closed
  BPO/office-services domain (`8220`).

## References

- `kotoba-lang/industry` registry entry `"8219"`.
- `cloud-itonami/cloud-itonami-isic-8219` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-8220/blueprint.edn` (the sibling shape this
  build reuses).
