# ADR-2607041900: cloud-itonami-iso3166 — country maturity promotion, batch 14 (China / Costa Rica / Czech Republic)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041800 batch 13: SWE/KAZ/QAT). This is the fourteenth
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `CHN` | People's Republic of China | major East Asian economy | Independent Public-Sector Market-Entry & Procurement Compliance Service — China |
| `CRI` | Costa Rica | first Central America jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Costa Rica |
| `CZE` | Czech Republic | Central Europe, EU member | Independent Public-Sector Market-Entry & Procurement Compliance Service — Czech Republic |

- **China**: China Government Procurement Network (中国政府采购网,
  ccgp.gov.cn) under the Ministry of Finance; Unified Social Credit Code
  (USCC) issued by the State Administration for Market Regulation
  (SAMR), verifiable through the National Enterprise Credit Information
  Publicity System (GSXT).
- **Costa Rica**: SICOP (Sistema Integrado de Compras Públicas),
  covering central government, autonomous institutions and
  municipalities; Registro Nacional de la Propiedad (National Registry,
  Ministry of Justice and Peace) business registration. First Central
  America jurisdiction, distinct from the South America (BRA, ARG, CHL,
  COL, PER) and Mexico coverage already in the registry.
- **Czech Republic**: NEN (Národní elektronický nástroj / National
  Electronic Tool, nen.nipez.cz) under the NIPEZ Information System on
  Public Contracts; Obchodní rejstřík (Commercial Register) via
  or.justice.cz, Ministry of Justice. EU member state — no
  national-content quota, matching the treatment already applied to
  ESP/NLD/FRA/IRL/POL/ITA/SWE.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 63/212 total blueprints (44 country + 19 Japan
agency); 13 tests / 777 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 41/193 to 44/193.
- (+) First Central America jurisdiction (Costa Rica) added; China adds
  a major East Asian economy distinct from the existing Japan/Korea/
  Vietnam/Indonesia/Thailand/Philippines coverage; Czech Republic
  deepens Central European EU coverage.
- (+) All 3 use real, named, web-verified systems (ccgp.gov.cn/USCC/
  SAMR/GSXT, SICOP/Registro Nacional, NEN/NIPEZ/Obchodní rejstřík)
  matching the honesty discipline of prior batches.
- (−) 149/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{chn,cri,cze}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041800 (country maturity promotion batch 13: SWE/KAZ/QAT)
