# ADR-2607042800: cloud-itonami-iso3166 — country maturity promotion, batch 23 (Slovakia / Bolivia / Cambodia)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607042700 batch 22: HUN/HRV/NAM). This is the twenty-third
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `SVK` | Slovakia | Central Europe, EU member | Independent Public-Sector Market-Entry & Procurement Compliance Service — Slovakia |
| `BOL` | Bolivia | eighth South America jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Bolivia |
| `KHM` | Cambodia | second Southeast Asia jurisdiction added this phase | Independent Public-Sector Market-Entry & Procurement Compliance Service — Cambodia |

- **Slovakia**: the Slovak Office for Public Procurement's List of
  Economic Operators (Zoznam hospodárskych subjektov), a prerequisite
  registration that relieves bidders from repeated document submission;
  Obchodný register (Business Register, orsr.sk) administered by the
  Ministry of Justice. EU member state — no national-content quota,
  matching the treatment already applied to
  ESP/NLD/FRA/IRL/POL/ITA/SWE/CZE/EST/FIN/DNK/LVA/LTU/HUN/HRV.
- **Bolivia**: SICOES (Sistema de Contrataciones Estatales,
  sicoes.gob.bo) managed by the Ministry of Economy and Public Finance;
  NIT (Número de Identificación Tributaria) via SIN, an eliminatory
  requirement for participation; commercial registration via SEPREC
  (established 2021, absorbing FUNDEMPRESA's functions). Eighth South
  America jurisdiction alongside Brazil, Argentina, Chile, Colombia,
  Peru, Uruguay and Ecuador.
- **Cambodia**: public-procurement bid publication managed by the
  Ministry of Economy and Finance (MEF, since February 2011); Ministry
  of Commerce (MoC) online business-registration portal, integrated via
  CamDX (Cambodia Data Exchange, launched 2020) with the tax and labor
  authorities. Second Southeast Asia jurisdiction added this phase,
  deepening regional coverage alongside Vietnam, Indonesia, Thailand,
  Philippines and Singapore.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 90/212 total blueprints (71 country + 19 Japan
agency); 13 tests / 885 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 68/193 to 71/193.
- (+) South America deepens to 8 jurisdictions; Southeast Asia deepens
  to 6 jurisdictions; Slovakia extends the Central European EU cluster
  alongside Poland, Czech Republic and Hungary.
- (+) All 3 use real, named, web-verified systems (List of Economic
  Operators/Obchodný register, SICOES/SEPREC, MEF/MoC-CamDX) matching
  the honesty discipline of prior batches.
- (−) 122/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{svk,bol,khm}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607042700 (country maturity promotion batch 22: HUN/HRV/NAM)
