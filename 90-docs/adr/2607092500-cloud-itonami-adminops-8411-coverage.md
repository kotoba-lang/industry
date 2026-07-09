# ADR-2607092500: cloud-itonami-isic-8411 (Community Public Administration Service) deepened to `:implemented`

## Status

Accepted, 2026-07-09.

## Related

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
- The 94 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-8411` (Community Public Administration Service,
ISIC Rev.4 class 8411) was the next `:blueprint`-tier candidate
selected under the standing "pick a new ISIC blueprint vertical"
authorization, in its gftdcojp-origin-registry scope extension.

A `kotoba-lang` org search for public-administration/government/
civic/municipal-named repos surfaced `cofog`. It was investigated: it
is the COFOG (UN Classification of the Functions of Government)
registry, the third generic classification registry in this fleet
alongside `kotoba.industry` and `kotoba.occupation` -- not a bespoke
domain capability library. This build returns to self-contained
domain logic. This blueprint's own `:itonami.blueprint/governor`
keyword, `:public-administration-governor`, is grep-verified unique
fleet-wide -- clean on the first attempt.

## Decision

Build the full governed-actor architecture for adminops/8411,
following the same template as all 94 prior actors:

- **Store**: `adminops.store`, MemStore + DatomicStore, proven parity
  via contract test.
- **Registry**: `adminops.registry`, pure DRAFT-certificate
  construction via `unsigned-certificate`, jurisdiction-scoped
  sequence numbering (`JPN-DEC-000007`, `JPN-NTF-000007`).
- **Governor**: `:public-administration-governor` (grep-verified
  unique fleet-wide).
- **Entity shape**: `case`, no `:kind` discriminator. Case decision
  and citizen notification are **sequential** acts on the same record
  (decide first, notify later) -- matching `employmentops`/7810's,
  `practiceops`/7110's, `hospitalityops`/5510's, `freightops`/4920's,
  `quarryops`/0810's and `agronomyops`/0162's own sequential shape.
  `high-stakes` = `#{:actuation/decide-case :actuation/
  notify-citizen}`.

### New HARD checks

1. **`decision-outside-authority-violations`** (FLAGSHIP, 86th
   distinct application of the unconditional-evaluation discipline
   overall). Unconditional: every decision is checked against its own
   `:within-delegated-authority?` ground truth. Grounded in real
   administrative-procedure/ultra-vires law:
   - Japan: 行政手続法/地方自治法 (Administrative Procedure Act / Local
     Autonomy Act), enforced by MIC/local governments.
   - US: APA §706 (agency action "in excess of statutory
     jurisdiction, authority, or limitations").
   - UK: ultra vires doctrine (Anisminic v Foreign Compensation
     Commission).
   - Germany: VwVfG §44 (Nichtigkeit eines Verwaltungsaktes).

2. **`appeal-rights-notice-missing-violations`** (87th distinct
   application overall, the **thirteenth conditional variant** --
   after socialresearch/7220's, bizassoc/9411's, training/8549's,
   furniture/9524's, specialtyrepair/9529's, leathergoods/9523's,
   ictrepair/9511's, quarryops/0810's, agronomyops/0162's,
   hospitalityops/5510's, practiceops/7110's and employmentops/7810's
   own, at 63rd, 64th, 66th, 67th, 68th, 69th, 71st, 77th, 79th, 81st,
   83rd and 85th). Conditional on the case's own `:decision-adverse?`
   ground truth -- not every decision is adverse. Grounded in real
   appeal-rights/notice-of-remedies law, all four jurisdictions
   honestly having a real regime:
   - Japan: 行政不服審査法第82条 (教示).
   - US: APA §555(e).
   - UK: Tribunals, Courts and Enforcement Act 2007.
   - Germany: VwVfG §37 Abs. 6 (Rechtsbehelfsbelehrung).

### Reapplied discipline (not claimed as new)

- **`assessed-fee-matches-claim?`**: re-derives the claimed assessed
  fee (`base-amount × fee-rate`) independently and flags any
  mismatch -- the same ground-truth-recompute discipline
  `employmentops.registry`'s own `placement-fee-matches-claim?`,
  `practiceops.registry`'s own `fee-total-matches-claim?` and
  `hospitalityops.registry`'s own `folio-total-matches-claim?`
  establish.

### Field sync -- the same gap pattern as four prior builds

This repo's `blueprint.edn` had the correct `:required-technologies`
matching the `kotoba-lang/industry` registry's own entry for `"8411"`
exactly, but was **missing `:optional-technologies [:optimization]`
entirely**. Fixed cleanly in the same commit as the `:maturity` flip.

## Consequences

- Fleet maturity: `{:implemented 94 :blueprint 4 :spec 545 :total
  643}` → `{:implemented 95 :blueprint 3 :spec 545 :total 643}`,
  verified against `(kotoba.industry/maturity-summary)` ground truth
  and kept in exact sync in `docs/cloud-itonami.md`.
- 39 tests / 176 assertions in the child repo, lint clean; the demo
  (`clojure -M:dev:run`) confirmed all six distinct HARD-hold rules
  firing correctly via the audit-ledger output.
- `kotoba-lang/industry`'s own full suite re-run with the local-root
  technology override before committing -- 7 tests / 142 assertions,
  all green.
- `test/kotoba/industry_test.clj`'s still-blueprint example reference
  (`"9101"`) did not need to change.

## Alternatives considered

- **An unconditional appeal-rights-notice check**: rejected --
  favorable decisions do not carry the same appeal-rights-notice
  requirement.
- **Fabricating a jurisdiction gap** to match `hospitalityops`/5510's
  own single-jurisdiction honesty gap: rejected -- all four seeded
  jurisdictions genuinely have a real appeal-rights regime here.
- **Treating `kotoba-lang/cofog` as this vertical's capability
  library**: rejected -- generic COFOG government-function-
  classification infrastructure, not domain-specific business logic.

## References

- `kotoba-lang/industry` registry entry `"8411"`.
- `cloud-itonami/cloud-itonami-isic-8411` repo, `ADR-0001`.
- 行政手続法 (Administrative Procedure Act); 地方自治法 (Local Autonomy
  Act); 行政不服審査法 (Administrative Appeal Act) Article 82 (Japan).
- Administrative Procedure Act (APA), 5 U.S.C. §706, §555(e) (US).
- Ultra vires doctrine (Anisminic v Foreign Compensation Commission);
  Tribunals, Courts and Enforcement Act 2007 (UK).
- Verwaltungsverfahrensgesetz (VwVfG) §37, §44 (Germany).
