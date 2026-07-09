# ADR-2607092400: cloud-itonami-isic-7810 (Community Employment Agency) deepened to `:implemented`

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607092300 (practiceops/7110 -- first governor-name collision)
- ADR-2607092200 (hospitalityops/5510)
- ADR-2607092151 (construction/4211 -- concurrent session)
- ADR-2607091900 (agronomyops/0162)
- ADR-2607091800 (quarryops/0810)
- ADR-2607091700 (freightops/4920)
- ADR-2607091600 (retailops/4711)
- ADR-2607091400 (ictrepair/9511)
- ADR-2607032000 (insurance/real-estate coverage)
- The 93 prior actors' own ADR-0001s
- langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-7810` (Community Employment Agency, ISIC Rev.4
class 7810) was the next `:blueprint`-tier candidate selected under
the standing "pick a new ISIC blueprint vertical" authorization, in
its gftdcojp-origin-registry scope extension.

A `kotoba-lang` org search for employment/staffing/recruiting/ats/hr/
talent/candidate/matching-named repos returned zero hits.
`kotoba-lang/occupation` was investigated: it is the ISCO-08
sole-proprietor occupation registry, the generic occupation-
classification counterpart to `kotoba.industry`, not a bespoke domain
capability library. This build returns to self-contained domain
logic. This blueprint's own `:itonami.blueprint/governor` keyword,
`:employment-agency-governor`, is grep-verified unique fleet-wide --
clean on the first attempt, unlike `practiceops`/7110's own
collision.

## Decision

Build the full governed-actor architecture for employmentops/7810,
following the same template as all 93 prior actors:

- **Store**: `employmentops.store`, MemStore + DatomicStore, proven
  parity via contract test.
- **Registry**: `employmentops.registry`, pure DRAFT-certificate
  construction via `unsigned-certificate`, jurisdiction-scoped
  sequence numbering (`JPN-MTC-000007`, `JPN-PLC-000007`).
- **Governor**: `:employment-agency-governor` (grep-verified unique
  fleet-wide).
- **Entity shape**: `candidacy`, no `:kind` discriminator. Candidate
  matching and candidate placement are **sequential** acts on the
  same record (match first, place later) -- matching `practiceops`/
  7110's, `hospitalityops`/5510's, `freightops`/4920's, `quarryops`/
  0810's and `agronomyops`/0162's own sequential shape. `high-stakes`
  = `#{:actuation/match-candidate :actuation/place-candidate}`.

### New HARD checks

1. **`matching-basis-discriminatory-violations`** (FLAGSHIP, 84th
   distinct application of the unconditional-evaluation discipline
   overall). Unconditional: every match is checked against its own
   `:matching-criteria-discriminatory?` ground truth. Grounded in real
   employment-anti-discrimination law:
   - Japan: 職業安定法/男女雇用機会均等法 (Employment Security Act / Equal
     Employment Opportunity Act), enforced by MHLW.
   - US: Title VII of the Civil Rights Act of 1964, enforced by the
     EEOC.
   - UK: Equality Act 2010, enforced by the EHRC.
   - Germany: Allgemeines Gleichbehandlungsgesetz (AGG), enforced by
     the Antidiskriminierungsstelle.

2. **`work-authorization-unverified-violations`** (85th distinct
   application overall, the **twelfth conditional variant** -- after
   socialresearch/7220's, bizassoc/9411's, training/8549's,
   furniture/9524's, specialtyrepair/9529's, leathergoods/9523's,
   ictrepair/9511's, quarryops/0810's, agronomyops/0162's,
   hospitalityops/5510's and practiceops/7110's own, at 63rd, 64th,
   66th, 67th, 68th, 69th, 71st, 77th, 79th, 81st and 83rd).
   Conditional on the candidacy's own `:requires-work-authorization?`
   ground truth -- not every candidate is a foreign national requiring
   work-authorization verification. Grounded in real work-
   authorization law, all four jurisdictions honestly having a real
   regime:
   - Japan: 出入国管理及び難民認定法, enforced by the Immigration Services
     Agency.
   - US: INA §274A Form I-9, enforced by USCIS.
   - UK: Immigration, Asylum and Nationality Act 2006 right-to-work
     checks, enforced by the Home Office.
   - Germany: Aufenthaltsgesetz §4a, enforced by Ausländerbehörden.

### Reapplied discipline (not claimed as new)

- **`placement-fee-matches-claim?`**: re-derives the claimed placement
  fee (`annual-salary × fee-rate`) independently and flags any
  mismatch -- the same ground-truth-recompute discipline
  `practiceops.registry`'s own `fee-total-matches-claim?`,
  `hospitalityops.registry`'s own `folio-total-matches-claim?`,
  `agronomyops.registry`'s own `dose-matches-claim?` and
  `quarryops.registry`'s own `royalty-matches-claim?` establish.

### Field sync -- the same gap pattern as three prior builds

This repo's `blueprint.edn` had the correct `:required-technologies`
matching the `kotoba-lang/industry` registry's own entry for `"7810"`
exactly, but was **missing `:optional-technologies [:optimization]`
entirely**. Fixed cleanly in the same commit as the `:maturity` flip.

## Consequences

- Fleet maturity: `{:implemented 93 :blueprint 5 :spec 545 :total
  643}` → `{:implemented 94 :blueprint 4 :spec 545 :total 643}`,
  verified against `(kotoba.industry/maturity-summary)` ground truth
  and kept in exact sync in `docs/cloud-itonami.md`.
- 39 tests / 176 assertions in the child repo, lint clean; the demo
  (`clojure -M:dev:run`) confirmed all six distinct HARD-hold rules
  firing correctly via the audit-ledger output.
- `kotoba-lang/industry`'s own full suite re-run with the local-root
  technology override before committing -- 7 tests / 141 assertions,
  all green.
- `test/kotoba/industry_test.clj`'s still-blueprint example reference
  (`"9101"`) did not need to change.

## Shared-checkout note

During this build's superproject sync steps, uncommitted local
modifications were found in `manifest/repos.edn`/`west.yml` (a
concurrent session's in-progress `kotobase-messenger` registration,
ADR-2607092345) plus two untracked ADR files. The untracked ADR files
were verified byte-identical to already-landed `origin/main` content
(safe), but `west.yml` also carried a genuinely-newer-than-origin
`net-kotobase-ipfs` pin not yet reflected anywhere upstream. Per
standing discipline, this was stashed (not dropped) via `git stash
push -u` rather than discarded. A subsequent fetch/fast-forward cycle
advanced the local checkout past the point where that stash would
have applied -- the concurrent session's own work had landed upstream
in the interim -- leaving the working tree clean with no data loss.
The stash entry remains in the stash list for that session's own
reconciliation.

## Alternatives considered

- **An unconditional work-authorization check**: rejected -- not
  every candidate requires work-authorization verification.
- **Fabricating a jurisdiction gap** to match `hospitalityops`/5510's
  own single-jurisdiction honesty gap: rejected -- all four seeded
  jurisdictions genuinely have a real work-authorization regime here.
- **Treating `kotoba-lang/occupation` as this vertical's capability
  library**: rejected -- generic ISCO-08 occupation-classification
  infrastructure, not domain-specific business logic.

## References

- `kotoba-lang/industry` registry entry `"7810"`.
- `cloud-itonami/cloud-itonami-isic-7810` repo, `ADR-0001`.
- 職業安定法 (Employment Security Act); 男女雇用機会均等法 (Equal Employment
  Opportunity Act); 出入国管理及び難民認定法 (Immigration Control and Refugee
  Recognition Act) (Japan).
- Title VII of the Civil Rights Act of 1964, 42 U.S.C. §2000e; INA
  §274A (US).
- Equality Act 2010; Immigration, Asylum and Nationality Act 2006
  (UK).
- Allgemeines Gleichbehandlungsgesetz (AGG); Aufenthaltsgesetz
  (AufenthG) §4a (Germany).
