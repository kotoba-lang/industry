# ADR-2607106600: iso3166 wave — RUS/CHE/BEL/AUT :implemented, batch 32

**Status**: accepted
**Date**: 2026-07-10

## Decision

1. **:implemented** — RUS (ru-entity/inn, EIS), CHE (ch-presence/uid, simap),
   BEL (eu-establishment/cbe), AUT (eu-establishment/firmenbuch); 24 tests each.
2. **Batch 32** — CAF, COD, COG, LBR, LSO, SLE → :blueprint
   (Central/West Africa). Public repos with `blueprint.edn` + `organization.edn`.
3. **Contacts** — 156 entries; `:head-role` for RUS/CHE/BEL/AUT (institutional only).

## Registry

- total 227 · implemented 24 · blueprint 132 · spec 71
- pin: `2e9e2362a53dcb7ea0aea1685cca86478ad69ff1` (iso3166)

## Honesty

- Portals/legal bases are real (zakupki.gov.ru/EIS, simap.ch, Belgian e-Procurement,
  USP/BVergG; ARMP/PPCC/PPAD/NPPA).
- `:head-role` stores institutional office titles only — no personal names.
