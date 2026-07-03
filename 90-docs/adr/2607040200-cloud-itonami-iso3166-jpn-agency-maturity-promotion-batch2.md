# ADR-2607040200: cloud-itonami-iso3166-jpn — agency maturity promotion, batch 2 (MOJ / MHLW / FSA)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

ADR-2607040100 established the Japan agency-level extension to
`kotoba-lang/iso3166` (19 `gov.jpn.*` bodies, 5 curated as `:blueprint`:
METI, MOF, Digital Agency, JFTC, PPC) and the coordinator+leaf split
(`cloud-itonami-iso3166-jpn` country coordinator + per-agency leaves). This
ADR is the first scheduled maturity/coverage promotion under that
extension, requested via a self-pacing `/loop` for continued incremental
growth of the family.

## Decision

Promote 3 more Japan agency entries from `:spec` to `:blueprint`, chosen
for regulatory diversity not covered by the first batch:

| Code | Body | Ooyake ID | Blueprint |
|---|---|---|---|
| `JPN-MOJ` | 法務省 (ministry) | `gov.jpn.moj` | Independent MOJ-Regulated Corporate Registry & Status-of-Residence Compliance Service |
| `JPN-MHLW` | 厚生労働省 (ministry) | `gov.jpn.mhlw` | Independent MHLW-Regulated Labor-Standards Compliance Service |
| `JPN-FSA` | 金融庁 (independent commission) | `gov.jpn.finreg` | Independent FSA Payment-Services & Financial-Regulatory Compliance Service |

- **MOJ**: corporate/commercial registry (商業登記) filings at the Legal
  Affairs Bureau (法務局), and status-of-residence (在留資格) for an
  operator's foreign staff, administered through the Immigration Services
  Agency (出入国在留管理庁) — both under MOJ.
- **MHLW**: Labor Standards Act (労働基準法) compliance for an operator
  hiring local staff, including 36-agreement (36協定) overtime filings.
- **FSA**: Payment Services Act (資金決済法) registration-tier
  classification for a government contract involving payments/funds
  transfer, and general financial-regulatory compliance.

Same structure, robotics exemption, and actuation gate as batch 1
(ADR-2607040100): README + blueprint.edn + docs/business-model.md +
docs/operator-guide.md + governance docs + AGPL-3.0-or-later,
`:itonami.blueprint/robotics false`, `:required-technologies [:identity
:forms :dmn :bpmn :audit-ledger]`, `:filing/submit` never automated.

Registry now stands at 13/212 total blueprints (5 country + 8 Japan
agency); 12 tests / 547 assertions, all green.

## Consequences

- (+) Japan agency coverage grows from 5/19 to 8/19 without touching the
  country level or other countries — an isolated, low-risk increment.
- (+) The 3 new agencies add corporate-registry/immigration, labor, and
  financial-regulatory domains, meaningfully broadening what an operator
  can compose alongside the country-level coordinator.
- (−) 11 Japan agencies remain `:spec`; no other country has any
  agency-level breakdown yet — both remain explicit future work.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-jpn-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-jpn-{moj,mhlw,fsa}` (3 repos)

## References

- ADR-2607040100 (cloud-itonami-iso3166-jpn agency-level extension) — the
  extension this ADR promotes further entries within.
- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints) — the country-level family and coordinator.
