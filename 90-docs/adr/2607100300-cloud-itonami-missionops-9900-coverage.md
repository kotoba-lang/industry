# ADR-2607100300: cloud-itonami-isic-9900 (Community Mission Operations Support) deepened to `:implemented` -- fleet-wide `:blueprint` tier reaches zero

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607100200 (domesticops/9700 -- third capability-library-wrapping vertical)
- ADR-2607092600 (libraryops/9101)
- ADR-2607092500 (adminops/8411)
- ADR-2607092400 (employmentops/7810)
- ADR-2607092300 (practiceops/7110 -- first governor-name collision)
- ADR-2607092200 (hospitalityops/5510)
- ADR-2607091900 (agronomyops/0162)
- ADR-2607091800 (quarryops/0810)
- ADR-2607091700 (freightops/4920)
- ADR-2607091600 (retailops/4711)
- ADR-2607091400 (ictrepair/9511)
- ADR-2607032000 (insurance/real-estate coverage)
- The 97 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-9900` (Community Mission Operations Support, ISIC
Rev.4 class 9900, section U -- Activities of extraterritorial
organizations and bodies) was selected under the standing "pick a new
ISIC blueprint vertical" authorization. Unlike every prior build in
this window, it was not merely the *next* candidate in the
gftdcojp-origin-registry scope extension -- a direct fleet-wide scan
via `(kotoba.industry/maturity-summary)` and a full enumeration of
every `:blueprint`-tier entry confirmed `"9900"` was the **only**
`:blueprint`-tier entry left anywhere in the entire 643-entry
registry, not merely within the gftdcojp-origin scope. Promoting it
was therefore known, going in, to bring the fleet-wide `:blueprint`
count to zero.

A kotoba-lang org search for mission/diplomatic/extraterritorial/
consular/aid/deployment returned zero hits -- no bespoke capability
library exists for this domain. `kotoba-lang/robotics`, named in the
blueprint's own README, is the same generic cross-cutting robotics
contract every vertical already resolves via `kotoba.technology`, not
domain-specific. This blueprint's own `:itonami.blueprint/governor`
keyword, `:mission-operations-governor`, is grep-verified unique
fleet-wide -- clean on the first attempt.

## Decision

Build the full governed-actor architecture for missionops/9900,
following the same template as all 97 prior actors:

- **Store**: `missionops.store`, MemStore + DatomicStore, proven
  parity via contract test.
- **Registry**: `missionops.registry`, pure DRAFT-certificate
  construction via `unsigned-certificate`, jurisdiction-scoped
  sequence numbering (`JPN-DSP-000007`, `JPN-RPT-000007`).
- **Governor**: `:mission-operations-governor` (grep-verified unique
  fleet-wide).
- **Entity shape**: `deployment`, no `:kind` discriminator. Mission
  dispatch and public-report publication are **sequential** acts on
  the same record (dispatch first, report later) -- matching
  `domesticops`/9700's, `libraryops`/9101's, `adminops`/8411's,
  `employmentops`/7810's, `practiceops`/7110's, `hospitalityops`/
  5510's, `freightops`/4920's, `quarryops`/0810's and `agronomyops`/
  0162's own sequential shape. `high-stakes` =
  `#{:actuation/dispatch-mission :actuation/publish-report}`.

### Ground-truth-recompute reapplication -- self-contained this time

`aid-value-matches-claim?` (deployment's own `:claimed-aid-value` vs.
`aid-quantity x unit-value` recomputed independently) is an HONEST
reapplication of the same discipline every sibling's own cost/total-
matching check establishes. Unlike `domesticops`/9700's own
reapplication (which delegated to a REAL capability library,
`kotoba.labor/wages-for`), this vertical has no bespoke capability
library to delegate to, so the arithmetic is self-contained --
matching the majority of this fleet's actors, not a regression from
9700's own pattern.

### New HARD checks

1. **`dispatch-outside-credential-scope-violations`** (FLAGSHIP, 92nd
   distinct application of the unconditional-evaluation discipline
   overall). Unconditional: every dispatch is checked against its own
   `:within-credential-scope?` ground truth. Grounded in real
   extraterritorial-mission-accreditation law:
   - Japan: 外交関係に関するウィーン条約 (Vienna Convention on Diplomatic
     Relations 1961) implementing legislation, enforced by MOFA.
   - US: Foreign Missions Act, 23 U.S.C. §4301 et seq., enforced by
     the Department of State's Office of Foreign Missions.
   - UK: Diplomatic Privileges Act 1964 (giving effect to the Vienna
     Convention 1961), enforced by the FCDO Protocol Directorate.
   - Germany: Gesetz zu dem Wiener Übereinkommen über diplomatische
     Beziehungen (WÜD-Ausführungsgesetz), enforced by the Auswärtiges
     Amt Protokollreferat.

2. **`cross-border-notification-missing-violations`** (93rd distinct
   application overall, the **sixteenth conditional variant** --
   after socialresearch/7220's, bizassoc/9411's, training/8549's,
   furniture/9524's, specialtyrepair/9529's, leathergoods/9523's,
   ictrepair/9511's, quarryops/0810's, agronomyops/0162's,
   hospitalityops/5510's, practiceops/7110's, employmentops/7810's,
   adminops/8411's, libraryops/9101's and domesticops/9700's own, at
   63rd, 64th, 66th, 67th, 68th, 69th, 71st, 77th, 79th, 81st, 83rd,
   85th, 87th, 89th and 91st). Conditional on the deployment's own
   `:involves-cross-border-movement?` ground truth -- not every
   deployment crosses a border (an in-country observation mission
   does not). Grounded in real cross-border-notification law, all
   four jurisdictions honestly having a real regime:
   - Japan: 出入国管理及び難民認定法 plus bilateral status-of-forces
     arrangements.
   - US: Status of Forces Agreement (SOFA) notification provisions, 8
     U.S.C. §1101 (A-visa border-crossing notice).
   - UK: Immigration Act 1971 diplomatic/mission border-crossing
     notice provisions.
   - Germany: Aufenthaltsgesetz §1 Abs. 2 (diplomatische/dienstliche
     Grenzuebertrittsmeldung).

### Field sync -- no fix needed this time

Like `domesticops`/9700's own build, this repo's `blueprint.edn`
already had the correct `:required-technologies` and
`:optional-technologies [:optimization]` matching the
`kotoba-lang/industry` registry's own entry exactly -- only the
`:maturity` field itself needed adding.

### Fleet-wide `:blueprint` tier reaches zero -- resolution

This promotion was known in advance to exhaust the LAST published-
but-unimplemented blueprint repo anywhere in the registry (verified
via a direct fleet-wide scan, not merely the gftdcojp-origin scope, of
`(kotoba.industry/maturity-summary)` immediately before starting this
build: `{:total 643 :spec 545 :blueprint 1 :implemented 97}`, with the
single `:blueprint` entry confirmed to be `"9900"` and no other).
Three test-suite assumptions in `kotoba-lang/industry`'s own
`test/kotoba/industry_test.clj` depended on a live `:blueprint`-tier
example existing, and a fourth assertion (`(is (pos? (:blueprint
m)))`) asserted the tier count itself is always positive -- both
premises this promotion breaks for the first time in the fleet's
history.

Resolved by refactoring, not by fabricating a fake registry entry or
leaving one real vertical permanently un-implemented as a test
fixture (either of which would violate this project's own
never-fabricate/never-stall discipline):

- Split `kotoba.industry/maturity` into a pure `maturity-of` (industry
  map -> tier, no registry lookup) plus the existing `maturity`
  wrapper (isic -> `maturity-of` via `get-industry`). Split
  `maturity-roadmap` the same way into `maturity-roadmap-of` (industry
  map + resolved tech stack -> roadmap) plus the existing wrapper.
- `maturity-tier`'s "a published blueprint repo is :blueprint" test
  and `maturity-roadmap-reports-next-step`'s "a blueprint entry's next
  step is implemented" test now call `maturity-of`/`maturity-roadmap-
  of` directly against a synthetic fixture map
  (`{:repo "https://example.invalid/still-blueprint-fixture"}`)
  instead of a live registry id -- this unit-tests the actual branch
  logic honestly, without needing (or fabricating) a real ISIC
  business sitting permanently at `:blueprint` tier.
- `maturity-summary-counts-tiers`'s `(is (pos? (:blueprint m)))`
  changed to `(is (zero? (:blueprint m)))`, with a comment explaining
  the backlog reaching zero is a real, desirable fleet milestone, not
  a bug -- and bumped `(is (= 97 (:implemented m)))` to `98`.
- `execution-plan-reports-ui-export-readiness`'s own use of `"9900"`
  needed **no swap at all**: `execution-plan`'s `ui-ready?`/
  `export-ready?`/technology-stack-has-`:ui?` assertions are pure
  functions of `:required-technologies` via `technology-stack`,
  entirely independent of maturity tier -- they still pass unchanged
  after `"9900"` becomes `:implemented`. This test's role was never
  actually to exercise the "still blueprint" premise; only the other
  two locations were.
- There is no still-blueprint test-reference swap this time (unlike
  the two prior swaps this window, 9101->9700 and 9700->9900) because
  there is no remaining `:blueprint`-tier candidate to swap to -- the
  refactor above removes the dependency on one existing at all.

## Consequences

- Fleet maturity: `{:implemented 97 :blueprint 1 :spec 545 :total
  643}` → `{:implemented 98 :blueprint 0 :spec 545 :total 643}`,
  verified against `(kotoba.industry/maturity-summary)` ground truth
  and kept in exact sync in `docs/cloud-itonami.md`.
- 39 tests / 176 assertions in the child repo, lint clean; the demo
  (`clojure -M:dev:run`) confirmed all six distinct HARD-hold rules
  firing correctly via the audit-ledger output.
- `kotoba-lang/industry`'s own full suite re-run with the local-root
  technology override before committing -- 7 tests / 145 assertions
  (one extra assertion from the added ninety-eighth implemented-actor
  sub-test), all green.
- **The entire gftdcojp-origin-registry scope extension is now fully
  exhausted** (this was its final candidate), AND the fleet-wide
  `:blueprint` backlog independently reaches zero at the same moment
  -- both the scope extension and the underlying tier this whole
  build sequence has been draining are simultaneously spent. Per the
  standing authorization's own carve-out for a genuine judgment-call
  juncture (the same kind that prompted the original scope-extension
  question when `cloud-itonami/cloud-itonami-isic-*` was first
  exhausted), this is reported to the user rather than a new scope
  being picked unilaterally.

## Alternatives considered

- **Fabricating a synthetic `:blueprint`-tier registry entry purely to
  keep the three tests passing unchanged**: rejected -- this would
  mean inventing a fake ISIC business or permanently withholding one
  real vertical from implementation, both dishonest relative to this
  project's own registry-integrity discipline.
- **Leaving the three tests broken / deleting them outright**:
  rejected -- the pure-function refactor (`maturity-of`/
  `maturity-roadmap-of`) preserves full coverage of the `:blueprint`
  branch logic without requiring a live fixture, which is strictly
  better than either deleting coverage or asserting a now-false
  premise.
- **An unconditional cross-border-notification check**: rejected --
  an in-country observation mission does not cross any border, so the
  requirement genuinely does not apply.

## References

- `kotoba-lang/industry` registry entry `"9900"`.
- `cloud-itonami/cloud-itonami-isic-9900` repo, `ADR-0001`.
- 外交関係に関するウィーン条約 (Vienna Convention on Diplomatic Relations 1961);
  出入国管理及び難民認定法 (Japan).
- Foreign Missions Act, 23 U.S.C. §4301 et seq.; Status of Forces
  Agreement notification provisions; 8 U.S.C. §1101 (US).
- Diplomatic Privileges Act 1964; Immigration Act 1971 (UK).
- WÜD-Ausführungsgesetz; Aufenthaltsgesetz §1 Abs. 2 (Germany).
