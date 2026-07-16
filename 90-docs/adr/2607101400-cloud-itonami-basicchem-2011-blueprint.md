# ADR-2607101400: cloud-itonami-isic-2011 (Community Basic Chemicals Manufacturing) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607101300 (hazwasteops/3812 -- fifth "author new blueprints
  from `:spec`" build)
- ADR-2607100800 (meddeviceops/2660 -- first manufacturing-lifecycle
  pick)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the SIXTH "author a new blueprint from `:spec` tier" build,
and the SECOND manufacturing-lifecycle pick (following the
`:spec :design :produce :inspect :package :audit` shape established at
`ADR-2607100800`, rather than the earlier services/dispatch shape).

### Candidate screening and rejected/deferred alternatives this round

Several candidates were investigated and set aside before settling on
`"2011"`:

- **`"3510"`** (Electric power generation, transmission and
  distribution): deferred -- genuinely rich grid-reliability
  regulatory grounding, but `cloud-itonami-isic-3512` ("Community
  Renewable Energy Operations", `:implemented`, `:grid-policy-
  governor`) already occupies adjacent territory (community-scale
  renewable generation) within the same ISIC division 35. Picking the
  broader class now would need careful framing (transmission/
  distribution operations, distinct from generation) to avoid
  redundancy -- deferred rather than forced through without that care.
- **`"6120"`** (Wireless telecommunications activities): deferred --
  `cloud-itonami-isic-6110` (wired network operations) and
  `cloud-itonami-isic-6190` ("Community Telecommunications Access",
  `:robotics true`, requires `:phone`) already occupy the
  telecommunications niche closely enough that a mobile/cellular-
  carrier vertical would need careful spectrum-specific framing to
  avoid overlap -- deferred rather than forced through.
- **`"1200"`** (Manufacture of tobacco products): rejected on the same
  grounds as `"2520"` (weapons manufacturing, rejected at
  `ADR-2607100600`) -- publishing an open, forkable business blueprint
  for a product engineered to be addictive and harmful carries
  dual-use/public-health-harm concerns disproportionate to this
  fleet's community-operator mission, regardless of it being a
  legal, regulated industry.
- **`"1101"`** (Distilling, rectifying and blending of spirits):
  considered briefly, set aside in favor of `"2011"`'s own richer,
  more universally-applicable regulatory grounding (industrial
  chemical safety applies far more broadly than beverage-alcohol
  production specifically).

`"2011"` (Manufacture of basic chemicals) was selected: genuinely rich,
well-known, internationally-distinct regulatory grounding (REACH in
the EU, Japan's 化審法 Act on the Evaluation of Chemical Substances, the
US's TSCA, Germany under REACH via BAuA), no redundancy with any
existing blueprint (confirmed via grep across every `blueprint.edn`),
a clean robotics-premise fit (process-operation, sampling and
inspection robots), and none of the dual-use/harm-disproportionality
concerns that ruled out weapons and tobacco. The legacy placeholder
`:repo` (`gftdcojp/cloud-itonami-C2011`) was confirmed via a direct
GitHub API 404 check to never have actually existed. No bespoke
capability library exists for chemical manufacturing specifically.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:chemical-safety-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword.

### Decision 3: manufacturing-lifecycle framing, matching meddeviceops/2660's own precedent

The "Core Contract" and Trust Controls text follows the SAME
manufacturing-lifecycle framing `ADR-2607100800` established (batch
release as the governed "real-world act" analog to a service
vertical's own actuation op), adapted for chemical-process safety
specifically (process-parameter changes and batch release without
verification, rather than device-design changes and device-batch
release).

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-2011` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 111 prior/sibling actors, rather than the legacy
`C####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-C2011"` to `"cloud-itonami-2011"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-2011` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"2011"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607101300`'s own `(is (= 5 (:blueprint m)))` assertion updated to
`(is (= 6 (:blueprint m)))`, continuing the "not a fixed invariant"
framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"2011")))`) alongside the five prior live-state corroboration tests
and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 5 :spec 535 :total 646}`
  → `{:implemented 106 :blueprint 6 :spec 534 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 159
  assertions, all green.
- `cloud-itonami-isic-2011` joins `cloud-itonami-isic-8010`,
  `cloud-itonami-isic-5610`, `cloud-itonami-isic-8030`,
  `cloud-itonami-isic-2660` and `cloud-itonami-isic-3812` as a sixth
  candidate available for a future `:blueprint`->`:implemented`
  promotion pass.
- `"3510"` (electric power) and `"6120"` (wireless telecommunications)
  remain flagged as candidates for a FUTURE pass, deferred pending
  careful redundancy-avoiding framing against their respective
  adjacent existing verticals.
- Establishes a second explicit ethical-screening precedent alongside
  weapons manufacturing (`ADR-2607100600`): tobacco manufacturing was
  also ruled out on the same dual-use/public-health-harm-
  disproportionality grounds.

## Alternatives considered

See the "Candidate screening" section above for `"3510"`, `"6120"`,
`"1200"` and `"1101"`.

## References

- `kotoba-lang/industry` registry entry `"2011"`.
- `cloud-itonami/cloud-itonami-isic-2011` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-3512/blueprint.edn`, `cloud-itonami-isic-6110/
  blueprint.edn` and `cloud-itonami-isic-6190/blueprint.edn` (confirmed
  why `"3510"`/`"6120"` were deferred this round).
