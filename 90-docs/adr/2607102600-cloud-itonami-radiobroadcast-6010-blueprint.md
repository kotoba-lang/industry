# ADR-2607102600: cloud-itonami-isic-6010 (Community Radio Broadcasting Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607102500 (tvbroadcastops/6020 -- fourteenth "author new
  blueprints from `:spec`" build, first fresh-domain pick)
- ADR-2607101600 (plumbingops/4322 -- established the low-friction-
  sibling-pick precedent)
- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier
  reached zero)
- The 106 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

This is the FIFTEENTH "author a new blueprint from `:spec` tier"
build. `ADR-2607102500`'s own Consequences section explicitly flagged
`"6010"` (Radio broadcasting) as "a natural, clearly-distinct future
candidate sharing the same shape and licensing-domain flavor" as
`"6020"` (television broadcasting) -- this build picks it up,
following the SAME low-friction-sibling-pick pattern
`plumbingops`/4322 established for `electricalops`/4321.

### Candidate selection

`"6010"` requires no fresh redundancy screening beyond what
`ADR-2607102500` already established for the broadcasting domain
generally: radio and television broadcasting are each licensed
separately in every jurisdiction checked (Japan's 放送法/電波法 dual
licensing for spectrum and broadcast content, the US's FCC issuing
distinct radio and television broadcast licenses, the UK's Ofcom
licensing radio and television separately, Germany's
Landesmedienanstalten licensing each medium under its own class),
confirming radio broadcasting is a genuinely distinct licensed medium
from television broadcasting, not a restatement of it. The legacy
placeholder `:repo` (`gftdcojp/cloud-itonami-J6010`) was confirmed via
a direct GitHub API 404 check to never have actually existed. No
bespoke capability library exists for radio broadcasting specifically.

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

The SAME artifact set as every prior fresh-blueprint publication this
scope: `README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later,
verbatim). No `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- reserved for a future `:blueprint`->`:implemented` promotion pass.

### Decision 2: `:radio-broadcast-governor` -- grep-verified unique fleet-wide

Distinct from `cloud-itonami-isic-6020`'s own `:broadcast-license-
governor`.

### Decision 3: reuse the telecom-service shape and the tvbroadcastops/6020 framing verbatim

The "Core Contract" and Trust Controls text follows the SAME framing
`tvbroadcastops`/6020 established (`:intake :provision :route :bill
:support :audit`, transmission gated on verified license scope,
public-interest-programming concerns), adapted only for radio-specific
language (transmitter/studio-equipment rather than television-
specific terms).

### Decision 4: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-6010` (public, matching every prior
vertical's own visibility), following the SAME `cloud-itonami-isic-####`
convention as all 120 prior/sibling actors, rather than the legacy
`J####`-prefixed placeholder scheme. `business-id` updated from
`"cloud-itonami-J6010"` to `"cloud-itonami-6010"` to match.

### Decision 5: `manifest/west.yml` registration -- deliberately NOT done

Same established precedent as every prior fresh-blueprint build this
scope: none of the `cloud-itonami-isic-*` repos are registered as west
projects. `cloud-itonami-isic-6010` follows suit.

### Decision 6: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"6010"` registry entry had its `:repo`, `:business-id`
and `:optional-technologies` fields updated, and its explicit
`:maturity :spec` override REMOVED so `maturity`/`maturity-of` now
auto-derive `:blueprint` from the real `:repo` presence.

### Decision 7: test-suite consequence -- `:blueprint` count updated again

`ADR-2607102500`'s own `(is (= 14 (:blueprint m)))` assertion updated
to `(is (= 15 (:blueprint m)))`, continuing the "not a fixed
invariant" framing established by `ADR-2607100500`. Added a live-
state corroboration assertion (`(is (= :blueprint (industry/maturity
"6010")))`) alongside the fourteen prior live-state corroboration
tests and the synthetic-fixture-based unit test.

## Consequences

- Fleet maturity: `{:implemented 106 :blueprint 14 :spec 526 :total 646}`
  → `{:implemented 106 :blueprint 15 :spec 525 :total 646}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 168
  assertions, all green.
- `cloud-itonami-isic-6010` joins the fourteen prior fresh-blueprint
  picks as a fifteenth candidate available for a future `:blueprint`->
  `:implemented` promotion pass.
- Confirms the low-friction-sibling-pick pattern (`plumbingops`/4322
  -> `electricalops`/4321) generalizes cleanly to a second domain
  family (broadcasting).

## Alternatives considered

- None substantively -- this candidate was explicitly pre-flagged as a
  clean sibling at `ADR-2607102500`, and the screening confirmed it
  needed no further disambiguation work.

## References

- `kotoba-lang/industry` registry entry `"6010"`.
- `cloud-itonami/cloud-itonami-isic-6010` repo (blueprint-stage only,
  no ADR-0001 yet -- arrives with a future implementation pass).
- `cloud-itonami-isic-6020/blueprint.edn` (the sibling shape and
  framing this build reuses).
