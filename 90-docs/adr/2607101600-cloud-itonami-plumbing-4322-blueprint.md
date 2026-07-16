# ADR-2607101600: cloud-itonami-isic-4322 (Community Plumbing and HVAC Installation) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607101500 (electricalops/4321 -- seventh "author new
  blueprints from `:spec`" build, introduced the permit-based
  construction/trade shape)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the EIGHTH "author a new blueprint from `:spec` tier" build.
`ADR-2607101500` explicitly flagged `"4322"` (Plumbing, heat and
air-conditioning installation) as "a natural, clearly-distinct future
candidate sharing the same permit-based shape" as `"4321"`
(Electrical installation) -- this build picks it up.

Unlike the prior three builds (each of which needed to screen out or
defer at least one near-neighbor candidate), `"4322"` required no such
screening: it shares `"4321"`'s own operating-states shape by design
(both are ISIC section F, division 43, "Specialized construction
activities," subclasses of the same permit-based construction/trade
pattern), but is governed by a genuinely SEPARATE licensing regime in
every jurisdiction checked (plumbing/HVAC licensing bodies are
distinct from electrical licensing bodies: Japan's 給水装置工事主任技術者
qualification is separate from 電気工事士; most US states license
plumbers and electricians under separate boards; the UK's Gas Safe
Register governs gas-fitting work specifically, separate from the
Part P electrical competent-person schemes; Germany's Handwerksordnung
lists Installateur und Heizungsbauer as its own distinct Meister
trade, separate from Elektrotechniker). Reusing an already-established
operating-states shape for a genuinely distinct regulatory domain
matches the precedent `investigationops`/8030 set when it reused
`securityops`/8010's own service-dispatch shape.

The legacy placeholder `:repo` (`gftdcojp/cloud-itonami-F4322`) was
confirmed via a direct GitHub API 404 check to never have actually
existed. No bespoke capability library exists for plumbing/HVAC trade
licensing specifically.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:plumbing-trade-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword -- distinct from `cloud-itonami-isic-4321`'s own
`:electrical-trade-governor`.

### Decision 3: reuse the permit-based shape established at ADR-2607101500 for a genuinely distinct trade

The "Core Contract" and Trust Controls text follows the SAME
permit-based framing `"4321"` established (permit before build,
inspection/commissioning sign-off after), adapted for plumbing/HVAC-
specific safety concerns (gas-line and pressure-system work outside a
licensed tradesperson's verified scope, rather than energization
outside an electrician's verified scope).

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-4322` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 113 prior/sibling actors, rather than the legacy
`F####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-F4322"` to `"cloud-itonami-4322"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-4322` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"4322"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607101500`'s own `(is (= 7 (:blueprint m)))` assertion updated to
`(is (= 8 (:blueprint m)))`, continuing the "not a fixed invariant"
framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"4322")))`) alongside the seven prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 7 :spec 533 :total 646}`
  → `{:implemented 106 :blueprint 8 :spec 532 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 161
  assertions, all green.
- `cloud-itonami-isic-4322` joins `cloud-itonami-isic-8010`,
  `cloud-itonami-isic-5610`, `cloud-itonami-isic-8030`,
  `cloud-itonami-isic-2660`, `cloud-itonami-isic-3812`,
  `cloud-itonami-isic-2011` and `cloud-itonami-isic-4321` as an
  eighth candidate available for a future `:blueprint`->`:implemented`
  promotion pass.
- Confirms the "reuse a shape for a genuinely distinct domain" pattern
  is a legitimate, low-friction path when a natural sibling candidate
  is already flagged -- this build needed essentially no redundancy
  screening, unlike the three builds immediately preceding it.

## Alternatives considered

- None substantively -- this candidate was explicitly pre-flagged as
  clean at `ADR-2607101500`, and the screening confirmed it needed no
  further disambiguation work.

## References

- `kotoba-lang/industry` registry entry `"4322"`.
- `cloud-itonami/cloud-itonami-isic-4322` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-4321/blueprint.edn` (the sibling shape this
  build reuses).
