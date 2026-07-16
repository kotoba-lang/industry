# ADR-2607040400: cloud-itonami-iso3166-jpn — agency maturity promotion, batch 4 (MOFA / MOD / Board of Audit)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage promotion (ADR-2607040100
extension, ADR-2607040200 batch 2, ADR-2607040300 batch 3). This is batch 4.

## Decision

Promote 3 more Japan agency entries from `:spec` to `:blueprint`:

| Code | Body | Ooyake ID | Blueprint |
|---|---|---|---|
| `JPN-MOFA` | 外務省 (ministry) | `gov.jpn.mofa` | Independent MOFA ODA/JICA Tender-Eligibility Compliance Service |
| `JPN-MOD` | 防衛省 (ministry) | `gov.jpn.mod` | Independent MOD Defense-Equipment Transfer & Security-Clearance Compliance Service |
| `JPN-AUDIT` | 会計検査院 (independent commission) | `gov.jpn.audit` | Independent Board-of-Audit Readiness Compliance Service |

- **MOFA**: eligibility screening for Japan's Official Development
  Assistance (政府開発援助/ODA) tenders and JICA-funded
  international-development contract rules.
- **MOD**: classification under the Three Principles on Transfer of
  Defense Equipment and Technology (防衛装備移転三原則), and
  security-clearance prerequisites for staff handling specially designated
  secrets (特定秘密保護法).
- **Board of Audit**: financial record-keeping and documentation standards
  a public-sector contractor should maintain in anticipation of a Board of
  Audit (会計検査院) inspection under the Board of Audit Act (会計検査院法)
  — directly reinforcing the `:public-spend-transparency` theme already
  present across this family.

Same structure, robotics exemption, and actuation gate as batches 1-3.

Registry now stands at 19/212 total blueprints (5 country + 14 Japan
agency); 12 tests / 577 assertions, all green.

## Consequences

- (+) Japan agency coverage grows from 11/19 to 14/19 (>70% of the family).
- (+) Adds international-development, defense, and audit domains not
  previously represented.
- (−) 5 Japan agencies remain `:spec` (CAO, MIC, MEXT, Reconstruction
  Agency, Statistics Japan); no other country has any agency-level
  breakdown yet.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-jpn-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-jpn-{mofa,mod,audit}` (3 repos)

## References

- ADR-2607040100 (cloud-itonami-iso3166-jpn agency-level extension)
- ADR-2607040200 (batch 2: MOJ/MHLW/FSA)
- ADR-2607040300 (batch 3: MAFF/MLIT/MOE)
