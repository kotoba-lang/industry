# ADR-2607042700: cloud-itonami-iso3166 — country maturity promotion, batch 22 (Hungary / Croatia / Namibia)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607042600 batch 21: ISL/LTU/ZMB). This is the twenty-second
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `HUN` | Hungary | Central Europe, EU member | Independent Public-Sector Market-Entry & Procurement Compliance Service — Hungary |
| `HRV` | Croatia | Balkans, EU member | Independent Public-Sector Market-Entry & Procurement Compliance Service — Croatia |
| `NAM` | Namibia | third Southern Africa jurisdiction (after Botswana, Zambia) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Namibia |

- **Hungary**: EKR (Elektronikus Közbeszerzési Rendszer, ekr.gov.hu),
  the mandatory centralized e-procurement platform operated by the
  Public Procurement Authority since 2018; Cégjegyzékszám verifiable via
  the e-cégjegyzék (Electronic Company Register) run by the Company
  Information Service of the Ministry of Justice. EU member state — no
  national-content quota.
- **Croatia**: EOJN (Elektronički oglasnik javne nabave, eojn.nn.hr),
  the mandatory publication channel for public-procurement notices,
  operated by Narodne novine on behalf of the Ministry of Economy;
  Sudski registar (Court Registry) via the e-Sudski registar portal. EU
  member state — no national-content quota, matching the treatment
  already applied to ESP/NLD/FRA/IRL/POL/ITA/SWE/CZE/EST/FIN/DNK/LVA/LTU.
- **Namibia**: government e-procurement portal (eprocurement.gov.na)
  overseen by the Central Procurement Board of Namibia (CPBN); BIPA
  (Business and Intellectual Property Authority, established under the
  BIPA Act 2016) business registration. Third Southern Africa
  jurisdiction, deepening regional coverage alongside Botswana and
  Zambia.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 87/212 total blueprints (68 country + 19 Japan
agency); 13 tests / 873 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 65/193 to 68/193.
- (+) Central Europe (Hungary) and the Balkans (Croatia) both add EU
  member states; Southern Africa now has 3 covered jurisdictions
  (Botswana, Zambia, Namibia), forming a coherent regional cluster.
- (+) All 3 use real, named, web-verified systems (EKR/Cégjegyzék,
  EOJN/Sudski registar, CPBN/BIPA) matching the honesty discipline of
  prior batches.
- (−) 125/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{hun,hrv,nam}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607042600 (country maturity promotion batch 21: ISL/LTU/ZMB)
