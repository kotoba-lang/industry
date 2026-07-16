# ADR-2607092600: cloud-itonami-isic-9101 (Community Library and Archive) deepened to `:implemented`

## Status

Accepted, 2026-07-09.

## Related

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
- The 95 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-9101` (Community Library and Archive, ISIC Rev.4
class 9101) was the next `:blueprint`-tier candidate selected under
the standing "pick a new ISIC blueprint vertical" authorization, in
its gftdcojp-origin-registry scope extension.

A `kotoba-lang` org search for library/archive/ils/catalog/marc/
preservation-named repos returned zero hits. This build returns to
self-contained domain logic. This blueprint's own
`:itonami.blueprint/governor` keyword, `:library-governor`, is
grep-verified unique fleet-wide -- clean on the first attempt.

## Decision

Build the full governed-actor architecture for libraryops/9101,
following the same template as all 95 prior actors:

- **Store**: `libraryops.store`, MemStore + DatomicStore, proven
  parity via contract test.
- **Registry**: `libraryops.registry`, pure DRAFT-certificate
  construction via `unsigned-certificate`, jurisdiction-scoped
  sequence numbering (`JPN-LND-000007`, `JPN-PRV-000007`).
- **Governor**: `:library-governor` (grep-verified unique
  fleet-wide).
- **Entity shape**: `item`, no `:kind` discriminator. Item lending
  and item preservation are **sequential** acts on the same record
  (lend first, preserve later) -- matching `adminops`/8411's,
  `employmentops`/7810's, `practiceops`/7110's, `hospitalityops`/
  5510's, `freightops`/4920's, `quarryops`/0810's and `agronomyops`/
  0162's own sequential shape. `high-stakes` = `#{:actuation/
  lend-item :actuation/preserve-item}`.

### New HARD checks

1. **`lending-restricted-item-violations`** (FLAGSHIP, 88th distinct
   application of the unconditional-evaluation discipline overall).
   Unconditional: every lending is checked against its own
   `:lending-restricted?` ground truth. Grounded in real legal-
   deposit/non-circulating-collection law:
   - Japan: 国立国会図書館法 (National Diet Library Act) Article 24 (納本
     制度), enforced by the NDL.
   - US: Copyright Act 17 U.S.C. §407 (mandatory deposit), enforced
     by the Copyright Office.
   - UK: Legal Deposit Libraries Act 2003, enforced by the British
     Library and legal-deposit libraries.
   - Germany: Gesetz über die Deutsche Nationalbibliothek (DNBG) §16
     (Pflichtablieferung), enforced by the DNB.

2. **`conservator-sign-off-missing-violations`** (89th distinct
   application overall, the **fourteenth conditional variant** --
   after socialresearch/7220's, bizassoc/9411's, training/8549's,
   furniture/9524's, specialtyrepair/9529's, leathergoods/9523's,
   ictrepair/9511's, quarryops/0810's, agronomyops/0162's,
   hospitalityops/5510's, practiceops/7110's, employmentops/7810's
   and adminops/8411's own, at 63rd, 64th, 66th, 67th, 68th, 69th,
   71st, 77th, 79th, 81st, 83rd, 85th and 87th). Conditional on the
   item's own `:requires-conservator-sign-off?` ground truth -- not
   every item professionally requires a qualified conservator's
   sign-off. Grounded in real professional-conservation-standards
   law, all four jurisdictions honestly having a real regime:
   - Japan: 文化財保護法 (Act on Protection of Cultural Properties),
     enforced by the Agency for Cultural Affairs.
   - US: AIC Code of Ethics and Guidelines for Practice.
   - UK: Icon Professional Standards and Code of Conduct.
   - Germany: VDR Berufsethische Richtlinien.

### Reapplied discipline (not claimed as new)

- **`late-fee-matches-claim?`**: re-derives the claimed late fee
  (`days-overdue × daily-rate`) independently and flags any mismatch
  -- the same ground-truth-recompute discipline `adminops.registry`'s
  own `assessed-fee-matches-claim?`, `employmentops.registry`'s own
  `placement-fee-matches-claim?` and `practiceops.registry`'s own
  `fee-total-matches-claim?` establish.

### Field sync -- the same gap pattern as five prior builds

This repo's `blueprint.edn` had the correct `:required-technologies`
matching the `kotoba-lang/industry` registry's own entry for `"9101"`
exactly, but was **missing `:optional-technologies [:optimization]`
entirely**. Fixed cleanly in the same commit as the `:maturity` flip.

### Still-blueprint test-reference swap -- the first needed in this build sequence

`kotoba-lang/industry`'s own `test/kotoba/industry_test.clj`
referenced `"9101"` (set during `ictrepair`/9511's own promotion) as
its still-blueprint example in three locations
(`maturity-tier`, `maturity-roadmap-reports-next-step`,
`execution-plan-reports-ui-export-readiness`). Since `9101` was
itself the vertical being promoted, all three references were swapped
to `"9700"` -- one of the two genuinely remaining `:blueprint`-tier
candidates after this promotion. Before finalizing the swap, a direct
`execution-plan` call confirmed `"9700"` also satisfies the third
test's specific `ui-ready?`/`export-ready?`/technology-stack-has-
`:ui?` assertions (not guaranteed for every `:blueprint`-tier entry).

## Consequences

- Fleet maturity: `{:implemented 95 :blueprint 3 :spec 545 :total
  643}` → `{:implemented 96 :blueprint 2 :spec 545 :total 643}`,
  verified against `(kotoba.industry/maturity-summary)` ground truth
  and kept in exact sync in `docs/cloud-itonami.md`.
- 39 tests / 176 assertions in the child repo, lint clean; the demo
  (`clojure -M:dev:run`) confirmed all six distinct HARD-hold rules
  firing correctly via the audit-ledger output.
- `kotoba-lang/industry`'s own full suite re-run with the local-root
  technology override before committing -- 7 tests / 143 assertions,
  all green.

## Alternatives considered

- **An unconditional conservator-sign-off check**: rejected -- routine
  repairs of ordinary circulating items do not require a
  conservator's sign-off.
- **Fabricating a jurisdiction gap** to match `hospitalityops`/5510's
  own single-jurisdiction honesty gap: rejected -- all four seeded
  jurisdictions genuinely have a real professional-conservation-
  standards regime here.

## References

- `kotoba-lang/industry` registry entry `"9101"`.
- `cloud-itonami/cloud-itonami-isic-9101` repo, `ADR-0001`.
- 国立国会図書館法 (National Diet Library Act) Article 24; 文化財保護法 (Act
  on Protection of Cultural Properties) (Japan).
- Copyright Act, 17 U.S.C. §407; AIC Code of Ethics and Guidelines
  for Practice (US).
- Legal Deposit Libraries Act 2003; Icon Professional Standards and
  Code of Conduct (UK).
- Gesetz über die Deutsche Nationalbibliothek (DNBG) §16; VDR
  Berufsethische Richtlinien (Germany).
