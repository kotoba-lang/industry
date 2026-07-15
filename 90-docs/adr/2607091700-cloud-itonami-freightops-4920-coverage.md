# ADR-2607091700: cloud-itonami-isic-4920 (community freight transport) deepened to `:implemented`

## Status

Accepted

## Related

- ADR-2607071849 (`cloud-itonami-isic-9521`, repairshop — origin of
  the general governed-actor architecture pattern)
- ADR-2607091400 (`cloud-itonami-isic-9511`, ictrepair — first build
  in the gftdcojp-origin-registry scope extension)
- ADR-2607091600 (`cloud-itonami-isic-4711`, retailops — first
  vertical wrapping a real bespoke capability library)
- the full `:adr/related` chain in the companion `.edn` file

## Context

`cloud-itonami-isic-4920` ("Community Freight Transport") is the
third build in the gftdcojp-origin-registry scope extension. Like
`retailops`/4711, its own `docs/business-model.md` had ALREADY
published a fully detailed `:freight-governor` Decision Rule before
this actor's code existed, and its README names a real, pre-existing
bespoke domain capability library,
[`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics)
(shipment/tracking/route/consignment pure-data contracts).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:freight-governor`, is grep-verified UNIQUE fleet-wide — no naming-
collision precedent question, a fresh independent build.

## Decision

Build `freightops` (FreightOps-LLM ⊣ Freight Governor) implementing
the ALREADY-PUBLISHED Decision Rule faithfully, wrapping `kotoba-lang/
logistics` rather than reimplementing its logic — the SECOND vertical
in this fleet to do so, after `retailops`/4711's own integration.
`freightops.registry/tracking-valid?` delegates directly to `kotoba.
logistics/tracking-valid?`.

Unlike `retailops`/4711's own `order` entity (distinguished by
`:kind`, alternative sale-or-reorder actions), this vertical's
`dispatch` and `settle` actuation events apply SEQUENTIALLY to the
SAME `shipment` entity — dispatch first, settlement later — matching
the repair-shop cluster's own sequential `ticket` shape more closely.
`high-stakes` is `#{:actuation/dispatch-shipment :actuation/
settle-consignment}`.

Three genuinely new checks, all evaluated UNCONDITIONALLY:

1. **`tracking-number-invalid-violations`** — delegates to `kotoba.
   logistics/tracking-valid?`, the SAME genuinely new sub-category
   `retailops.governor/ean13-invalid-violations` establishes (reusing
   a CAPABILITY LIBRARY's own validated function rather than a
   sibling actor's check). The 73rd distinct application overall.
2. **`pod-chain-integrity-violations`** — grep-verified absent
   fleet-wide. The 74th distinct application. Grounded in the US
   Carmack Amendment (49 U.S.C. §14706) and the CMR Convention
   (implemented in the UK via the Carriage of Goods by Road Act 1965
   and in Germany via HGB §407 ff.), which condition carrier liability
   on an intact proof-of-delivery chain across route legs.
3. **`cargo-liability-disclosure-violations`** — the FLAGSHIP
   genuinely new check, grep-verified absent fleet-wide. The 75th
   distinct application. Grounded in the same Carmack/CMR/HGB regime
   PLUS Japan's own 商法 (Commercial Code) 運送営業 provisions. ALL FOUR
   seeded jurisdictions actually have a real regime here, reported
   honestly (matching `leathergoods`/9523's own, `ictrepair`/9511's
   own and `retailops`/4711's own full-coverage sub-citations).

A fourth check, `delivery-exception-unresolved-violations`, is
grounded directly in this blueprint's own README text and evaluated
unconditionally across both actuation ops.

This R0 build deliberately scopes DOWN from the full Decision Rule
already published: multi-modal route optimization is left as a
follow-up slice.

## Self-caught field-sync error and correction

Unlike `retailops`/4711's own clean single-field fix, this build's
`blueprint.edn` field-sync review required a self-correction. `:robotics`
was genuinely missing from `:required-technologies`. However, an
initial analysis ALSO concluded `:optimization` needed to move to
`:optional-technologies`, based on a misread of the registry entry
from a truncated `grep` earlier in the session. This was caught before
the industry-side promotion, verified directly against the registry's
own full entry (which genuinely includes `:optimization` in
`:required-technologies`, matching the blueprint's original design),
and corrected in a same-day follow-up commit
(`fix-4920-optimization-required`) that reverted the incorrect move
and fixed the ADR/business-model.md text that had claimed it. This is
documented explicitly rather than silently corrected, consistent with
this fleet's own honesty discipline.

## Consequences

- 88th actor in this fleet (87 implemented before this build).
- SECOND vertical in this fleet to integrate a real, pre-existing
  bespoke domain capability library, confirming the pattern
  `retailops`/4711 established generalizes.
- Establishes three genuinely NEW unconditional-evaluation-discipline
  checks (73rd, 74th, 75th).
- 37 tests / 180 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks one clean dispatch + settlement
  lifecycle, plus five HARD-hold scenarios, end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 135
  assertions) was re-run clean before committing the promotion.
- No still-blueprint test-example swap was needed this time — `"9101"`
  remains a valid, currently-referenced example.
- `manifest/west.yml`'s `industry` pin was advanced via the GitHub API
  single-entry-commit path and verified canonical via
  `nbb scripts/gen-west-manifest.cljs --entry industry`.

## Scope note

`:fleet-maturity-before`/`:fleet-maturity-after` in the companion
`.edn` match `docs/cloud-itonami.md`'s own "Total entries: 643" figure
exactly — `{:implemented 88 :blueprint 10 :spec 545 :total 643}` after
this build, verified directly via `(industry/maturity-summary)`.

9 genuine `:blueprint`-tier candidates remain in the gftdcojp-origin
scope (0162, 0810, 5510, 7110, 7810, 8411, 9101, 9700, 9900), plus one
partially-implemented entry (4211). Both `retailops`/4711 and
`freightops`/4920 have now established the "check for an existing
`kotoba-lang` capability library before designing self-contained
domain logic" pattern — worth checking on each remaining candidate
before starting.

## Alternatives considered

- **Reimplementing tracking-number validation in-repo.** Rejected:
  `kotoba-lang/logistics` already provides an independently-tested,
  pure-data function for exactly this.
- **A `:kind`-distinguished entity** (matching `retailops`/4711's own
  `order` shape). Rejected: dispatch and settlement happen
  SEQUENTIALLY on the SAME shipment in this domain, not as
  alternative actions.
- **Building the FULL Decision Rule in one commit** (including
  multi-modal route optimization). Rejected in favor of a scoped R0
  slice.
- **Silently correcting the `:optimization` field-sync error without
  documenting it.** Rejected: this fleet's own honesty discipline
  (never fabricate, never quietly paper over) applies to self-caught
  build errors too, not just regulatory citations.

## References

- `cloud-itonami-isic-4920/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-4711/docs/adr/0001-architecture.md` (origin of
  the "wrap a real capability library" pattern this build follows)
- `kotoba-lang/logistics` (the capability library this build wraps)
- Carmack Amendment, 49 U.S.C. §14706 (US)
- Carriage of Goods by Road Act 1965 (CMR Convention) (UK)
- Handelsgesetzbuch (HGB) §407 ff. (CMR Convention) (Germany)
- 商法 (Commercial Code) 運送営業規定 (Japan)
