# ADR-2607041600: cloud-itonami-iso3166 — country maturity promotion, batch 11 (France / Egypt / Pakistan)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041500 batch 10: BGD/ARG/GHA). This is the eleventh
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `FRA` | France | major Western European economy, EU single market | Independent Public-Sector Market-Entry & Procurement Compliance Service — France |
| `EGY` | Egypt | first North Africa jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Egypt |
| `PAK` | Pakistan | third South Asia jurisdiction (after India, Bangladesh) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Pakistan |

- **France**: PLACE (Plateforme des Achats de l'État,
  marches-publics.gouv.fr) state e-procurement platform, BOAMP (Bulletin
  officiel des annonces des marchés publics) tender-notice publication,
  SIREN/SIRET registration via INSEE through the Guichet unique. EU
  member state — no national-content quota, matching the treatment
  already applied to ESP/NLD/IRL/POL/ITA.
- **Egypt**: Unified Public Procurement Law (Law No. 182 of 2018) and its
  Executive Regulations (Ministry of Finance Decree No. 692/2019),
  Government Procurement Portal tender publication, GAFI (General
  Authority for Investment and Free Zones) company registry, Egyptian
  Tax Authority (ETA) tax registration. First North Africa jurisdiction
  in this registry, distinct from the sub-Saharan African jurisdictions
  already covered (KEN, NGA, ZAF, GHA).
- **Pakistan**: PPRA (Public Procurement Regulatory Authority) and its
  EPADS (Electronic Procurement & Disposal System) federal e-procurement
  portal, SECP company incorporation, FBR-issued National Tax Number
  (NTN). All three facts verified via web search before drafting
  (honesty discipline maintained from prior batches).

Same structure, robotics exemption, and actuation gate as all prior
country blueprints.

Registry now stands at 54/212 total blueprints (35 country + 19 Japan
agency); 13 tests / 741 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 32/193 to 35/193.
- (+) First North Africa jurisdiction (Egypt) and a major EU economy
  (France) added; South Asia coverage deepens to 3 countries.
- (+) All 3 use real, named, web-verified systems (PLACE/BOAMP/INSEE,
  Law 182/2018/GAFI/ETA, PPRA/EPADS/SECP/FBR) matching the honesty
  discipline of prior batches.
- (−) 158/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{fra,egy,pak}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041500 (country maturity promotion batch 10: BGD/ARG/GHA)
