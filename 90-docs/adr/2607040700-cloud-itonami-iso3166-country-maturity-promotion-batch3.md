# ADR-2607040700: cloud-itonami-iso3166 — country maturity promotion, batch 3 (UAE / Australia / South Korea)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607040600 batch 2: BRA/GBR/SGP). This ADR adds 3 more
countries, chosen to cover regions not yet represented (Middle East,
Oceania, non-Japan East Asia).

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Region added | Blueprint |
|---|---|---|---|
| `ARE` | United Arab Emirates | Middle East | Independent Public-Sector Market-Entry & Procurement Compliance Service — United Arab Emirates |
| `AUS` | Australia | Oceania | Independent Public-Sector Market-Entry & Procurement Compliance Service — Australia |
| `KOR` | South Korea | East Asia (non-Japan) | Independent Public-Sector Market-Entry & Procurement Compliance Service — South Korea |

- **UAE**: Department of Economic Development (DED) trade license or
  free-zone authority registration, In-Country Value (ICV) certification
  for ICV-weighted tenders, Federal Tax Authority (FTA) VAT registration.
- **Australia**: AusTender procurement-information system, ABN (via ABR) +
  ASIC company registration, GST registration with the ATO, and the
  Indigenous Procurement Policy (IPP) mandatory minimum-purchase
  targets/set-asides for Indigenous-owned businesses.
- **South Korea**: KONEPS (Korea ON-line E-Procurement System) — the
  Public Procurement Service's unified national e-procurement platform —
  business registration with the National Tax Service, and the SME
  product purchase-promotion program reserving a share of public
  purchasing for registered SMEs.

Same structure, robotics exemption, and actuation gate as the prior
country blueprints.

Registry now stands at 30/212 total blueprints (11 country + 19 Japan
agency); 13 tests / 645 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 8/193 to 11/193, adding Middle
  East, Oceania, and a second East Asian jurisdiction distinct from Japan.
- (+) All 3 use real, named systems (AusTender, KONEPS, ICV) matching the
  honesty discipline of prior batches.
- (−) 182/193 countries remain `:spec`; no agency-level breakdown exists
  for any of the 11 blueprint countries except Japan.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{are,aus,kor}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607040600 (country maturity promotion batch 2: BRA/GBR/SGP)
