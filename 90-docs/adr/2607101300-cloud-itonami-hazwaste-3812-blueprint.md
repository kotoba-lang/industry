# ADR-2607101300: cloud-itonami-isic-3812 (Community Hazardous Waste Collection) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607100800 (meddeviceops/2660 -- fourth "author new blueprints
  from `:spec`" build, first manufacturing-sector pick)
- ADR-2607100700 (investigationops/8030 -- where `"3812"` was first
  flagged and deferred)
- ADR-2607100600 (foodserviceops/5610)
- ADR-2607100500 (securityops/8010)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s (concurrent session activity
  landed 8 petroleum-fleet actors between ADR-2607100800 and this ADR,
  bringing `:implemented` from 98 to 106 and `:total` from 643 to 646 --
  unrelated to this build, reflected in the counts below)
- langgraph-clj ADR-0001

## Context

This is the FIFTH "author a new blueprint from `:spec` tier" build.
`"3812"` (Collection of hazardous waste) was flagged as a strong
candidate at `ADR-2607100700` but deferred at that time to avoid
picking a third waste-adjacent blueprint in a row (the niche already
held `cloud-itonami-isic-3830`, materials recovery, and
`cloud-itonami-cofog-05.1`, municipal waste collection). Since then,
`ADR-2607100800` diversified into a manufacturing-sector pick
(`"2660"`, medical device manufacturing), so this build returns to
`"3812"` with the crowding concern addressed by that intervening
diversification.

### Distinguishing from the two existing waste-adjacent blueprints

- `cloud-itonami-isic-3830` ("Local Materials Recovery", `:implemented`,
  `:traceability-governor`): general recyclables/materials recovery,
  a circular-economy concern.
- `cloud-itonami-cofog-05.1` ("Independent Municipal Waste Collection
  Robotics", a SEPARATE COFOG-classified sub-fleet, blueprint-stage
  only, `:waste-operations-governor`): general municipal garbage
  collection, a public-service concern.
- `"3812"` (this build): hazardous industrial/medical/chemical waste,
  governed by a materially STRICTER manifest/chain-of-custody regime
  than either neighbor (Japan's 廃棄物処理法 特別管理産業廃棄物管理票 system, the
  US's RCRA cradle-to-grave manifest, the UK's hazardous-waste
  consignment note system, Germany's Nachweisverordnung) -- a
  genuinely distinct regulatory tier, not a restatement of either
  existing vertical.

The legacy placeholder `:repo` (`gftdcojp/cloud-itonami-E3812`) was
confirmed via a direct GitHub API 404 check to never have actually
existed. No bespoke capability library exists for hazardous-waste
manifest management specifically.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:hazardous-waste-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this exact
governor keyword (distinct from `:traceability-governor` and
`:waste-operations-governor`, the two neighboring waste-adjacent
verticals' own keywords).

### Decision 3: sequential intake/observe/treat/publish shape, matching the existing registry entry's own operating-states

This vertical's own registry-declared operating-states
(`:intake :observe :treat :publish :audit`) already imply a natural
SEQUENTIAL shape: intake waste -> observe/classify -> treat (dispatch
collection/transport) -> publish (chain-of-custody manifest record) ->
audit. The blueprint's own Core Contract text follows this shape
directly rather than reusing a generic dispatch/report framing
verbatim.

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-3812` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 106 prior/sibling actors, rather than the legacy
`E####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-E3812"` to `"cloud-itonami-3812"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-3812` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"3812"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again, `:implemented` count reflects concurrent petroleum-fleet activity

`ADR-2607100800`'s own `(is (= 4 (:blueprint m)))` assertion updated to
`(is (= 5 (:blueprint m)))`. Separately, a concurrent session's own
"register the 8 ADR-2607100400 petroleum-fleet actors" commit landed
between `ADR-2607100800` and this ADR, correctly bumping `:implemented`
from 98 to 106 and `:total` from 643 to 646 (that session updated the
test file's own `:implemented` assertion and numbered-actor tests
itself; this ADR only touches the `:blueprint` line and adds its own
live-state corroboration test). Added a live-state corroboration
assertion (`(is (= :blueprint (industry/maturity "3812")))`) alongside
the four prior live-state corroboration tests and the synthetic-
fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 4 :spec 536 :total 646}`
  → `{:implemented 106 :blueprint 5 :spec 535 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 158
  assertions, all green.
- `cloud-itonami-isic-3812` joins `cloud-itonami-isic-8010`,
  `cloud-itonami-isic-5610`, `cloud-itonami-isic-8030` and
  `cloud-itonami-isic-2660` as a fifth candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- Confirms the "defer, don't discard" discipline established at
  `ADR-2607100700`: a genuinely strong candidate flagged as deferred
  rather than rejected was picked up two builds later once the
  crowding concern that motivated the deferral was addressed.

## Alternatives considered

- **Picking `"3812"` immediately at `ADR-2607100700`**: rejected at
  that time -- would have been a third waste-adjacent pick in a row.
  Correctly deferred instead of forced.
- **Reusing `:waste-operations-governor` (from `cloud-itonami-cofog-
  05.1`) instead of a fresh keyword**: rejected -- these are genuinely
  distinct verticals (municipal garbage vs. hazardous industrial/
  medical/chemical waste) with materially different regulatory
  regimes; sharing a governor keyword would blur that distinction.

## References

- `kotoba-lang/industry` registry entry `"3812"`.
- `cloud-itonami/cloud-itonami-isic-3812` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-3830/blueprint.edn` and `cloud-itonami-cofog-
  05.1/blueprint.edn` (confirmed genuine distinction, not redundancy).
- 廃棄物処理法 (Waste Management and Public Cleansing Act), 特別管理産業廃棄物管理票
  system (Japan).
- Resource Conservation and Recovery Act (RCRA), EPA hazardous-waste
  generator/transporter/TSDF manifest system (US).
- Hazardous Waste (England and Wales) Regulations 2005, consignment
  note system (UK).
- Nachweisverordnung (NachwV) (Germany).
