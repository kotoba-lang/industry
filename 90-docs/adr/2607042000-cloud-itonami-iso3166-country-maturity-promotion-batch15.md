# ADR-2607042000: cloud-itonami-iso3166 — country maturity promotion, batch 15 (Ukraine / Israel / Uruguay)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041900 batch 14: CHN/CRI/CZE). This is the fifteenth
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `UKR` | Ukraine | Eastern Europe, globally recognized open-data e-procurement | Independent Public-Sector Market-Entry & Procurement Compliance Service — Ukraine |
| `ISR` | Israel | Middle East, non-Gulf jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Israel |
| `URY` | Uruguay | second South America jurisdiction added this phase, regional e-gov leader | Independent Public-Sector Market-Entry & Procurement Compliance Service — Uruguay |

- **Ukraine**: Prozorro, the open-data public e-procurement system
  through which all state, municipal and state-owned-enterprise tenders
  flow (launched 2014, internationally recognized, OGP/World Procurement
  Award winner); EDR (Unified State Register of Legal Entities,
  Individual Entrepreneurs and Public Associations) via the Ministry of
  Justice, with the EDRPOU code as primary cross-system identifier.
- **Israel**: Government Procurement Administration (GPA, mr.gov.il)
  digital tender portal; Registrar of Companies (Rasham HaChavarot)
  under the Israel Corporations Authority, Ministry of Justice. First
  Middle East jurisdiction distinct from the Gulf states (ARE, SAU, QAT)
  already covered.
- **Uruguay**: Compras Estatales (comprasestatales.gub.uy) operated by
  ARCE (Agencia Reguladora de Compras Estatales); RUPE (Registro Único
  de Proveedores del Estado) centralized supplier registry, requiring an
  active RUT with the DGI tax authority.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 66/212 total blueprints (47 country + 19 Japan
agency); 13 tests / 789 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 44/193 to 47/193.
- (+) First Middle East jurisdiction outside the Gulf (Israel) added;
  Ukraine adds a globally celebrated open-data procurement reference
  case; Uruguay deepens South America coverage with a recognized
  e-government leader.
- (+) All 3 use real, named, web-verified systems (Prozorro/EDR,
  GPA/Registrar of Companies, Compras Estatales/RUPE) matching the
  honesty discipline of prior batches.
- (−) 146/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{ukr,isr,ury}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041900 (country maturity promotion batch 14: CHN/CRI/CZE)
