# ADR-2607041100: cloud-itonami-iso3166 — country maturity promotion, batch 6 (Chile / Nigeria / Indonesia)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041000 batch 5: CAN/NZL/ZAF). This batch reaches **20/193**
countries at `:maturity :blueprint` — a round milestone for the sweep.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `CHL` | Chile | third South America (distinct from Brazil/Mexico) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Chile |
| `NGA` | Nigeria | distinct West Africa (from Kenya/South Africa) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Nigeria |
| `IDN` | Indonesia | second Southeast Asia (distinct from Singapore) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Indonesia |

- **Chile**: ChileCompra (Mercado Público) central procurement platform,
  RUT/SII tax registration.
- **Nigeria**: Nigeria Open Contracting Portal (NOCOPO) overseen by the
  Bureau of Public Procurement (BPP), CAC business registration, and
  Nigerian Content Development and Monitoring Board (NCDMB) local-content
  thresholds.
- **Indonesia**: SPSE electronic procurement system overseen by LKPP, OSS/
  NIB business registration, and TKDN (domestic-content-level) scoring.

Same structure, robotics exemption, and actuation gate as all prior
country blueprints.

Registry now stands at 39/212 total blueprints (20 country + 19 Japan
agency); 13 tests / 681 assertions, all green.

## Consequences

- (+) Country-level coverage reaches 20/193 (>10%), spanning 3 South
  American, 3 Sub-Saharan African, 2 Southeast Asian, 2 Middle Eastern, 2
  North American, and 2 Oceania jurisdictions among its 20.
- (+) All 3 use real, named systems (ChileCompra, NOCOPO, SPSE) matching
  the honesty discipline of prior batches.
- (−) 173/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{chl,nga,idn}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041000 (country maturity promotion batch 5: CAN/NZL/ZAF)
