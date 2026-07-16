# ADR-2607041800: cloud-itonami-iso3166 — country maturity promotion, batch 13 (Sweden / Kazakhstan / Qatar)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041700 batch 12: TUR/MAR/ETH). This is the thirteenth
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `SWE` | Sweden | Nordic, EU member state | Independent Public-Sector Market-Entry & Procurement Compliance Service — Sweden |
| `KAZ` | Kazakhstan | first Central Asia jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Kazakhstan |
| `QAT` | Qatar | third Gulf jurisdiction (after UAE, Saudi Arabia) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Qatar |

- **Sweden**: TendSign (Visma TendSign) e-procurement platform used by
  most Swedish public agencies/municipalities; Bolagsverket (Swedish
  Companies Registration Office) + Skatteverket F-skatt/VAT registration.
  EU member state — no national-content quota, matching the treatment
  already applied to ESP/NLD/FRA/IRL/POL/ITA.
- **Kazakhstan**: Goszakup (goszakup.gov.kz), the centralized
  e-procurement portal operated by the Ministry of Finance; Business
  Identification Number (BIN) via the State Revenue Committee + EDS
  (Electronic Digital Signature) via eGov.kz. First Central Asia
  jurisdiction in this registry.
- **Qatar**: Monaqasat (monaqasat.mof.gov.qa), the unified e-tendering
  platform operated by the Ministry of Finance; Commercial Registration
  and vendor/supplier classification via the Ministry of Commerce and
  Industry (MOCI). Third Gulf jurisdiction, distinct governance model
  from the two already covered (ARE, SAU).

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 60/212 total blueprints (41 country + 19 Japan
agency); 13 tests / 765 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 38/193 to 41/193.
- (+) First Central Asia jurisdiction (Kazakhstan) added; Gulf coverage
  deepens to 3 countries; Nordic region represented for the first time.
- (+) All 3 use real, named, web-verified systems (TendSign/Bolagsverket,
  Goszakup/BIN/EDS, Monaqasat/MOCI) matching the honesty discipline of
  prior batches.
- (−) 152/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{swe,kaz,qat}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041700 (country maturity promotion batch 12: TUR/MAR/ETH)
