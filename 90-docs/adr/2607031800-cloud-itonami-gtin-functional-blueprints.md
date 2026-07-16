# ADR-2607031800: cloud-itonami-gtin — GTIN functional blueprints (identifier-service counterpart, not a classification family)

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`cloud-itonami-{ISIC}`, `cloud-itonami-isco-{code}`, and
`cloud-itonami-cofog-{code}` (ADR-2607031600) are all keyed by a numeric
CLASSIFICATION code with a hierarchy (industry / occupation / government
function). `cloud-itonami-unspsc-{segment}` (ADR-2607031700) is likewise
keyed by a commodity/service classification segment.

**GTIN (Global Trade Item Number, GS1's barcode-family identifier:
GTIN-8/12/13/14, i.e. UPC/EAN/JAN) is not a classification taxonomy.** It
is a per-product IDENTIFIER system with no category hierarchy to split
blueprints by. This workspace already has a canonical GTIN actor,
`com-etzhayyim-gtin` (etzhayyim/root) — "全世界の商品 identity を GTIN
family で正規化する canonical product actor" — and its own CLAUDE.md
confirms the scope has **no category tree**: 4 flat collections (product /
alias / brand / category-as-metadata-only), keyed by resolved product
identity, not by a classification code an operator business could be keyed
to.

Naively forcing a `cloud-itonami-gtin-{code}` pattern (one blueprint per
GTIN code) would be incoherent — a GTIN identifies ONE product, not a
business niche a sole operator could specialize in. The right split for a
GTIN-adjacent open-business family is by **function performed on GTIN
data**, not by code.

## Decision

### 1. `cloud-itonami-gtin-{function}` — 3 functional blueprints, not code-keyed

- **`cloud-itonami-gtin-issuance`** — Independent GS1 Prefix & GTIN
  Allocation Service. Helps small brand owners navigate GS1 company-prefix
  registration and allocate GTINs correctly (a real, recurring pain point
  for small manufacturers).
- **`cloud-itonami-gtin-verification`** — Independent Barcode Verification
  & Counterfeit-Detection Service. Validates GTIN-family check digits,
  flags duplicate/suspicious/malformed codes for retailers and
  marketplaces.
- **`cloud-itonami-gtin-catalog`** — Independent Product Master Data &
  Canonicalization Service. The OSS-operator counterpart to
  `com-etzhayyim-gtin`'s own function (canonical product identity, brand/
  alias resolution) — something a retailer or distributor can self-host
  instead of paying a closed PIM (Product Information Management) SaaS.

Each repo is self-contained: `blueprint.edn` declares its own
`:required-technologies` directly (no `kotoba-lang/gtin` registry — there
is no code space to register against). Structurally otherwise identical
to the other `cloud-itonami-*` families (README + blueprint.edn +
docs/business-model.md + docs/operator-guide.md + AGPL-3.0-or-later +
CONTRIBUTING/SECURITY/GOVERNANCE/CODE_OF_CONDUCT).

### 2. No robotics premise — digital/data service exemption

GTIN issuance, verification and cataloging are pure data/software
services with no physical domain work (check-digit computation, database
lookups, brand/alias matching) — the same exemption class as
`cloud-itonami-6310` (HR SaaS replacement), which likewise ships with no
`:robotics` requirement despite ADR-2607011000's general robotics-premise
mandate. `blueprint.edn` sets `:itonami.blueprint/robotics false` and
`:required-technologies` lists only real capabilities (`:identity`,
`:forms`, `:audit-ledger`, no `:robotics`).

## Consequences

- (+) GTIN gets an open-business blueprint presence without forcing an
  incoherent "one blueprint per barcode" or "one blueprint per fake
  category" pattern onto an identifier system that has neither.
  Naming disambiguation is not a concern here (unlike `isco-`/`cofog-`/
  `unspsc-`, which disambiguate against numeric-code collisions): GTIN
  function names are self-describing English words, not codes.
- (+) `cloud-itonami-gtin-catalog` gives `com-etzhayyim-gtin`'s canonical
  product-identity function an explicit "you can also self-host an OSS
  version" counterpart, the same relationship `cloud-itonami-6310` has to
  a closed HR SaaS.
- (−) Because there is no registry lib, there is no `:spec` stub coverage
  to extend later the way ISIC/ISCO/COFOG/UNSPSC have — all 3 GTIN
  blueprints ship at `:blueprint` maturity immediately; there is no
  larger "long tail" to promote from, by design (there are only 3
  coherent functions, not dozens of codes).
- (−) No robotics premise means these 3 repos are a further, explicit data
  point (alongside `cloud-itonami-6310`) that the robotics-premise
  mandate (ADR-2607011000) is understood to have a standing exemption for
  pure digital/data-service verticals — this ADR does not amend
  ADR-2607011000, it documents an instance of the same exemption class.
- superproject registration: none — no new `kotoba-lang/*` lib. The 3
  `cloud-itonami-gtin-*` blueprint repos remain standalone, following the
  existing `cloud-itonami-*` convention.

## Artifacts

- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-gtin-{issuance,verification,catalog}` (3 repos)

## References

- ADR-2607011000 (cloud-itonami robotics premise + ISIC 21/21) — the
  general mandate; this ADR documents the digital-service exemption class
  it already has one instance of (`cloud-itonami-6310`).
- ADR-2607031600 / ADR-2607031700 — the immediate COFOG/UNSPSC precedents,
  both code-keyed families this ADR deliberately departs from.
- `com-etzhayyim-gtin` (etzhayyim/root) — the canonical global
  product-identity actor `cloud-itonami-gtin-catalog` offers an
  self-hostable OSS counterpart to.
