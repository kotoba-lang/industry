# ADR-2607042200: cloud-itonami-iso3166 — country maturity promotion, batch 17 (Georgia / Jordan / Senegal)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607042100 batch 16: EST/RWA/PAN). This is the seventeenth
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `GEO` | Georgia | first Caucasus jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Georgia |
| `JOR` | Jordan | second Middle East / Levant jurisdiction (after Israel) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Jordan |
| `SEN` | Senegal | third West Africa jurisdiction (after Ghana, Nigeria) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Senegal |

- **Georgia**: Unified Electronic System of State Procurement (Ge-GP)
  overseen by the State Procurement Agency (an independent LEPL
  established 2014, internationally recognized for anti-corruption
  transparency); National Agency of Public Registry (NAPR) under the
  Ministry of Justice for business registration.
- **Jordan**: JONEPS (Jordan e-Procurement System, launched 2018) used
  alongside the Government Tenders Directorate (GTD, established 1982);
  Companies Control Department (CCD) under the Ministry of Industry,
  Trade and Supply for business registration. Second Middle East
  jurisdiction, distinct from Israel and the Gulf states already
  covered.
- **Senegal**: Portail des Marchés Publics (marchespublics.sn)
  supervised by ARMP and DCMP; RCCM (Registre du Commerce et du Crédit
  Mobilier) business registration via APIX's Bureau de Création
  d'Entreprise one-stop shop. Third West Africa jurisdiction, deepening
  regional coverage alongside Ghana and Nigeria, and the first
  OHADA-zone (French-civil-law harmonized commercial code) jurisdiction
  in this registry.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 72/212 total blueprints (53 country + 19 Japan
agency); 13 tests / 813 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 50/193 to 53/193.
- (+) First Caucasus jurisdiction (Georgia) added; Middle East and West
  Africa each deepen to 2-3 jurisdictions; Senegal is the first
  OHADA-zone jurisdiction, a distinct commercial-law tradition from
  common-law and other civil-law jurisdictions already covered.
- (+) All 3 use real, named, web-verified systems (Ge-GP/NAPR,
  JONEPS/GTD/CCD, marchespublics.sn/RCCM) matching the honesty
  discipline of prior batches.
- (−) 140/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{geo,jor,sen}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607042100 (country maturity promotion batch 16: EST/RWA/PAN)
