# ADR-2607041200: cloud-itonami-iso3166 — country maturity promotion, batch 7 (Ireland / Netherlands / Vietnam)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041100 batch 6: CHL/NGA/IDN). This batch adds two more
Western European EU member states with distinct national procurement/
registration mechanics, plus a third Southeast Asian jurisdiction.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `IRL` | Ireland | second Western Europe/EU (distinct from Germany/Poland) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Ireland |
| `NLD` | Netherlands | third Western Europe/EU | Independent Public-Sector Market-Entry & Procurement Compliance Service — Netherlands |
| `VNM` | Vietnam | third Southeast Asia (distinct from Singapore/Indonesia) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Vietnam |

- **Ireland**: eTenders official public procurement platform, CRO company
  registration, Revenue VAT registration.
- **Netherlands**: TenderNed official public procurement platform, KVK
  business registration, Belastingdienst BTW (VAT) registration.
- **Vietnam**: Vietnam National E-Procurement System (VNEPS,
  muasamcong.mpi.gov.vn) overseen by the Ministry of Planning and
  Investment, Enterprise Registration Certificate + tax-code registration,
  domestic-preference margins under the Law on Bidding.

Same structure, robotics exemption, and actuation gate as all prior
country blueprints.

Registry now stands at 42/212 total blueprints (23 country + 19 Japan
agency); 13 tests / 693 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 20/193 to 23/193.
- (+) All 3 use real, named systems (eTenders, TenderNed, VNEPS) matching
  the honesty discipline of prior batches.
- (−) 170/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{irl,nld,vnm}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041100 (country maturity promotion batch 6: CHL/NGA/IDN)
