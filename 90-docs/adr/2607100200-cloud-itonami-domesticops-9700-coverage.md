# ADR-2607100200: cloud-itonami-isic-9700 (Community Domestic Employment) deepened to `:implemented`

## Status

Accepted, 2026-07-10.

## Related

- ADR-2607092600 (libraryops/9101)
- ADR-2607092500 (adminops/8411)
- ADR-2607092400 (employmentops/7810)
- ADR-2607092300 (practiceops/7110 -- first governor-name collision)
- ADR-2607092200 (hospitalityops/5510)
- ADR-2607092151 (construction/4211 -- concurrent session)
- ADR-2607091900 (agronomyops/0162)
- ADR-2607091800 (quarryops/0810)
- ADR-2607091700 (freightops/4920)
- ADR-2607091600 (retailops/4711)
- ADR-2607091400 (ictrepair/9511)
- ADR-2607032000 (insurance/real-estate coverage)
- The 96 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-9700` (Community Domestic Employment, ISIC Rev.4
class 9700) was the next `:blueprint`-tier candidate selected under
the standing "pick a new ISIC blueprint vertical" authorization, in
its gftdcojp-origin-registry scope extension.

Unlike the majority of this window's builds, this blueprint's own
README explicitly names a bespoke domain capability library:
`kotoba-lang/labor` (contracts, timesheets, wages, payroll) -- the
THIRD capability-library-wrapping vertical in this fleet, after
`retailops`/4711 (`kotoba-lang/retail`) and `freightops`/4920
(`kotoba-lang/logistics`). `kotoba-lang/robotics` is also named but is
the same generic cross-cutting robotics contract every vertical
already uses. This blueprint's own `:itonami.blueprint/governor`
keyword, `:domestic-employment-governor`, is grep-verified unique
fleet-wide -- clean on the first attempt.

## Decision

Build the full governed-actor architecture for domesticops/9700,
following the same template as all 96 prior actors:

- **Store**: `domesticops.store`, MemStore + DatomicStore, proven
  parity via contract test.
- **Registry**: `domesticops.registry`, pure DRAFT-certificate
  construction via `unsigned-certificate`, jurisdiction-scoped
  sequence numbering (`JPN-DSP-000007`, `JPN-PAY-000007`).
- **Governor**: `:domestic-employment-governor` (grep-verified
  unique fleet-wide).
- **Entity shape**: `assignment`, no `:kind` discriminator. Mission
  dispatch and payroll posting are **sequential** acts on the same
  record (dispatch first, pay later) -- matching `libraryops`/9101's,
  `adminops`/8411's, `employmentops`/7810's, `practiceops`/7110's,
  `hospitalityops`/5510's, `freightops`/4920's, `quarryops`/0810's and
  `agronomyops`/0162's own sequential shape. `high-stakes` =
  `#{:actuation/dispatch-mission :actuation/post-payroll}`.

### Capability-library-reuse check

`payroll-matches-contract?` (assignment's own claimed gross vs. what
`kotoba.labor/wages-for` independently computes from `kotoba.labor/
contract` + `kotoba.labor/timesheet`) is DIFFERENT from every prior
sibling's own cost/total-matching check: instead of reimplementing
flat quantity × unit-rate arithmetic, it delegates DIRECTLY to this
vertical's own real capability library -- more honest than
reinventing wage math a library already provides correctly. This
matches `retailops`/4711's own `ean13-valid?` delegation and
`freightops`/4920's own `tracking-valid?` delegation, both counted as
"capability-library-reuse," not claimed as a genuinely new invention.

### New HARD checks

1. **`household-employment-unregistered-violations`** (FLAGSHIP, 90th
   distinct application of the unconditional-evaluation discipline
   overall). Unconditional: every mission dispatch is checked against
   its own `:household-employment-registered?` ground truth. Grounded
   in real household-employer-registration law:
   - Japan: 労働保険/健康保険/厚生年金保険 家事使用人特例 registration obligations,
     enforced by MHLW/日本年金機構.
   - US: IRS Schedule H household-employment-tax registration, 26
     U.S.C. §3510.
   - UK: HMRC PAYE-for-Employers household-employer registration.
   - Germany: Minijob-Zentrale Haushaltsscheckverfahren, §28a SGB IV.

2. **`vulnerable-person-safeguarding-check-missing-violations`** (91st
   distinct application overall, the **fifteenth conditional
   variant** -- after socialresearch/7220's, bizassoc/9411's,
   training/8549's, furniture/9524's, specialtyrepair/9529's,
   leathergoods/9523's, ictrepair/9511's, quarryops/0810's,
   agronomyops/0162's, hospitalityops/5510's, practiceops/7110's,
   employmentops/7810's, adminops/8411's and libraryops/9101's own, at
   63rd, 64th, 66th, 67th, 68th, 69th, 71st, 77th, 79th, 81st, 83rd,
   85th, 87th and 89th). Conditional on the assignment's own
   `:involves-vulnerable-person?` ground truth -- not every domestic-
   worker assignment cares for a child or elder (a gardener
   assignment does not). Grounded in real vulnerable-person-
   safeguarding law, all four jurisdictions honestly having a real
   regime:
   - Japan: 児童福祉法/日本版DBS制度, enforced by the Children and Families
     Agency.
   - US: state in-home child/elder-care background-check statutes
     (National Background Check Program).
   - UK: Safeguarding Vulnerable Groups Act 2006 (DBS check for
     "regulated activity").
   - Germany: §72a SGB VIII (erweitertes Führungszeugnis), enforced by
     Jugendämter.

### Field sync -- no fix needed this time

Unlike the previous five builds (agronomyops/0162, hospitalityops/
5510, practiceops/7110, employmentops/7810 and adminops/8411, all of
which had a missing `:optional-technologies` key), this repo's
`blueprint.edn` already had the correct `:required-technologies`
(including `:labor`) and `:optional-technologies [:optimization]`
matching the `kotoba-lang/industry` registry's own entry exactly --
only the `:maturity` field itself needed adding.

### Still-blueprint test-reference swap -- the second needed in this build sequence

`kotoba-lang/industry`'s own `test/kotoba/industry_test.clj`
referenced `"9700"` (set during `libraryops`/9101's own promotion) as
its still-blueprint example in three locations. Since `9700` was
itself the vertical being promoted, all three references were swapped
to `"9900"` -- the sole remaining `:blueprint`-tier candidate after
this promotion. Before finalizing the swap, a direct `execution-plan`
call confirmed `"9900"` also satisfies the third test's specific
`ui-ready?`/`export-ready?`/technology-stack-has-`:ui?` assertions.

## Consequences

- Fleet maturity: `{:implemented 96 :blueprint 2 :spec 545 :total
  643}` → `{:implemented 97 :blueprint 1 :spec 545 :total 643}`,
  verified against `(kotoba.industry/maturity-summary)` ground truth
  and kept in exact sync in `docs/cloud-itonami.md`.
- 40 tests / 177 assertions in the child repo (one extra test
  covering the capability-library-wrap's own unknown-wage-type edge
  case), lint clean; the demo (`clojure -M:dev:run`) confirmed all six
  distinct HARD-hold rules firing correctly via the audit-ledger
  output.
- `kotoba-lang/industry`'s own full suite re-run with the local-root
  technology override before committing -- 7 tests / 144 assertions,
  all green.
- Only **one** genuine `:blueprint`-tier candidate remains in the
  gftdcojp-origin-registry scope: `"9900"` (Community Mission
  Operations Support).

## Alternatives considered

- **An unconditional vulnerable-person-safeguarding check**: rejected
  -- gardener/general-housekeeping assignments do not carry the same
  safeguarding requirement.
- **Reimplementing wage arithmetic instead of delegating to
  `kotoba.labor`**: rejected -- the library already provides correct,
  tested wage-computation logic; reimplementing it would risk silent
  drift between the actor's own math and the library's own math.

## References

- `kotoba-lang/industry` registry entry `"9700"`.
- `cloud-itonami/cloud-itonami-isic-9700` repo, `ADR-0001`.
- `cloud-itonami-isic-4711`'s and `cloud-itonami-isic-4920`'s own
  ADR-0001s (the two prior capability-library-wrapping verticals).
- 労働保険の保険料の徴収等に関する法律 (家事使用人特例); 健康保険法/厚生年金保険法; 児童福祉法
  (Child Welfare Act) (Japan).
- IRS Schedule H, 26 U.S.C. §3510; National Background Check Program
  (US).
- Income Tax (Earnings and Pensions) Act 2003; Safeguarding
  Vulnerable Groups Act 2006 (UK).
- §28a SGB IV (Haushaltsscheckverfahren); §72a SGB VIII (erweitertes
  Führungszeugnis) (Germany).
