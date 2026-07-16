# ADR-2607040600: cloud-itonami-iso3166 — country maturity promotion, batch 2 (Brazil / United Kingdom / Singapore)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

With the Japan agency-level extension complete (ADR-2607040100 through
ADR-2607040500, 19/19), the self-paced `/loop` maturity/coverage sweep
shifts back to the COUNTRY level: only 5/193 countries
(JPN/USA/DEU/KEN/IND) had a published `cloud-itonami-iso3166-{code}`
blueprint. This ADR promotes the next 3, chosen to add regions and legal
traditions not yet represented (East Asia / North America / Western
Europe / Sub-Saharan Africa / South Asia were already covered).

## Decision

Promote 3 more countries from `:spec` to `:blueprint`:

| Code | Country | Region/tradition added | Blueprint |
|---|---|---|---|
| `BRA` | Brazil | South America, civil law | Independent Public-Sector Market-Entry & Procurement Compliance Service — Brazil |
| `GBR` | United Kingdom | Common-law Europe, post-Brexit | Independent Public-Sector Market-Entry & Procurement Compliance Service — United Kingdom |
| `SGP` | Singapore | Southeast Asia, common-law city-state | Independent Public-Sector Market-Entry & Procurement Compliance Service — Singapore |

- **Brazil**: ComprasNet / Portal Nacional de Contratações Públicas (PNCP)
  registration under Lei 14.133/2021, CNPJ + SICAF supplier registration,
  ME/EPP (micro/small enterprise) preferential-bidding margins.
- **United Kingdom**: Find a Tender service (FTS) / Contracts Finder under
  the Procurement Act 2023 (post-Brexit successor to the Public Contracts
  Regulations 2015), Companies House + HMRC registration, social-value
  scoring.
- **Singapore**: GeBIZ government e-procurement portal, ACRA (UEN)
  business registration, IRAS GST registration, LEAD-scheme preferential
  access for qualifying SMEs.

Same structure, robotics exemption, and actuation gate as the original 5
country blueprints (ADR-2607032330): README + blueprint.edn +
docs/business-model.md + docs/operator-guide.md + governance docs +
AGPL-3.0-or-later, `:itonami.blueprint/robotics false`,
`:required-technologies [:identity :forms :dmn :bpmn :audit-ledger]`,
`:filing/submit` never automated.

Registry now stands at 27/212 total blueprints (8 country + 19 Japan
agency); 13 tests / 633 assertions, all green.

## Consequences

- (+) Country-level coverage grows from 5/193 to 8/193, adding 3 new
  regions/legal traditions.
- (+) All 3 new countries use real, named e-procurement portals and
  registration schemes (ComprasNet/PNCP, Find a Tender, GeBIZ), matching
  the honesty discipline of the original 5.
- (−) 185/193 countries remain `:spec`; no agency-level breakdown exists
  for any of the 8 blueprint countries except Japan.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-{bra,gbr,sgp}` (3 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints) — the country-level family this ADR extends.
- ADR-2607040500 (Japan agency coverage complete) — the immediately
  preceding milestone; this ADR shifts the sweep's focus back to country
  breadth.
