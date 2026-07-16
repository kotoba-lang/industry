# ADR-2607100600: cloud-itonami-isic-5610 (Community Food Service Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607100500 (securityops/8010 -- first "author new blueprints from
  `:spec`" build)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier reached
  zero)
- The 98 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the SECOND "author a new blueprint from `:spec` tier" build
under the scope the user approved after the fleet-wide `:blueprint`
backlog reached zero (ADR-2607100300). Following the same
candidate-selection discipline established for `securityops`/8010, a
fresh scan of remaining 4-digit-class `:spec`-tier entries was run
across professional/technical-services ranges to find a
well-differentiated, well-grounded next candidate.

### Candidate selection and redundancy screening

`"4923"` (Freight transport by road) was considered first but REJECTED
after discovering `cloud-itonami-isic-4920` ("Community Freight
Transport", already `:implemented`, `:freight-governor`) already covers
the same real-world business concept under ISIC Rev.5's own numbering
-- picking `"4923"` would have been redundant with an existing actor,
not a genuinely new vertical. This is an important discipline for the
`:spec`-authoring scope specifically: unlike the earlier `:blueprint`
->`:implemented` promotion scope (where every candidate was, by
definition, already a distinct published business), a fresh `:spec`
scan can surface an ISIC Rev.4 class that is the SAME underlying
business as an already-implemented ISIC Rev.5 entry -- always cross-
check a candidate's own name/domain against every existing `blueprint.
edn` before committing to it.

`"5610"` (Restaurants and mobile food service activities, ISIC Rev.4
class, section I) was selected instead: genuinely distinct from
`cloud-itonami-isic-5510`'s own "Community Accommodation Operations"
(same broader ISIC section I -- Accommodation and food service
activities -- but a different subsector: lodging vs. food service),
a rich and well-known internationally-distinct regulatory domain (food
hygiene/safety law), and a natural robotics-premise fit (food-
preparation, plating and delivery robots). The legacy placeholder
`:repo` (`gftdcojp/cloud-itonami-I5610`) was confirmed via a direct
GitHub API 404 check to never have actually existed, matching the SAME
pattern found for `"8010"`'s own placeholder. No bespoke capability
library exists for food service specifically (a kotoba-lang org search
for food/restaurant/kitchen/catering/nutrition returned zero hits).

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as `securityops`/8010's own blueprint publication
(and every prior actor's own pre-implementation state, reconstructed
from `cloud-itonami-isic-9900`'s own git history): `README.md`,
`blueprint.edn`, `docs/business-model.md`, `docs/operator-guide.md`,
`CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `GOVERNANCE.md`, `SECURITY.md`,
`LICENSE` (AGPL-3.0-or-later, verbatim). No `src/`/`test/`/`deps.edn`/
`docs/adr/0001-architecture.md` -- reserved for a future
`:blueprint`->`:implemented` promotion pass.

### Decision 2: `:food-service-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this governor
keyword (one unrelated hit for `:food-safety-compliance-governor` in
`cloud-itonami-iso3166-jpn-maff`, a DIFFERENT sub-fleet entirely --
country/agency-specific market-entry compliance services, not the
ISIC-numbered `cloud-itonami-isic-*` governed-actor fleet, and
explicitly `:itonami.blueprint/robotics false` -- no overlap).

### Decision 3: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-5610` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 99 prior/sibling actors, rather than the legacy
`I####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-I5610"` to `"cloud-itonami-5610"` to match.

### Decision 4: `manifest/west.yml` registration -- deliberately NOT done

Same precedent as `securityops`/8010's own build: none of the
`cloud-itonami-isic-*` repos are registered as west projects.
`cloud-itonami-isic-5610` follows suit -- no `:extra-projects`/
`west.yml` entry.

### Decision 5: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"5610"` registry entry (name/required-technologies/
operating-states already present from the original ISIC coverage
batch) had its `:repo`, `:business-id` and `:optional-technologies`
fields updated, and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 6: test-suite consequence -- `:blueprint` count updated again

`ADR-2607100500`'s own `(is (= 1 (:blueprint m)))` assertion needed
updating the moment this second new blueprint was published. Changed
to `(is (= 2 (:blueprint m)))`, continuing the same "not a fixed
invariant" framing `ADR-2607100500` established. Added a live-state
corroboration assertion (`(is (= :blueprint (industry/maturity
"5610")))`) alongside the existing synthetic-fixture-based unit test
and the `"8010"` live-state corroboration test, all three kept
side-by-side.

## Consequences

- Fleet maturity: `{:implemented 98 :blueprint 1 :spec 544 :total 643}`
  → `{:implemented 98 :blueprint 2 :spec 543 :total 643}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 147
  assertions, all green.
- `cloud-itonami-isic-5610` joins `cloud-itonami-isic-8010` as a second
  candidate available for a future `:blueprint`->`:implemented`
  promotion pass under this fleet's own established governed-actor
  architecture.
- Established a new discipline for this scope specifically: cross-check
  every fresh `:spec`-tier candidate's own name/domain against every
  existing `blueprint.edn` before committing, since ISIC Rev.4/Rev.5
  numbering differences can otherwise surface a business that is
  already implemented under a different code (see the rejected
  `"4923"` candidate above).

## Alternatives considered

- **`"4923"` (Freight transport by road)**: rejected -- redundant with
  the already-implemented `cloud-itonami-isic-4920` (Community Freight
  Transport), confirmed by directly reading its own `blueprint.edn`
  before committing to either candidate.
- **`"8030"` (Investigation activities)**: considered as a same-section
  alternative to `"8010"`'s own private-security domain, but deferred
  in favor of `"5610"`'s own more clearly distinct regulatory domain
  (food hygiene vs. a second licensing/investigation angle adjacent to
  `"8010"`'s own).
- **`"2520"` (Manufacture of weapons and ammunition)**: considered for
  its rich regulatory hook (arms-export control law) but rejected --
  publishing an open, forkable business blueprint for arms manufacture
  raises dual-use/ethical concerns disproportionate to this fleet's own
  community-operator-focused mission, unlike every other vertical built
  so far.

## References

- `kotoba-lang/industry` registry entry `"5610"`.
- `cloud-itonami/cloud-itonami-isic-5610` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-5510/blueprint.edn` (confirmed distinct
  accommodation-only scope, no overlap).
- `cloud-itonami-isic-4920/blueprint.edn` (confirmed `"4923"` would
  have been redundant with this already-implemented actor).
