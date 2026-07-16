# ADR-2607091600: cloud-itonami-isic-4711 (community retail operations) deepened to `:implemented`

## Status

Accepted

## Related

- ADR-2607071849 (`cloud-itonami-isic-9521`, repairshop — origin of
  the general governed-actor architecture pattern)
- ADR-2607087400 (`cloud-itonami-isic-9523`, leathergoods — completion
  of the `cloud-itonami/cloud-itonami-isic-*` blueprint fleet)
- ADR-2607091400 (`cloud-itonami-isic-9511`, ictrepair — first build
  in the gftdcojp-origin-registry scope extension)
- the full `:adr/related` chain in the companion `.edn` file

## Context

`cloud-itonami-isic-4711` ("Community Retail Operations") is the
second build in the gftdcojp-origin-registry scope extension approved
after ADR-2607087400. Unlike every prior sibling, its own
`docs/business-model.md` had ALREADY published a fully detailed
`:retail-governor` Decision Rule — approve/reject conditions, a
required-technologies table explaining what each technology is
load-bearing for, and a tie to a companion playable prototype
(`network-isekai`'s "ITONAMI: Retail Shift", ADR-2607031000) — before
this actor's code existed. Its README also names a real, pre-existing
bespoke domain capability library,
[`kotoba-lang/retail`](https://github.com/kotoba-lang/retail)
(SKU/EAN-13/POS/inventory pure-data contracts, already tested and
independent of this build).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:retail-governor`, is grep-verified UNIQUE fleet-wide — no naming-
collision precedent question, a fresh independent build.

## Decision

Build `retailops` (RetailOps-LLM ⊣ Retail Governor) implementing the
ALREADY-PUBLISHED Decision Rule faithfully, wrapping `kotoba-lang/
retail` rather than reimplementing its logic — the FIRST vertical in
this fleet to do so. `retailops.registry/ean13-valid?` and
`retailops.registry/needs-reorder?` delegate directly to `kotoba.
retail/ean13-valid?` and `kotoba.retail/needs-reorder?`; `retailops.
registry/compute-sale-total` uses `kotoba.retail/line-item`'s own net
calculation.

The primary entity is an `order`, distinguished by its own `:kind`
(`:sale` | `:reorder`) — still the SAME dual-actuation shape every
prior sibling uses (two real-world acts, each with its own history/
sequence/double-actuation-guard boolean), just a domain-honest entity
name matching `kotoba.retail`'s own vocabulary (SKU, line-item,
receipt) rather than the repair-shop cluster's `ticket`. `high-stakes`
is `#{:actuation/post-sale :actuation/commit-reorder}`.

Two genuinely new checks:

1. **`ean13-invalid-violations`** — delegates to `kotoba.retail/
   ean13-valid?` rather than reimplementing the GS1 mod-10 checksum, a
   genuinely NEW SUB-CATEGORY in the unconditional-evaluation
   discipline (reusing a CAPABILITY LIBRARY's own validated function
   rather than a sibling actor's check). The 71st distinct application
   overall (most recently `ictrepair.governor/media-sanitization-
   unconfirmed-violations` at 70th). Evaluated UNCONDITIONALLY.
2. **`price-band-violation-violations`** — the FLAGSHIP genuinely new
   check, grep-verified absent fleet-wide (zero hits for `price-
   band`/`unit-pricing`/`price-marking`). The 72nd distinct
   application overall. Grounded in real unit-pricing/price-marking
   law: the US NIST Handbook 130 (Uniform Regulation for the Method of
   Sale of Commodities, adopted by most states), the UK's Price
   Marking Order 2004, Germany's Preisangabenverordnung (PAngV,
   implementing EU Directive 98/6/EC), and Japan's own 計量法
   (Measurement Act) unit-price provisions. ALL FOUR seeded
   jurisdictions actually have a real regime here, reported honestly
   (matching `leathergoods`/9523's own and `ictrepair`/9511's own
   full-coverage sub-citations). Evaluated UNCONDITIONALLY.

Two checks apply the SAME ground-truth-recompute discipline as every
sibling's own parts-cost check, reapplied to new domain facts, not
claimed as new code (no literal code is shared — different domain,
different capability library):

- `sale-total-mismatch-violations` — order's own claimed total vs.
  quantity x unit-price.
- `reorder-threshold-mismatch-violations` — order's own recorded
  stock vs. its own reorder threshold, via `kotoba.retail/
  needs-reorder?`.

Unlike every prior check in this discipline's exercise pattern, no
dedicated `:X/screen` op was introduced for the new checks — they are
evaluated directly at `:sale/post` time, matching how a real POS
system validates a barcode and a price at the point of sale itself,
not as a discrete pre-screening ceremony a shop clerk performs
separately. Each check is still exercised directly via its own
dedicated demo order and test, never only via a happy-path actuation.

This R0 build deliberately scopes DOWN from the full Decision Rule
already published: reconciliation, void-without-reason blocking, and
cash-discrepancy escalation (all named in that rule) are left as a
follow-up slice, consistent with every prior actor's own "extending
coverage is additive" convention.

## `blueprint.edn` field-sync fix

Unlike the last several builds, this repo's `blueprint.edn` DID need a
fix: `:required-technologies` was missing `:robotics` (present in the
`kotoba-lang/industry` registry's own entry for `"4711"` but absent
from the blueprint's own list) — fixed as part of this promotion.

## Consequences

- 87th actor in this fleet (86 implemented before this build).
- FIRST vertical in this fleet to integrate a real, pre-existing
  bespoke domain capability library rather than self-contained logic
  — worth checking for on future builds before writing domain logic
  from scratch.
- Establishes two genuinely NEW unconditional-evaluation-discipline
  checks (71st, 72nd).
- 44 tests / 186 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks one clean sale lifecycle, one clean
  reorder lifecycle, and six HARD-hold scenarios end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 134
  assertions) was re-run clean before committing the promotion.
- No still-blueprint test-example swap was needed this time — `"9101"`
  (set during `ictrepair`/9511's own promotion) remains a valid,
  currently-referenced example.
- `manifest/west.yml`'s `industry` pin was advanced via the GitHub API
  single-entry-commit path and verified canonical via
  `nbb scripts/gen-west-manifest.cljs --entry industry`.

## Scope note

`:fleet-maturity-before`/`:fleet-maturity-after` in the companion
`.edn` match `docs/cloud-itonami.md`'s own "Total entries: 643" figure
exactly (as reconciled in ADR-2607091400) — `{:implemented 87
:blueprint 11 :spec 545 :total 643}` after this build, verified
directly via `(industry/maturity-summary)`.

10 genuine `:blueprint`-tier candidates remain in the gftdcojp-origin
scope (0162, 0810, 4920, 5510, 7110, 7810, 8411, 9101, 9700, 9900),
plus one partially-implemented entry (4211) noted for separate
follow-up. Several of these (e.g. 4920, Community Freight Transport)
also already have unusually detailed pre-published Decision Rule text
in their own `docs/business-model.md` — worth checking before
designing a fresh governor from a generic template, following the
precedent this build establishes.

## Alternatives considered

- **Reimplementing EAN-13 checksum and reorder-threshold logic
  in-repo.** Rejected: `kotoba-lang/retail` already provides
  independently-tested, pure-data functions for exactly this.
- **Building the FULL Decision Rule in one commit.** Rejected in
  favor of a scoped R0 slice, consistent with this fleet's own
  "extending coverage is additive" convention.
- **A `ticket`-style entity name** (matching the repair-shop cluster).
  Rejected: `order` (distinguished by `:kind`) is the domain-honest
  name, matching `kotoba.retail`'s own vocabulary.

## References

- `cloud-itonami-isic-4711/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `kotoba-lang/retail` (the capability library this build wraps)
- NIST Handbook 130, Uniform Regulation for the Method of Sale of
  Commodities (US)
- Price Marking Order 2004 (UK)
- Preisangabenverordnung (PAngV) (Germany)
- 計量法 (Measurement Act) (Japan)
