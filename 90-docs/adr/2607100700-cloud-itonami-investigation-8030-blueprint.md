# ADR-2607100700: cloud-itonami-isic-8030 (Community Investigation Services) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607100600 (foodserviceops/5610 -- second "author new blueprints
  from `:spec`" build)
- ADR-2607100500 (securityops/8010 -- first such build)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 98 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the THIRD "author a new blueprint from `:spec` tier" build
under the scope the user approved after the fleet-wide `:blueprint`
backlog reached zero (ADR-2607100300).

### Candidate selection and the crowded-waste-niche near-miss

`"3812"` (Collection of hazardous waste) was investigated first --
a strong candidate on its own regulatory merits (hazardous-waste
manifest/chain-of-custody law is genuinely rich and internationally
distinct: Japan's 廃棄物処理法 特別管理産業廃棄物管理票 system, the US's RCRA
cradle-to-grave manifest, the UK's hazardous-waste consignment note
system, Germany's Nachweisverordnung). It was set aside, not rejected
outright, because the waste/environmental niche is already occupied by
TWO adjacent blueprints: `cloud-itonami-isic-3830` ("Local Materials
Recovery" -- recycling/materials recovery, `:implemented`,
`:traceability-governor`) and `cloud-itonami-cofog-05.1` ("Independent
Municipal Waste Collection Robotics" -- a SEPARATE COFOG-classified
sub-fleet, not part of the ISIC-numbered `cloud-itonami-isic-*` series
this session builds, blueprint-stage only, `:waste-operations-
governor`). A hazardous-waste vertical would likely still be genuinely
distinct (a materially stricter manifest regime than either), but
picking a third waste-adjacent blueprint in a row felt like a weaker
use of this pass than finding a more clearly unoccupied niche --
deferred as a strong candidate for a FUTURE pass, not discarded.

`"8030"` (Investigation activities, ISIC Rev.4 class, section N) was
selected instead: private/licensed investigation is genuinely distinct
from `cloud-itonami-isic-8010`'s own private-security-guard-licensing
domain (same broader ISIC section N -- Administrative and support
service activities -- but a different licensing regime and regulatory
concern: armed/uniformed guard dispatch vs. case investigation,
surveillance and evidence handling), has its own real-world regulatory
texture (investigator licensing regimes vary meaningfully by
jurisdiction -- worth surfacing honestly at implementation time rather
than assumed uniform), and fits the robotics premise naturally
(surveillance/observation robots, document and evidence capture).
`:labor` is already required (investigator shift/timesheet management
via `kotoba-lang/labor`), the SAME capability-library-wrap candidate
pattern as `cloud-itonami-isic-8010`'s own. The legacy placeholder
`:repo` (`gftdcojp/cloud-itonami-N8030`) was confirmed via a direct
GitHub API 404 check to never have actually existed.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as `securityops`/8010's and `foodserviceops`/
5610's own blueprint publications: `README.md`, `blueprint.edn`,
`docs/business-model.md`, `docs/operator-guide.md`,
`CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `GOVERNANCE.md`, `SECURITY.md`,
`LICENSE` (AGPL-3.0-or-later, verbatim). No `src/`/`test/`/`deps.edn`/
`docs/adr/0001-architecture.md` -- reserved for a future
`:blueprint`->`:implemented` promotion pass.

### Decision 2: `:private-investigation-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this governor
keyword.

### Decision 3: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-8030` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 100 prior/sibling actors, rather than the legacy
`N####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-N8030"` to `"cloud-itonami-8030"` to match.

### Decision 4: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as `securityops`/8010's and `foodserviceops`/
5610's own builds: none of the `cloud-itonami-isic-*` repos are
registered as west projects. `cloud-itonami-isic-8030` follows suit.

### Decision 5: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"8030"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 6: test-suite consequence -- `:blueprint` count updated again

`ADR-2607100600`'s own `(is (= 2 (:blueprint m)))` assertion updated to
`(is (= 3 (:blueprint m)))`, continuing the "not a fixed invariant"
framing established by `ADR-2607100500`. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"8030")))`) alongside the existing synthetic-fixture-based unit test
and the `"8010"`/`"5610"` live-state corroboration tests.

## Consequences

- Fleet maturity: `{:implemented 98 :blueprint 2 :spec 543 :total 643}`
  → `{:implemented 98 :blueprint 3 :spec 542 :total 643}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 148
  assertions, all green.
- `cloud-itonami-isic-8030` joins `cloud-itonami-isic-8010` and
  `cloud-itonami-isic-5610` as a third candidate available for a future
  `:blueprint`->`:implemented` promotion pass.
- `"3812"` (Collection of hazardous waste) remains flagged as a strong
  candidate for a FUTURE authoring pass, deferred rather than rejected
  -- see Alternatives below.

## Alternatives considered

- **`"3812"` (Collection of hazardous waste)**: deferred, not
  rejected -- genuinely rich regulatory grounding, but the waste/
  environmental niche is already occupied by two adjacent blueprints
  (`cloud-itonami-isic-3830` materials recovery, `cloud-itonami-cofog-
  05.1` municipal waste collection). A future pass may still pick it,
  since hazardous-waste manifest law is materially distinct in
  stringency from either existing neighbor -- this ADR just prioritized
  a more clearly unoccupied niche for THIS pass.
- **Reusing `"8010"`'s own private-security domain instead of
  distinguishing investigation activities as its own vertical**:
  rejected -- armed/uniformed guard dispatch and licensed investigation
  work are governed by genuinely different regulatory regimes and
  should not be collapsed into one blueprint.

## References

- `kotoba-lang/industry` registry entry `"8030"`.
- `cloud-itonami/cloud-itonami-isic-8030` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-8010/blueprint.edn` (confirmed distinct
  private-security-only scope, no overlap).
- `cloud-itonami-isic-3830/blueprint.edn` and `cloud-itonami-cofog-
  05.1/blueprint.edn` (confirmed why `"3812"` was deferred this round).
