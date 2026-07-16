# ADR-2607042400: cloud-itonami-iso3166 — country maturity promotion, batch 19 (Norway / Sri Lanka / Botswana)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage sweep at the country
level (ADR-2607042300 batch 18: NPL/FIN/TUN). This is the nineteenth
country promotion batch since ADR-2607032330.

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Relationship to existing coverage | Blueprint |
|---|---|---|---|
| `NOR` | Norway | fourth Nordic jurisdiction, EEA (not EU) member | Independent Public-Sector Market-Entry & Procurement Compliance Service — Norway |
| `LKA` | Sri Lanka | fifth South Asia jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Sri Lanka |
| `BWA` | Botswana | first Southern Africa jurisdiction | Independent Public-Sector Market-Entry & Procurement Compliance Service — Botswana |

- **Norway**: Doffin, the national notification database for public
  procurement operated by DFØ (the Norwegian Agency for Public and
  Financial Management); Register of Business Enterprises maintained by
  the Brønnøysund Register Centre under the Ministry of Trade, Industry
  and Fisheries. Norway is an EEA member (not an EU member state) —
  procurement follows EU-directive-aligned open tendering under the EEA
  Agreement, distinguished in this blueprint from the EU-member
  treatment used for ESP/NLD/FRA/IRL/POL/ITA/SWE/CZE/EST/FIN.
- **Sri Lanka**: e-GP (electronic Government Procurement) platform per
  Department of Public Finance directives; Department of the Registrar
  of Companies (DRC, established 1938) via the eROC online
  incorporation system under the Companies Act No. 7 of 2007. Fifth
  South Asia jurisdiction alongside India, Bangladesh, Pakistan and
  Nepal.
- **Botswana**: IPMS (Integrated Procurement Management System,
  ipms.ppadb.co.bw) operated by the Public Procurement Regulatory
  Authority (PPRA, formerly PPADB, transitioned 2022); Companies and
  Intellectual Property Authority (CIPA) registration + Botswana
  Unified Revenue Service (BURS) Tax Clearance Certificate. First
  Southern Africa jurisdiction in this registry, distinct from the East,
  West and North African jurisdictions already covered.

All three facts verified via web search before drafting (honesty
discipline maintained from prior batches). Same structure, robotics
exemption, and actuation gate as all prior country blueprints.

Registry now stands at 78/212 total blueprints (59 country + 19 Japan
agency); 13 tests / 837 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 56/193 to 59/193.
- (+) First Southern Africa jurisdiction (Botswana) added; South Asia
  deepens to 5 jurisdictions; Norway is the first EEA-not-EU jurisdiction,
  distinguishing that trade-regime nuance from the EU-member treatment.
- (+) All 3 use real, named, web-verified systems (Doffin/Brønnøysund,
  e-GP/eROC, IPMS/CIPA/BURS) matching the honesty discipline of prior
  batches.
- (−) 134/193 countries remain `:spec`.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s
  existing west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{nor,lka,bwa}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints)
- ADR-2607042300 (country maturity promotion batch 18: NPL/FIN/TUN)
