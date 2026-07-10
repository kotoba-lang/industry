# ADR-2607105400: cloud-itonami-iso3166 — country maturity promotion, batch 24 (Russia / Belgium / Austria)

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (standing scaffold authorization + coverage-improvement request)

## Context

Continuing the country-level breadth item from ADR-2607042900 (122
countries still `:spec` at second-sweep close). This batch prioritizes
(1) **RUS**, which remained a registry stub while JPN/USA/CHN already
had blueprints, and (2) two EU members that deepen the Central/Western
European cluster (**BEL**, **AUT**).

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `RUS` | Russian Federation | first Eastern Europe / post-Soviet major market at blueprint | Independent Public-Sector Market-Entry & Procurement Compliance Service — Russia |
| `BEL` | Belgium | Western Europe EU member; federal e-Procurement hub | Independent Public-Sector Market-Entry & Procurement Compliance Service — Belgium |
| `AUT` | Austria | Central Europe EU member; USP/BBG cluster | Independent Public-Sector Market-Entry & Procurement Compliance Service — Austria |

- **Russia**: Unified Information System for Procurement (ЕИС,
  zakupki.gov.ru) under 44-FZ / 223-FZ; OGRN/INN via the Federal Tax
  Service (ФНС). First blueprint for a major non-EU Eastern European /
  post-Soviet jurisdiction.
- **Belgium**: federal e-Procurement platform (publicprocurement.be /
  BOSA) — e-Notification, e-Tendering, Free Market, e-Catalogue; CBE
  enterprise number + VAT. EU member — no national-content quota
  (`:eu-single-market-access` treatment matching prior EU members).
- **Austria**: USP (Unternehmensserviceportal, usp.gv.at) eProcurement
  under BVergG 2018; federal central purchasing via BBG
  (Bundesbeschaffung GmbH); Firmenbuch + UID. EU member, same single-
  market treatment as BEL.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry after this batch **and** concurrent JPN `:implemented`
promotion (ADR-2607105300):

- Total entries: 212 (193 countries + 19 Japan agencies)
- `:implemented` 1 (JPN)
- `:blueprint` 92 (73 country + 19 Japan agency)
- `:spec` 119
- 13 tests / 890 assertions, all green

## Consequences

- (+) Country-level coverage grows from 71/193 to 74/193 at-or-above
  blueprint (73 blueprint + 1 implemented).
- (+) RUS closes the G20/major-power gap left after CHN/USA/JPN.
- (+) BEL/AUT deepen the EU cluster (with ESP/NLD/FRA/… already present).
- (−) 119/193 countries remain `:spec`.
- superproject registration: no new west project for the blueprints
  (standalone `cloud-itonami` org convention). `kotoba-lang/iso3166`'s
  west.yml pin advances to the new commit.

## Artifacts

- lib: `kotoba-lang/iso3166` (updated)
- blueprint repos (public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{rus,bel,aut}`

## References

- ADR-2607032330 (family creation)
- ADR-2607042800 (batch 23: SVK/BOL/KHM)
- ADR-2607042900 (second sweep closing)
- ADR-2607105300 (JPN `:implemented`, concurrent)
