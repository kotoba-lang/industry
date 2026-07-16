# ADR-2607040900: cloud-itonami-iso3166 — country maturity promotion, batch 4 (Poland / Mexico / Saudi Arabia)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, resumed after ADR-2607040800 closing summary)

## Context

ADR-2607040800 closed the initial `/loop` sweep with a summary and
explicit future-work list. The owner re-invoked `/loop 30min 成熟度,
coverage を向上`, resuming the same task. Per the closing ADR's future-work
priority #1 ("continue country-level breadth"), this batch adds 3 more
countries, chosen for regions/traditions still under-represented (Central/
Eastern Europe, a second Latin American jurisdiction, a second Middle
Eastern jurisdiction).

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Region/tradition added | Blueprint |
|---|---|---|---|
| `POL` | Poland | Central/Eastern Europe, EU member | Independent Public-Sector Market-Entry & Procurement Compliance Service — Poland |
| `MEX` | Mexico | Latin America (second jurisdiction, distinct from Brazil) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Mexico |
| `SAU` | Saudi Arabia | Middle East (second jurisdiction, distinct from UAE) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Saudi Arabia |

- **Poland**: e-Zamówienia central procurement platform (implementing EU
  procurement directives), KRS (National Court Register) + NIP tax
  registration.
- **Mexico**: CompraNet federal e-procurement system, RFC + RUPC supplier
  registration, USMCA-linked reciprocity rules for cross-border bidders.
- **Saudi Arabia**: Etimad unified e-procurement/e-payment platform,
  Commercial Registration (CR), LCGPA local-content scoring and Nitaqat
  (Saudization) workforce-localization requirements.

Same structure, robotics exemption, and actuation gate as all prior
country blueprints.

Registry now stands at 33/212 total blueprints (14 country + 19 Japan
agency); 13 tests / 657 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 11/193 to 14/193, adding Central/
  Eastern Europe and a second jurisdiction each for Latin America and the
  Middle East (demonstrating that "one blueprint per region" isn't the
  ceiling — multiple distinct jurisdictions within a region are equally
  valid, since each has genuinely different registration/portal/local-
  content mechanics).
- (+) All 3 use real, named systems (e-Zamówienia, CompraNet, Etimad)
  matching the honesty discipline of prior batches.
- (−) 179/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{pol,mex,sau}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607040700 (country maturity promotion batch 3: ARE/AUS/KOR)
- ADR-2607040800 (sweep closing summary) — the future-work list this ADR
  acts on.
