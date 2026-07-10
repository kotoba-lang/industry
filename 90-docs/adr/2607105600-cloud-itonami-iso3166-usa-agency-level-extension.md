# ADR-2607105600: cloud-itonami-iso3166-usa — agency-level extension (15 curated federal bodies)

**Status**: accepted
**Date**: 2026-07-10

## Context

ADR-2607040100 validated Japan agency depth (19/19). Future work asked for
USA depth, deliberately scoped — the full U.S. federal org chart is far
larger than Japan's 19-body mapping.

## Decision

Add a **deliberate 15-body** USA agency level under parent `USA`:

| Type | Bodies |
|---|---|
| agency | GSA, SBA |
| ministry (cabinet dept) | TREASURY, DOC, DOD, DHS, DOL, HHS, DOT, DOE, VA |
| independent-commission | EPA, FTC, SEC, FCC |

Each gets `cloud-itonami-iso3166-usa-{slug}` blueprint at `:blueprint`,
with `:ooyake-id` `gov.usa.*` cross-reference (naming parallel to Japan).

`kotoba.iso3166/children "USA"` returns 15. Other countries remain flat.

## Consequences

- Total registry entries 212 → 227
- USA is the second country with agency-level depth (after JPN)
- Not full federal coverage — intentional; expand in later batches
