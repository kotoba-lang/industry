# ADR-2607041400: cloud-itonami-iso3166 — country maturity promotion, batch 9 (Philippines / Peru / Italy)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607041300 batch 8: THA/COL/ESP).

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `PHL` | Philippines | fifth Southeast Asia | Independent Public-Sector Market-Entry & Procurement Compliance Service — Philippines |
| `PER` | Peru | fifth South America | Independent Public-Sector Market-Entry & Procurement Compliance Service — Peru |
| `ITA` | Italy | fifth Western Europe/EU | Independent Public-Sector Market-Entry & Procurement Compliance Service — Italy |

- **Philippines**: PhilGEPS (Philippine Government Electronic Procurement
  System), DTI/SEC business registration.
- **Peru**: SEACE (Sistema Electrónico de Contrataciones del Estado),
  RUC/RNP registration via SUNAT.
- **Italy**: MEPA (Mercato Elettronico della Pubblica Amministrazione)
  operated by CONSIP, Registro delle Imprese + Partita IVA registration.

Same structure, robotics exemption, and actuation gate as all prior
country blueprints.

Registry now stands at 48/212 total blueprints (29 country + 19 Japan
agency); 13 tests / 717 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 26/193 to 29/193.
- (+) All 3 use real, named systems (PhilGEPS, SEACE, MEPA) matching the
  honesty discipline of prior batches.
- (+) README's blueprint-table summary sentence simplified (no longer
  itemizes every region multiplier, which had become unwieldy at 29
  entries) — same trim applied to the registry note field in
  ADR-2607041300.
- (−) 164/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{phl,per,ita}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607041300 (country maturity promotion batch 8: THA/COL/ESP)
