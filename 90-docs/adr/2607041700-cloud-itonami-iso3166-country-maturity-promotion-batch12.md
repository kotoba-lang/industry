# ADR-2607041700: cloud-itonami-iso3166 — country maturity promotion, batch 12 (Turkey / Morocco / Ethiopia)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041600 batch 11: FRA/EGY/PAK). This is the twelfth
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `TUR` | Republic of Türkiye | trans-continental, EU customs union (not EU member) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Turkey |
| `MAR` | Morocco | second North Africa jurisdiction (after Egypt), first Maghreb | Independent Public-Sector Market-Entry & Procurement Compliance Service — Morocco |
| `ETH` | Ethiopia | first East Africa jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Ethiopia |

- **Turkey**: EKAP (Elektronik Kamu Alımları Platformu), the mandatory
  e-procurement gateway operated by the Public Procurement Authority
  (Kamu İhale Kurumu, KİK); MERSİS business-registry number; Public
  Procurement Law No. 4734's domestic-goods price-preference margin.
- **Morocco**: national tendering portal marchespublics.gov.ma managed by
  the Trésorerie Générale du Royaume; OMPIC-administered Registre du
  Commerce + ICE number; public procurement code preference margins for
  national SMEs.
- **Ethiopia**: e-GP (Electronic Government Procurement, egp.ppa.gov.et)
  rolled out by the Federal Public Procurement and Property Authority
  (PPPA) since its 2021 pilot; Ministry of Trade and Regional
  Integration business registration + TIN. First East Africa
  jurisdiction, distinct from the existing Kenya, Nigeria, Ghana, South
  Africa (sub-Saharan but different regions) coverage.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 57/212 total blueprints (38 country + 19 Japan
agency); 13 tests / 753 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 35/193 to 38/193.
- (+) First East Africa jurisdiction (Ethiopia) and second Maghreb/North
  Africa jurisdiction (Morocco) added; Turkey adds a trans-continental,
  EU-customs-union-but-non-EU-member jurisdiction distinct from all prior
  EU-member entries.
- (+) All 3 use real, named, web-verified systems (EKAP/MERSIS,
  marchespublics.gov.ma/OMPIC, e-GP/PPPA) matching the honesty discipline
  of prior batches.
- (−) 155/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{tur,mar,eth}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041600 (country maturity promotion batch 11: FRA/EGY/PAK)
