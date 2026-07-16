# ADR-2607042500: cloud-itonami-iso3166 — country maturity promotion, batch 20 (Denmark / Latvia / Ecuador)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607042400 batch 19: NOR/LKA/BWA). This is the twentieth
country promotion batch since ADR-2607032330 — a milestone round number.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `DNK` | Denmark | completes full Nordic five (SWE/FIN/NOR/DNK) with Iceland still `:spec` | Independent Public-Sector Market-Entry & Procurement Compliance Service — Denmark |
| `LVA` | Latvia | second Baltic jurisdiction (after Estonia) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Latvia |
| `ECU` | Ecuador | seventh South America jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Ecuador |

- **Denmark**: udbud.dk, the public-procurement portal operated by the
  Public Procurement Division of the Danish Competition and Consumer
  Authority; CVR (Central Business Register) via the Virk portal,
  operated by the Danish Business Authority. EU member state — no
  national-content quota, matching the treatment already applied to
  ESP/NLD/FRA/IRL/POL/ITA/SWE/CZE/EST/FIN.
- **Latvia**: Electronic Procurement System (EIS, eis.gov.lv) with
  notice monitoring via IUB (Procurement Monitoring Bureau); Register of
  Enterprises (UR, ur.gov.lv). Second Baltic jurisdiction, deepening
  regional coverage alongside Estonia.
- **Ecuador**: SERCOP (Servicio Nacional de Contratación Pública) and
  its RUP (Registro Único de Proveedores) supplier record; SRI
  (Servicio de Rentas Internas) RUC (Registro Único de Contribuyentes)
  tax registration, a precondition for RUP. Seventh South America
  jurisdiction alongside Brazil, Argentina, Chile, Colombia, Peru and
  Uruguay.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 81/212 total blueprints (62 country + 19 Japan
agency); 13 tests / 849 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 59/193 to 62/193.
- (+) Nordic coverage effectively completes (4 of 5 Nordic states now
  covered: Sweden, Finland, Norway, Denmark — only Iceland remains
  `:spec`); Baltic deepens to 2 jurisdictions; South America deepens to
  7 jurisdictions.
- (+) All 3 use real, named, web-verified systems (udbud.dk/CVR,
  EIS/IUB/UR, SERCOP/RUP/SRI/RUC) matching the honesty discipline of
  prior batches.
- (−) 131/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{dnk,lva,ecu}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607042400 (country maturity promotion batch 19: NOR/LKA/BWA)
