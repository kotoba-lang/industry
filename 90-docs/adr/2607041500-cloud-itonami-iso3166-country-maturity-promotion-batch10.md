# ADR-2607041500: cloud-itonami-iso3166 — country maturity promotion, batch 10 (Bangladesh / Argentina / Ghana)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041400 batch 9: PHL/PER/ITA). This is the tenth country
promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `BGD` | Bangladesh | second South Asia (distinct from India) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Bangladesh |
| `ARG` | Argentina | sixth South America | Independent Public-Sector Market-Entry & Procurement Compliance Service — Argentina |
| `GHA` | Ghana | second West Africa (distinct from Nigeria) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Ghana |

- **Bangladesh**: e-GP (Electronic Government Procurement) managed by the
  Central Procurement Technical Unit (CPTU), RJSC business registration.
- **Argentina**: COMPR.AR official public procurement portal, CUIT tax
  registration via ARCA, Compre Argentino y Contrate Nacional preference
  margins.
- **Ghana**: GHANEPS (Ghana Electronic Procurement System) managed by the
  Public Procurement Authority, Registrar General's Department business
  registration.

Same structure, robotics exemption, and actuation gate as all prior
country blueprints.

Registry now stands at 51/212 total blueprints (32 country + 19 Japan
agency); 13 tests / 729 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 29/193 to 32/193.
- (+) All 3 use real, named systems (e-GP, COMPR.AR, GHANEPS) matching the
  honesty discipline of prior batches.
- (−) 161/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{bgd,arg,gha}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041400 (country maturity promotion batch 9: PHL/PER/ITA)
