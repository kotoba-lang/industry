# ADR-2607041300: cloud-itonami-iso3166 — country maturity promotion, batch 8 (Thailand / Colombia / Spain)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041200 batch 7: IRL/NLD/VNM).

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `THA` | Thailand | fourth Southeast Asia | Independent Public-Sector Market-Entry & Procurement Compliance Service — Thailand |
| `COL` | Colombia | fourth South America | Independent Public-Sector Market-Entry & Procurement Compliance Service — Colombia |
| `ESP` | Spain | fourth Western Europe/EU | Independent Public-Sector Market-Entry & Procurement Compliance Service — Spain |

- **Thailand**: e-GP (Electronic Government Procurement) managed by the
  Comptroller General's Department, DBD business registration.
- **Colombia**: SECOP II managed by Colombia Compra Eficiente, RUT/RUP
  registration via local chambers of commerce.
- **Spain**: Plataforma de Contratación del Sector Público (PLACSP),
  Registro Mercantil + NIF registration.

Same structure, robotics exemption, and actuation gate as all prior
country blueprints.

Registry now stands at 45/212 total blueprints (26 country + 19 Japan
agency); 13 tests / 705 assertions, all green.

### Registry note field trimmed

`kotoba-lang/iso3166`'s registry.edn `:kotoba.registry/note` had grown to
list every individual country/agency promoted in every ADR verbatim,
making it unwieldy. This batch trims it to describe the structural
decisions (Japan agency-level extension, its completion) and points to
the ADR history (`90-docs/adr/`) and this repo's README for the current
country-level count, rather than repeating the full batch list inline on
every future promotion.

## Consequences

- (+) Country-level coverage grows from 23/193 to 26/193.
- (+) All 3 use real, named systems (e-GP, SECOP II, PLACSP) matching the
  honesty discipline of prior batches.
- (+) Registry note field is now maintainable going forward — future
  batches don't need to append another clause to an ever-growing string.
- (−) 167/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{tha,col,esp}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041200 (country maturity promotion batch 7: IRL/NLD/VNM)
