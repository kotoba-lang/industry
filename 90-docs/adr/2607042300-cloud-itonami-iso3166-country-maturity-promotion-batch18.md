# ADR-2607042300: cloud-itonami-iso3166 — country maturity promotion, batch 18 (Nepal / Finland / Tunisia)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607042200 batch 17: GEO/JOR/SEN). This is the eighteenth
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `NPL` | Nepal | fourth South Asia jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Nepal |
| `FIN` | Finland | third Nordic jurisdiction, EU member | Independent Public-Sector Market-Entry & Procurement Compliance Service — Finland |
| `TUN` | Tunisia | second Maghreb jurisdiction (after Morocco) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Tunisia |

- **Nepal**: Bolpatra (bolpatra.gov.np) e-GP portal operated under the
  Public Procurement Monitoring Office (PPMO, established under the
  Public Procurement Act 2007); Office of the Company Registrar (OCR)
  + PAN certificate from the Inland Revenue Department. Fourth South
  Asia jurisdiction alongside India, Bangladesh and Pakistan.
- **Finland**: Hilma (hankintailmoitukset.fi), Finland's mandatory
  public-procurement notification channel operated by the Ministry of
  Finance under the Hankintalaki (Public Procurement Act 1397/2016);
  PRH Trade Register (Kaupparekisteriote). EU member state — no
  national-content quota, matching the treatment already applied to
  ESP/NLD/FRA/IRL/POL/ITA/SWE/CZE/EST.
- **Tunisia**: TUNEPS electronic public-procurement platform; RNE
  (Registre National des Entreprises) managed by CNRE (Centre national
  du registre des entreprises, established 2018). Second Maghreb
  jurisdiction, alongside Morocco, and second North Africa jurisdiction
  overall, alongside Egypt.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 75/212 total blueprints (56 country + 19 Japan
agency); 13 tests / 825 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 53/193 to 56/193.
- (+) South Asia deepens to 4 jurisdictions; Nordic representation
  deepens to 3 (Sweden, Estonia, Finland); Maghreb/North Africa deepens
  to 2 jurisdictions each.
- (+) All 3 use real, named, web-verified systems (Bolpatra/PPMO/OCR,
  Hilma/PRH, TUNEPS/RNE/CNRE) matching the honesty discipline of prior
  batches.
- (−) 137/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{npl,fin,tun}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607042200 (country maturity promotion batch 17: GEO/JOR/SEN)
