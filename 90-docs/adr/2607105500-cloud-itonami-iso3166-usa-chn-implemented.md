# ADR-2607105500: cloud-itonami-iso3166-{usa,chn} deepened to `:implemented`

**Status**: accepted
**Date**: 2026-07-10

## Decision

Promote **USA** and **CHN** from `:blueprint` to `:implemented` by forking
the JPN `marketentry` actor (ADR-2607105300) and swapping jurisdiction
facts / demo data / flagship HARD checks.

| Code | Flagship HARD | Portal facts |
|---|---|---|
| USA | `sam-uei-unverified` | SAM.gov / FAR / EIN |
| CHN | `domestic-entity-missing` | CCGP (ccgp.gov.cn) / USCC / domestic-entity |

Both keep `:filing/submit` never-auto and 24 tests / 79 assertions green.

## Consequences

- Family `:implemented` = 3 (JPN, USA, CHN)
- Ladder validated across three major jurisdictions
