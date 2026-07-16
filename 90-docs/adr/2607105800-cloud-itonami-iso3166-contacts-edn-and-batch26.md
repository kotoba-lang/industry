# ADR-2607105800: iso3166 organization contacts EDN + country batch 26

**Status**: accepted
**Date**: 2026-07-10

## Decision

1. **Organization contacts EDN** — add
   `kotoba-lang/iso3166/resources/kotoba/iso3166/contacts.edn` and API
   `contacts` / `get-contact`. Each entry carries real HQ address/phone
   (from ooyake or official public contact pages) and an
   **institutional** `:head-role` (大臣 / Secretary / Chair) — never a
   personal name. Agency registry rows also get flat `:hq-*` /
   `:head-role` / `:official-url` fields; blueprint repos ship
   `organization.edn`.

2. **Country batch 26** — promote BGR, CYP, SVN, LUX, MLT, ROU to
   `:blueprint` (EU deepening).

## Honesty

- No invented personal names for 責任者.
- `:sourced-from` / `:hq :sourcing` record provenance.
- ooyake addresses reused verbatim where present.

## Registry after this wave

- total 227 · implemented 3 · blueprint 117 · spec 107
- contacts entries: 40
