# ADR-2607100500: cloud-itonami-isic-8010 (Community Private Security Operations) published as a fresh `:blueprint`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607100300 (missionops/9900 -- fleet-wide `:blueprint` tier reached zero)
- ADR-2607100200 (domesticops/9700)
- The 98 prior actors' own ADR-0001s (implementation-stage precedent for
  this vertical's own eventual promotion)
- langgraph-clj ADR-0001

## Context

With cloud-itonami-isic-9900's own promotion (ADR-2607100300), every
published-but-unimplemented `:blueprint`-tier ISIC entry in the
`kotoba-lang/industry` registry had been implemented -- the standing
"pick a new ISIC blueprint vertical" authorization's own supply of
candidates was exhausted at both scope levels it had operated under
this session (the original `cloud-itonami/cloud-itonami-isic-*` scope
and its later gftdcojp-origin-registry extension). This is a genuine
judgment-call juncture the standing authorization's own carve-out
anticipates, so the user was asked how the recurring "coverage, 成熟度
を向上" loop should continue. The user selected: **author new
blueprints from `:spec` tier** -- a materially different, larger task
than promoting an existing blueprint, since it starts a vertical from
nothing (no published business model, no `blueprint.edn`, no registry
`:repo`) rather than implementing a scaffold someone already wrote.

This ADR records the FIRST such fresh-blueprint authoring pass:
`"8010"` (ISIC Rev.4 class, section N -- Administrative and support
service activities: private security activities).

### Candidate selection

A direct fleet-wide scan of 4-digit-class `:spec`-tier registry
entries found 327 candidates, ALL of which carried a stale legacy
`:repo` field of the form `https://github.com/gftdcojp/cloud-itonami-
N####` with an explicit `:maturity :spec` override (meaning these
placeholder URLs were never real published blueprints -- confirmed
for `"8010"`'s own placeholder via a direct GitHub API 404 check
before touching anything). `"8010"` (Private security activities) was
selected for having a genuinely rich, well-known, internationally-
distinct regulatory domain (private-security/guard licensing law
across JPN/US/UK/DE) matching the caliber of every prior vertical's
own real-world grounding, a clear robotics-premise fit (patrol/
perimeter-monitoring robots dispatched under human-gated governance),
and `:labor` already listed among its own `:required-technologies`
(guard shift/timesheet management via `kotoba-lang/labor`, a candidate
capability-library-wrap at implementation time, matching
`domesticops`/9700's own pattern) -- confirmed via a direct kotoba-lang
org search that no bespoke private-security-specific capability
library exists (`kotoba-lang/security` is Kotoba's own language/
runtime security-assurance framework; `kotoba-lang/securities` is
financial securities/funds -- neither fits this domain).

## Decision

### Decision 1: publish blueprint-only artifacts, no src/test yet

Following the SAME artifact set every prior actor's own `:blueprint`-
stage repo carried before its own implementation commit (reconstructed
from `cloud-itonami-isic-9900`'s own git history, commit `e53012c`):
`README.md`, `blueprint.edn`, `docs/business-model.md`, `docs/
operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later, verbatim).
Deliberately NO `src/`/`test/`/`deps.edn`/`docs/adr/0001-architecture.md`
-- those belong to the SEPARATE `:blueprint` -> `:implemented`
promotion this fleet's whole prior sequence has been doing, a future
follow-up, not this ADR's own scope.

### Decision 2: `:private-security-governor` -- grep-verified unique fleet-wide

No existing blueprint anywhere in this fleet declares this governor
keyword.

### Decision 3: real repo naming, replacing the dead legacy placeholder

`cloud-itonami/cloud-itonami-isic-8010` (public, matching every prior
vertical's own visibility -- confirmed via a direct API check against
two existing siblings), following the SAME `cloud-itonami-isic-####`
naming convention as all 98 prior actors, rather than the legacy
`N####`-prefixed placeholder scheme (which, per the candidate-
selection section above, never actually existed as a real repo).
`business-id` updated from `"cloud-itonami-N8010"` to
`"cloud-itonami-8010"` to match.

### Decision 4: `manifest/west.yml` registration -- deliberately NOT done

Unlike `kotoba-lang/*` capability libraries and actor repos following
the `etzhayyim/com-etzhayyim-*` convention, NONE of the 98 prior
`cloud-itonami-isic-*` repos are registered as west projects (verified:
zero `cloud-itonami-isic-*` entries anywhere in `manifest/west.yml`,
the only `cloud-itonami`-named entry being the separate main
`cloud-itonami` actor repo itself). These are plain standalone git
repos, tracked only via `kotoba-lang/industry`'s own `:repo` field on
each ISIC entry -- consistent with this, `cloud-itonami-isic-8010` is
NOT added to `manifest/repos.edn`'s `:extra-projects` or `west.yml`
either, matching established precedent exactly (an initial edit
attempting this registration was self-caught and reverted before
landing anything).

### Decision 5: `kotoba.industry` registry update -- field replacement, not a net-new entry

The existing `"8010"` registry entry (name/required-technologies/
operating-states already present from the original ISIC coverage
batch) had its `:repo`, `:business-id` and `:optional-technologies`
fields updated, and its explicit `:maturity :spec` override REMOVED so
`maturity`/`maturity-of` now auto-derive `:blueprint` from the real
`:repo` presence.

### Decision 6: test-suite consequence -- `:blueprint` count is not a fixed invariant

`ADR-2607100300`'s own `(is (zero? (:blueprint m)))` assertion (written
when the fleet-wide backlog had JUST reached zero) needed updating the
moment a new blueprint gets published, since that count naturally
fluctuates over time as blueprints are published and later implemented.
Changed to `(is (= 1 (:blueprint m)))` reflecting the current true
count, with a comment explaining the count is not asserted as a fixed
invariant. Also added a live-state corroboration assertion (`(is (=
:blueprint (industry/maturity "8010")))`) alongside the existing
synthetic-fixture-based unit test (`maturity-of`), since a real example
now exists again -- the synthetic-fixture test itself is kept
unchanged (it remains strictly better than depending on live state,
regardless of whether one currently exists).

### Self-caught-and-corrected error

The registry comment and `industry_test.clj`'s own comments initially
cited `ADR-2607100400` (chosen before checking the actual next-
available superproject ADR id) -- by the time this ADR was actually
being created, a concurrent session had already taken that id
(`cloud-itonami-petroleum-supply-chain-coverage`). Caught before this
ADR's own id was finalized; fixed via a dedicated comment-only follow-
up commit in `kotoba-lang/industry` (no functional/pin change),
following the SAME self-caught-and-corrected-error transparency
discipline established earlier this session (`employmentops`/7810's
own ADR-reference fix, `freightops`/4920's own `:optimization` fix).

## Consequences

- Fleet maturity: `{:implemented 98 :blueprint 0 :spec 545 :total 643}`
  → `{:implemented 98 :blueprint 1 :spec 544 :total 643}`, verified
  against `(kotoba.industry/maturity-summary)` ground truth and kept in
  exact sync in `docs/cloud-itonami.md`.
- `kotoba-lang/industry`'s own full test suite re-run with the
  local-root technology override before committing -- 7 tests / 146
  assertions, all green.
- `cloud-itonami-isic-8010` is now the FIRST candidate available for a
  future `:blueprint` -> `:implemented` promotion pass under this
  fleet's own established governed-actor architecture, whenever that
  is next taken up.
- This is the FIRST "author a new blueprint from `:spec`" build under
  the freshly-approved scope; the underlying `:spec`-tier pool (544
  remaining 4-digit-class candidates plus 3-digit-group entries) is far
  from exhausted, unlike the now-fully-drained `:blueprint`-tier pool
  this session spent its first several hours promoting.

## Alternatives considered

- **Reusing the legacy `N8010`-prefixed placeholder naming** (`cloud-
  itonami-N8010`) instead of the established `cloud-itonami-isic-####`
  convention: rejected -- would introduce a second, inconsistent naming
  scheme into a fleet that has used one convention across 98 prior
  actors, and the placeholder repo never actually existed regardless.
- **Registering the new repo in `manifest/west.yml`**: rejected --
  no precedent for this among 98 prior siblings; an initial attempt
  was self-caught and reverted before landing.
- **Picking a candidate already implied by an existing capability
  library** (to guarantee a capability-library-wrap at implementation
  time): rejected as the primary selection criterion -- `"8010"` was
  chosen first for its own genuine regulatory richness and robotics-
  premise fit; the `:labor` capability-library-wrap potential is a
  bonus, not the deciding factor.

## References

- `kotoba-lang/industry` registry entry `"8010"`.
- `cloud-itonami/cloud-itonami-isic-8010` repo (blueprint-stage only,
  no ADR-0001 yet -- that arrives with the future implementation pass).
- `cloud-itonami-isic-9900/README.md`'s own git history (commit
  `e53012c`), the template this blueprint's own scaffold was
  reconstructed from.
