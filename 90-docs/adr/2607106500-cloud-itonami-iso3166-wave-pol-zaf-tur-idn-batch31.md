# ADR-2607106500: iso3166 wave — POL/ZAF/TUR/IDN :implemented, batch 31

**Status**: accepted
**Date**: 2026-07-10

## Decision

1. **:implemented** — POL (eu-establishment/nip), ZAF (za-presence/cipc),
   TUR (tr-entity/vkn), IDN (id-entity/nib); 24 tests / 79 assertions each.
2. **Batch 31** — BDI, MOZ, MWI, TZA, UGA, ZWE → :blueprint
   (East/Southern Africa). Public repos under `cloud-itonami/cloud-itonami-iso3166-*`
   with `blueprint.edn` + `organization.edn` (HQ/address/contact, institutional
   `:head-role` only).
3. **Contacts** — 150 entries in `kotoba-lang/iso3166` `contacts.edn`
   (added batch-31 HQ; enriched ZAF/TUR/IDN `:head-role`).

## Registry

- total 227 · implemented 20 · blueprint 130 · spec 77
- pin: `1740e5affa008df125aa1f56bdabf4fd89d68ccf` (iso3166)

## Honesty

- Portals/legal bases are real public-sector systems (e-Zamówienia, CSD/eTender,
  EKAP, SPSE; ARMP/UFSA/PPDA/TANePS/PRAZ).
- `:head-role` stores institutional office titles only — no personal names.
