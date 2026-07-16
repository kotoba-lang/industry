# ADR-2607042100: cloud-itonami-iso3166 — country maturity promotion, batch 16 (Estonia / Rwanda / Panama)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607042000 batch 15: UKR/ISR/URY). This is the sixteenth
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `EST` | Estonia | Baltic, EU member, globally renowned digital-government reference | Independent Public-Sector Market-Entry & Procurement Compliance Service — Estonia |
| `RWA` | Rwanda | second East Africa jurisdiction (after Ethiopia) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Rwanda |
| `PAN` | Panama | second Central America jurisdiction (after Costa Rica) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Panama |

- **Estonia**: Riigihangete Register (RHR, riigihanked.riik.ee),
  Estonia's centralized e-procurement platform administered by Riigi
  Tugiteenuste Keskus (State Shared Service Centre); e-Business Register
  (e-äriregister) operated by RIK. EU member state — no
  national-content quota, matching the treatment already applied to
  ESP/NLD/FRA/IRL/POL/ITA/SWE/CZE.
- **Rwanda**: UMUCYO e-Procurement system operated by the Rwanda Public
  Procurement Authority (RPPA); business registration and PKI digital
  certificates via the Rwanda Development Board (RDB), integrated
  directly into UMUCYO. Second East Africa jurisdiction, deepening
  regional coverage alongside Ethiopia.
- **Panama**: PanamaCompra (panamacompra.gob.pa) electronic
  public-procurement system with its Registry of Proponents under the
  Dirección General de Contrataciones Públicas; Registro Público
  (Public Registry, Ministry of Government and Justice) for corporate
  registration. Second Central America jurisdiction, deepening regional
  coverage alongside Costa Rica.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 69/212 total blueprints (50 country + 19 Japan
agency); 13 tests / 801 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 47/193 to 50/193 — a round
  milestone (50/193, roughly a quarter of current UN-member states).
- (+) Estonia adds a widely cited e-government reference case (X-Road,
  e-Residency ecosystem, though this blueprint scopes only to
  procurement/business registration); East Africa and Central America
  each deepen to 2 jurisdictions.
- (+) All 3 use real, named, web-verified systems (RHR/e-Business
  Register, UMUCYO/RDB, PanamaCompra/Registro Público) matching the
  honesty discipline of prior batches.
- (−) 143/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{est,rwa,pan}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607042000 (country maturity promotion batch 15: UKR/ISR/URY)
