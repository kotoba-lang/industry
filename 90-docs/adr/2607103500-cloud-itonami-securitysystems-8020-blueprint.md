# ADR-2607103500: cloud-itonami-isic-8020 (Community Security Systems Service Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607103400 (freightforwardingops/5229 -- twenty-first "author
  new blueprints from `:spec`" build, closed the "transport support"
  family)
- ADR-2607100500 (securityops/8010 -- the sibling vertical this build
  distinguishes itself from)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the TWENTY-SECOND "author a new blueprint from `:spec` tier"
build, and the FIRST NON-TRANSPORT pick since the "transport support"
family (`ADR-2607102900` through `ADR-2607103400`) closed. This build
deliberately picks a fresh domain rather than continuing the same
family further, to avoid over-concentrating the fleet's fresh
blueprints in one sub-sector.

### Candidate selection

`"8020"` (Security systems service activities) was selected: a
richly and distinctly licensed activity in every jurisdiction checked
(California's Bureau of Security and Investigative Services issues a
separate "Alarm Company Operator" license distinct from its "Private
Patrol Operator" license; the UK's Security Industry Authority
licenses "Public Space Surveillance (CCTV)" separately from "Door
Supervisor"/"Security Guarding"; Japan's 警備業法 Security Business Act
treats 機械警備業務 -- mechanical/systems security -- as its own
registration category, 機械警備業務開始届出書, with its own statutory
response-time requirements distinct from ordinary guarding under the
same law), with a natural robotics-premise fit (installation-support
rigs, camera-alignment/cable-routing assist, periodic system-health
inspection).

This candidate required direct verification against a close sibling:
`cloud-itonami-isic-8010` ("Community Private Security Operations",
`ADR-2607100500`) already covers private security, and shares the
label "security" broadly. Directly read `8010`'s own `blueprint.edn`
and docs before selection: `8010` is scoped purely to PERSONNEL-based
guard/patrol services (its `required-technologies` include `:labor`
for guard-shift/timesheet management, with no mention anywhere of
alarms, CCTV or access-control systems). `"8020"` is scoped to the
SEPARATE business of ELECTRONIC security systems, where the security
function is performed by installed equipment rather than guard
presence -- confirmed non-redundant. No `blueprint.edn`/governor-
keyword collision found (`security-systems-governor`/`alarm-
monitoring-governor`/`electronic-security-governor` all grep-empty
fleet-wide). The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-N8020` -- note the N-prefix legacy scheme,
distinct from the more common H-prefix seen elsewhere) was confirmed
via a direct GitHub API 404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:security-systems-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword; distinct from `securityops`/8010's own
`:private-security-governor`.

### Decision 3: explicit "Scope note" distinguishing this vertical from `securityops`/8010

Following the same discipline established repeatedly this scope: the
README's own "Scope note" section directly names `cloud-itonami-isic-
8010`, quotes its scope (personnel-based guard/patrol services), and
explains the electronic-systems-vs-personnel distinction with
specific jurisdictional licensing citations, rather than merely
asserting non-redundancy.

### Decision 4: reuse `securityops`/8010's own labor-dispatch operating-states shape

The registry's own pre-existing `:operating-states` for `"8020"`
(`:intake :register :match :dispatch :follow-up :audit`) is IDENTICAL
to `"8010"`'s own -- this build reuses that shape verbatim, adapted
for electronic-systems-specific safety concerns (a monitored-alarm
response dispatched outside verified response-time scope, an
installation job without a completed permit/compliance check, an
unverified monitoring-center escalation) rather than guard-dispatch
concerns. This is the SAME legitimate shape-reuse precedent
established across the fleet -- first for `investigationops`/8030
reusing `securityops`/8010's own shape, now for a second sibling in
the same ISIC section.

### Decision 5: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-8020` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 127 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N8020"` to `"cloud-itonami-8020"` to match.

### Decision 6: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-8020` follows suit.

### Decision 7: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"8020"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated (`:optional-technologies`
gains `:optimization`, matching technician-routing/scheduling
optimization needs), and its explicit `:maturity :spec` override
REMOVED so `maturity`/`maturity-of` now auto-derive `:blueprint` from
the real `:repo` presence.

### Decision 8: test-suite consequence -- `:blueprint` count updated again

`ADR-2607103400`'s own `(is (= 21 (:blueprint m)))` assertion updated
to `(is (= 22 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"8020")))`) alongside the twenty-one prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 21 :spec 519 :total 646}`
  → `{:implemented 106 :blueprint 22 :spec 518 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 175
  assertions, all green.
- `cloud-itonami-isic-8020` joins the twenty-one prior fresh-blueprint
  picks as a twenty-second candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- Confirms the fleet-wide discipline of pausing a productive family
  (transport support) once it's structurally complete and deliberately
  diversifying into a fresh domain, rather than exhausting every
  possible sibling in one family before moving on.

## Alternatives considered

- **Continuing further into the transport-support family**
  (`"5310"`/`"5320"` postal/courier): still deliberately deferred per
  `ADR-2607103400`'s own reasoning; picking a fresh non-transport
  domain this pass was preferred for portfolio diversity.
- **`"8010"`-adjacent private-investigation/guard sub-verticals**: no
  other close sibling of `8010` was identified as cleanly distinct;
  `8020` was the clearest available pick in this ISIC section.

## References

- `kotoba-lang/industry` registry entry `"8020"`.
- `cloud-itonami/cloud-itonami-isic-8020` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-8010/blueprint.edn` (the sibling shape this
  build reuses, and the vertical its scope note distinguishes from).
