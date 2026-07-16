# ADR-2607041000: cloud-itonami-iso3166 — country maturity promotion, batch 5 (Canada / New Zealand / South Africa)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607040900 batch 4: POL/MEX/SAU). This ADR adds 3 more
countries, each a second jurisdiction within a region already touched but
with materially distinct registration/local-content mechanics from the
first.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `CAN` | Canada | second North America (distinct federal system from USA) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Canada |
| `NZL` | New Zealand | second Oceania (distinct from Australia) | Independent Public-Sector Market-Entry & Procurement Compliance Service — New Zealand |
| `ZAF` | South Africa | second Sub-Saharan Africa (distinct from Kenya) | Independent Public-Sector Market-Entry & Procurement Compliance Service — South Africa |

- **Canada**: CanadaBuys federal procurement portal, Business Number (BN)
  registration via CRA, and the Procurement Strategy for Indigenous
  Business (PSIB) mandatory minimum-participation targets/set-asides.
- **New Zealand**: GETS (Government Electronic Tenders Service), NZBN
  registration, and the Government Procurement Rules' broader-outcomes
  requirement weighing Māori business participation and regional economic
  development.
- **South Africa**: Central Supplier Database (CSD) + National Treasury
  eTender Publication Portal, CIPC company registration, and B-BBEE
  (Broad-Based Black Economic Empowerment) scorecard status — a mandatory,
  heavily-weighted disclosure for most government contracts.

Same structure, robotics exemption, and actuation gate as all prior
country blueprints.

Registry now stands at 36/212 total blueprints (17 country + 19 Japan
agency); 13 tests / 669 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 14/193 to 17/193. This batch
  specifically demonstrates that "one blueprint per region" is not the
  model — Canada/USA, New Zealand/Australia, and South Africa/Kenya each
  have genuinely distinct registration portals and local-content regimes
  despite regional proximity.
- (+) All 3 use real, named systems (CanadaBuys, GETS, CSD/B-BBEE)
  matching the honesty discipline of prior batches.
- (−) 176/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{can,nzl,zaf}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607040900 (country maturity promotion batch 4: POL/MEX/SAU)
