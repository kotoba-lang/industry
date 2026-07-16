# ADR-2607040500: cloud-itonami-iso3166-jpn — Japan agency coverage complete (19/19)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Final batch of the self-paced `/loop` maturity/coverage sweep started by
ADR-2607040100 (Japan agency-level extension) and continued through
ADR-2607040200 / -300 / -400 (batches 2-4). 5 Japan agencies remained
`:spec`.

## Decision

Promote the final 5 Japan agency entries from `:spec` to `:blueprint`:

| Code | Body | Ooyake ID | Blueprint |
|---|---|---|---|
| `JPN-CAO` | 内閣府 (ministry) | `gov.jpn.cao` | Independent Cabinet-Office Cross-Ministerial Program Compliance Service |
| `JPN-MIC` | 総務省 (ministry) | `gov.jpn.mic` | Independent MIC Telecom & Broadcasting Licensing Compliance Service |
| `JPN-MEXT` | 文部科学省 (ministry) | `gov.jpn.mext` | Independent MEXT Research-Grant & School-Accreditation Compliance Service |
| `JPN-RECONSTRUCTION` | 復興庁 (agency) | `gov.jpn.reconstruction` | Independent Reconstruction-Agency Special-Zone Procurement Compliance Service |
| `JPN-STATISTICS` | 総務省統計局 (independent commission) | `gov.jpn.statistics` | Independent Statistics-Japan Reporting-Obligation Compliance Service |

- **CAO**: eligibility for Cabinet-Office-coordinated cross-ministerial
  programs (regional revitalization, regulatory-sandbox schemes).
- **MIC**: Telecommunications Business Act (電気通信事業法) carrier
  registration and broadcasting-license rules.
- **MEXT**: research-grant (科研費-adjacent) eligibility/reporting and
  school/institutional accreditation for education-sector contracts.
- **Reconstruction Agency**: special reconstruction-zone (復興特区)
  eligibility and disaster-recovery procurement rules.
- **Statistics Japan**: Statistics Act (統計法) reporting obligations for
  designated statistical surveys.

Same structure, robotics exemption, and actuation gate as batches 1-4.

**This completes the Japan agency-level extension: all 19/19 `gov.jpn.*`
central-government bodies (12 ministries, 2 agencies, 5 independent
commissions) now have a published `cloud-itonami-iso3166-jpn-{code}`
blueprint repo.** Registry stands at 24/212 total blueprints (5 country +
19 Japan agency); 13 tests / 621 assertions, all green.

## Consequences

- (+) Full Japan agency coverage achieved across all three body types
  (ministry / agency / independent commission), giving an operator a
  complete menu of agency-specific compliance blueprints to compose
  alongside `cloud-itonami-iso3166-jpn`'s country-level coordinator.
- (+) The sweep validated the coordinator+leaf pattern end-to-end (5
  incremental ADRs, 19 blueprint repos, zero registry-schema changes
  needed after the initial ADR-2607040100 extension).
- (−) The remaining 188/193 countries still have no agency-level
  breakdown; whether/how to extend this pattern to another country (USA,
  DEU, KEN, IND, or others) is explicit future work, not decided here.
- (−) 188/193 countries remain `:spec` at the country level too (only 5
  curated blueprints: JPN/USA/DEU/KEN/IND) — country-level maturity
  promotion is a separate, much larger future increment.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 5 new
  `cloud-itonami-iso3166-jpn-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-jpn-{cao,mic,mext,reconstruction,statistics}` (5 repos)

## References

- ADR-2607040100 (cloud-itonami-iso3166-jpn agency-level extension) — the
  original decision this ADR completes.
- ADR-2607040200 / ADR-2607040300 / ADR-2607040400 (batches 2-4)
- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints) — the country-level family and coordinator.
