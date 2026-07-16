# ADR-2607101500: cloud-itonami-isic-4321 (Community Electrical Installation) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607101400 (basicchemops/2011 -- sixth "author new blueprints
  from `:spec`" build, second manufacturing-lifecycle pick)
- ADR-2607100800 (meddeviceops/2660 -- first manufacturing-lifecycle
  pick)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the SEVENTH "author a new blueprint from `:spec` tier" build.
Two candidates remained flagged as deferred from `ADR-2607101400`
(`"3510"` electric power, `"6120"` wireless telecommunications), each
needing careful redundancy-avoiding framing against adjacent existing
verticals before being safely picked. Rather than force either through
this round, a fresh candidate screening surfaced `"4321"` (Electrical
installation, ISIC Rev.4 class, section F -- Construction), which
turned out to introduce a genuinely NEW operating-states shape for
this fleet.

### A third distinct operating-states shape

Every fresh-blueprint pick so far in this `:spec`-authoring scope has
followed one of two shapes: the services shape (`intake -> dispatch ->
report -> audit`, `securityops`/8010, `foodserviceops`/5610,
`investigationops`/8030, `hazwasteops`/3812) or the manufacturing
shape (`spec -> design -> produce -> inspect -> package -> audit`,
`meddeviceops`/2660, `basicchemops`/2011). `"4321"`'s own registry-
declared operating-states (`:intake :design :permit :build :inspect
:audit`) is a THIRD, genuinely distinct shape: a PERMIT-BASED
construction/trade lifecycle, where the governed act is obtaining and
respecting a permit before physical work, and energizing/completing an
installation only after inspection sign-off -- distinct from both a
service dispatch and a manufacturing batch release.

### Candidate selection

`"4321"` (Electrical installation) was selected for a genuinely rich,
well-known, internationally-distinct regulated-trades domain (licensed
electrician requirements: Japan's 電気工事士法/電気工事業法, US state/local
electrical contractor licensing plus National Electrical Code
compliance, the UK's Part P Building Regulations plus competent-person
schemes, Germany's Handwerksordnung Meisterpflicht for the electrical
trade plus VDE standards), no redundancy with `cloud-itonami-isic-
4211`'s own general building-construction scope (confirmed by reading
its own `blueprint.edn` -- electrical trade licensing is a separate,
specialized regulatory regime from general construction, licensed and
regulated independently in every jurisdiction checked), and a clean
robotics-premise fit (conduit/cable installation, panel assembly and
inspection robots). The legacy placeholder `:repo`
(`gftdcojp/cloud-itonami-F4321`) was confirmed via a direct GitHub API
404 check to never have actually existed. No bespoke capability
library exists for electrical trade licensing specifically.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:electrical-trade-governor` -- grep-verified unique within the ISIC-numbered series

Distinct from `:electrical-install-governor`, used by the SEPARATE
`cloud-itonami-unspsc-39` UNSPSC-classified sub-fleet -- a deliberate
choice not to reuse a similar-sounding keyword from a different
sub-fleet, to avoid any confusion between the two.

### Decision 3: permit-based construction/trade framing, a genuinely new shape

The "Core Contract" and Trust Controls text follows this vertical's
own registry-declared operating-states directly (a permit obtained
before build, an inspection/energization sign-off after) rather than
reusing either the services or manufacturing framing established by
prior picks -- see the "A third distinct operating-states shape"
section above.

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-4321` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 112 prior/sibling actors, rather than the legacy
`F####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-F4321"` to `"cloud-itonami-4321"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-4321` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"4321"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607101400`'s own `(is (= 6 (:blueprint m)))` assertion updated to
`(is (= 7 (:blueprint m)))`, continuing the "not a fixed invariant"
framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"4321")))`) alongside the six prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 6 :spec 534 :total 646}`
  → `{:implemented 106 :blueprint 7 :spec 533 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 160
  assertions, all green.
- `cloud-itonami-isic-4321` joins `cloud-itonami-isic-8010`,
  `cloud-itonami-isic-5610`, `cloud-itonami-isic-8030`,
  `cloud-itonami-isic-2660`, `cloud-itonami-isic-3812` and
  `cloud-itonami-isic-2011` as a seventh candidate available for a
  future `:blueprint`->`:implemented` promotion pass.
- Establishes a THIRD operating-states shape for this scope's own
  fresh-blueprint picks -- permit-based construction/trade -- alongside
  the services and manufacturing shapes already established, widening
  the diversity of what a "genuinely distinct pick" can look like.
- `"3510"` (electric power) and `"6120"` (wireless telecommunications)
  remain flagged as candidates for a FUTURE pass, still pending careful
  redundancy-avoiding framing.

## Alternatives considered

- **`"3510"`/`"6120"` (again)**: not force-picked this round either --
  the careful-framing work these two need is real and deferring them a
  second round is preferable to a rushed, under-justified pick.
- **`"4322"`** (Plumbing, heat and air-conditioning installation): a
  near-identical sibling of `"4321"` sharing the same operating-states
  shape and licensing-domain flavor -- set aside in favor of `"4321"`
  as the FIRST pick in this permit-based shape, leaving `"4322"` as a
  natural, clearly-distinct future candidate (a different trade,
  different licensing body in most jurisdictions) rather than picking
  both in the same round.

## References

- `kotoba-lang/industry` registry entry `"4321"`.
- `cloud-itonami/cloud-itonami-isic-4321` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-4211/blueprint.edn` (confirmed genuine
  distinction from general building construction).
- `cloud-itonami-unspsc-39/blueprint.edn` (confirmed the SEPARATE
  sub-fleet's own similar-sounding governor keyword, avoided rather
  than reused).
