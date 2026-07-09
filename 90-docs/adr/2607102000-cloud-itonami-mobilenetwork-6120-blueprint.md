# ADR-2607102000: cloud-itonami-isic-6120 (Community Mobile Network Infrastructure Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607101800 (gridtransmissionops/3510 -- ninth "author new
  blueprints from `:spec`" build, resolved a deferred candidate)
- ADR-2607101400 (basicchemops/2011 -- where `"6120"` was first
  flagged and deferred)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TENTH "author a new blueprint from `:spec` tier" build,
and resolves the LAST candidate still flagged as deferred: `"6120"`
(Wireless telecommunications activities), first flagged at
`ADR-2607101400` alongside `"3510"` (resolved separately at
`ADR-2607101800`).

### Resolving the deferral: licensed network operator, not a reseller

`cloud-itonami-isic-6190` ("Community Telecommunications Access") was
re-read directly (`docs/business-model.md`) to settle whether `"6120"`
could be cleanly distinguished. `6190`'s own text names its activity
explicitly as "VoIP, public access, reselling" -- it is a service-layer
business that does NOT hold spectrum or network infrastructure; it
buys wholesale connectivity and resells/routes calls over it. `"6120"`
in the real ISIC Rev.4 classification (division 61,
Telecommunications) means specifically the entity that OWNS AND
OPERATES the wireless radio access network itself -- spectrum license,
cell towers, base stations, RAN equipment -- a genuinely distinct,
independently regulated business (spectrum-license compliance and
site-access rights are core regulatory concerns a pure reseller never
faces). This is the SAME kind of infrastructure-operator-vs-service-
reseller distinction `ADR-2607101800` used to resolve `"3510"` against
`"3512"`, and matches the real, standard ISIC subclass boundary between
6110 (wired network operators), 6120 (wireless network operators) and
6190 (other telecommunications activities -- explicitly the catch-all
for activities that do NOT fit the "network operator" categories,
including resale/VoIP).

`"6120"`'s own registry-declared operating-states (`:intake :provision
:route :bill :support :audit`) were checked for shape-honesty concerns
(the same discipline applied when confirming `"3510"`'s own shape
genuinely matched its new framing): this shape is genuinely consistent
with EITHER business (a reseller routes calls over someone else's
network; a network operator routes calls over its OWN network) --
the distinguishing fact is who owns the underlying infrastructure, not
a different operational lifecycle, so no shape-honesty concern arises.

`kotoba-lang/phone` (E.164 numbering, SIP URIs, CDR/SMS records) is
explicitly named in its own README as built for `cloud-itonami-6190`
specifically, and is required by `"6120"`'s own registry entry too --
this is a SHARED cross-cutting telephony-records capability (any
telecom operator, reseller or infrastructure owner, needs E.164/CDR/SMS
records), the same sharing pattern as `:robotics` being required
fleet-wide -- NOT evidence of redundancy between the two verticals.

The legacy placeholder `:repo` (`gftdcojp/cloud-itonami-J6120`) was
confirmed via a direct GitHub API 404 check to never have actually
existed. No bespoke capability library exists uniquely for mobile
network infrastructure specifically (beyond the shared `:phone`
contract).

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:mobile-network-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: explicit scope note distinguishing from the reseller vertical

The README's own text carries an explicit "Scope note: licensed
network operator, not a reseller" section naming
`cloud-itonami-isic-6190` directly and quoting its own activity
description, plus naming `cloud-itonami-isic-6110`'s own wired-network
scope for completeness -- following the SAME discipline
`gridtransmissionops`/3510 established at `ADR-2607101800`.

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-6120` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 115 prior/sibling actors, rather than the legacy
`J####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-J6120"` to `"cloud-itonami-6120"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-6120` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"6120"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607101800`'s own `(is (= 9 (:blueprint m)))` assertion updated to
`(is (= 10 (:blueprint m)))`, continuing the "not a fixed invariant"
framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"6120")))`) alongside the nine prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 9 :spec 531 :total 646}`
  → `{:implemented 106 :blueprint 10 :spec 530 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 163
  assertions, all green.
- `cloud-itonami-isic-6120` joins the nine prior fresh-blueprint picks
  as a tenth candidate available for a future `:blueprint`->
  `:implemented` promotion pass -- a nice round milestone for this
  `:spec`-authoring scope's own first ten builds.
- With this build, EVERY candidate ever explicitly flagged as deferred
  in this scope has now been resolved (`"3812"` at `ADR-2607101300`,
  `"3510"` at `ADR-2607101800`, `"6120"` here) -- none remain
  outstanding. The next build will need a fresh candidate scan rather
  than picking up a flagged deferral.

## Alternatives considered

- **Treating `"6120"` and `"6190"` as too close to differentiate,
  deferring indefinitely**: rejected -- direct re-reading of `6190`'s
  own business-model.md confirmed an unambiguous, real ISIC-standard
  distinction (network-operator vs. reseller) rather than a forced
  one.
- **Reusing `kotoba-lang/phone` as evidence of redundancy**: rejected
  as a concern -- shared cross-cutting capability libraries (`:phone`
  here, `:robotics` fleet-wide) are expected and do not imply the
  consuming verticals are the same business.

## References

- `kotoba-lang/industry` registry entry `"6120"`.
- `cloud-itonami/cloud-itonami-isic-6120` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-6190/docs/business-model.md` (confirmed the
  genuine distinction, not redundancy).
- `cloud-itonami-isic-6110/blueprint.edn` (confirmed the wired-network
  scope this build does not overlap with).
