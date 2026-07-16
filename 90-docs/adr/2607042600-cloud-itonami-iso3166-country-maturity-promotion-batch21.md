# ADR-2607042600: cloud-itonami-iso3166 — country maturity promotion, batch 21 (Iceland / Lithuania / Zambia)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607042500 batch 20: DNK/LVA/ECU). This is the twenty-first
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `ISL` | Iceland | completes the full Nordic five (SWE/FIN/NOR/DNK/ISL) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Iceland |
| `LTU` | Lithuania | completes the full Baltic three (EST/LVA/LTU) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Lithuania |
| `ZMB` | Zambia | second Southern Africa jurisdiction (after Botswana) | Independent Public-Sector Market-Entry & Procurement Compliance Service — Zambia |

- **Iceland**: Útboðsvefur.is, the public-tender publicity platform for
  contracts above national/EEA thresholds; Fyrirtækjaskrá (Company
  Register) maintained by Skatturinn (the Icelandic Tax Authority).
  Iceland is an EEA member (not an EU member state), matching the
  EEA-not-EU treatment already established for Norway.
- **Lithuania**: CVP IS (Central Public Procurement Information System,
  viesiejipirkimai.lt), the single mandatory portal for all Lithuanian
  public procurement, run by the Public Procurement Office; Register of
  Legal Entities (Juridinių asmenų registras) maintained by the State
  Enterprise Centre of Registers. EU member state — no national-content
  quota, matching the treatment already applied to
  ESP/NLD/FRA/IRL/POL/ITA/SWE/CZE/EST/FIN/DNK/LVA.
- **Zambia**: ZPPA e-GP (Electronic Government Procurement) system
  operated by the Zambia Public Procurement Authority (established
  under the Public Procurement Act No. 12 of 2008), integrated with
  IFMIS, PACRA and ZRA; PACRA (Patents and Companies Registration
  Agency) business registration. Second Southern Africa jurisdiction,
  deepening regional coverage alongside Botswana.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 84/212 total blueprints (65 country + 19 Japan
agency); 13 tests / 861 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 62/193 to 65/193.
- (+) The Nordic region is now fully covered (5/5: Sweden, Finland,
  Norway, Denmark, Iceland); the Baltic region is now fully covered
  (3/3: Estonia, Latvia, Lithuania); Southern Africa deepens to 2
  jurisdictions.
- (+) All 3 use real, named, web-verified systems (Útboðsvefur.is/
  Fyrirtækjaskrá, CVP IS/Register of Legal Entities, ZPPA e-GP/PACRA)
  matching the honesty discipline of prior batches.
- (−) 128/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{isl,ltu,zmb}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607042500 (country maturity promotion batch 20: DNK/LVA/ECU)
