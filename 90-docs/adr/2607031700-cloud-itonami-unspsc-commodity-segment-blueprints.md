# ADR-2607031700: cloud-itonami-unspsc — UNSPSC segment-level blueprints (commodity/service counterpart to ISIC/ISCO/COFOG)

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`cloud-itonami-{ISIC}` (what a business produces), `cloud-itonami-isco-{code}`
(what a worker does) and `cloud-itonami-cofog-{code}` (what public function
is served, ADR-2607031600) cover three classification families. No
counterpart existed for **what commodity/service a business specializes
in sourcing, servicing or refurbishing** — UNSPSC (United Nations Standard
Products and Services Code).

UNSPSC already has a LIVE, authoritative presence in this workspace at
COMMODITY granularity: the clj `unspsc` actor (etzhayyim/root,
`20-actors/unspsc`, an 18,342-code data table + `kotodama.unspsc.dispatch`
framework, serving the `unspsc.etzhayyim.com` XRPC gateway). This actor
supersedes an earlier, since-retired 18,343-file-per-code Python
LangGraph-agent fleet (ADR-2605171300, retired by ADR-2606172100) —
important prior art to be aware of before designing anything new on top of
UNSPSC, since the retirement means the per-code-generated-file APPROACH
was explicitly rejected, not just the Python implementation.

UNSPSC's full depth (~150,000 commodity codes) is too fine-grained for the
`cloud-itonami` "1 blueprint = 1 independent operator" pattern — a
blueprint per exact commodity SKU would not compose into a coherent
business the way an ISIC class or a COFOG group does. The right
granularity for a business-blueprint family is the UNSPSC **segment**
(2-digit, ~53-57 codes), the same order of magnitude as ISIC sections /
COFOG divisions.

## Decision

### 1. `cloud-itonami-unspsc-{segment}` — commodity/service-specialist blueprints

A new blueprint family, structurally identical to `cloud-itonami-{ISIC}` /
`cloud-itonami-isco-{code}` / `cloud-itonami-cofog-{code}` (README +
blueprint.edn + docs/business-model.md + docs/operator-guide.md +
AGPL-3.0-or-later + CONTRIBUTING/SECURITY/GOVERNANCE/CODE_OF_CONDUCT), but
each repo designs a business for an **independent operator specializing in
ONE UNSPSC segment's commodities/services** (sourcing, servicing,
refurbishing, or install/diagnostics) — not a full ISIC industry, not an
occupation, not a government function. Robotics premise carries over
unchanged (ADR-2607011000).

**Naming requires the `unspsc-` infix**, matching the `isco-`/`cofog-`
precedent, so all four classification families stay visually
unambiguous.

### 2. `kotoba-lang/unspsc` — UNSPSC SEGMENT-level registry (NOT the commodity-level data)

A new, deliberately coarser registry than `20-actors/unspsc`'s
authoritative commodity table: ~53 segment-level entries, same contract
shape as `kotoba.occupation` / `kotoba.cofog` (`get-segment`,
`required-technologies`, `readiness`, `execution-plan`, `maturity`,
`maturity-summary`, `maturity-roadmap`). This registry does **not** read
from, write to, duplicate, or attempt to supersede the etzhayyim `unspsc`
actor's commodity data — it exists purely to drive the
`cloud-itonami-unspsc-*` blueprint pattern, exactly as `kotoba-lang/cofog`
exists alongside (not instead of) `matsurigoto`'s COFOG data.

**Honesty note, explicitly different from the COFOG ADR**: unlike
`kotoba-lang/cofog`, which mirrors an authoritative source file
(`matsurigoto`'s COFOG data) verbatim, this segment list is drawn from
general UNSPSC taxonomy knowledge, not copied from a verified official
UNSPSC codeset file present in this workspace. The registry's own note
field and README flag this explicitly and point to unspsc.org / GS1 US as
the source to verify against before relying on any segment number/title
for anything beyond a structural starting point.

5 curated segments are `:maturity :blueprint` and back a published
`cloud-itonami-unspsc-{segment}` repo:

| Segment | Name | Blueprint |
|---:|---|---|
| 10 | Live Plant and Animal Material and Accessories and Supplies | Independent Urban Apiary & Pollinator Services |
| 27 | Tools and General Machinery | Independent Tool Fleet Rental & Maintenance Robotics |
| 39 | Electrical Systems and Lighting and Components and Accessories and Supplies | Independent Solar & EV-Charging Install & Diagnostics |
| 43 | Information Technology Broadcasting and Telecommunications | Independent IT Asset Recovery & E-Waste Refurbishment Robotics |
| 73 | Industrial Production and Manufacturing Services | Independent Industrial Cleaning & Certification Robotics |

The remaining 48 entries are `:spec` registry-only stubs, available for
future promotion via the same `maturity-roadmap` path the other
`kotoba-lang/*` registries use.

## Consequences

- (+) Commodity/service classification now has a first-class blueprint
  pattern symmetric with industry / occupation / government-function
  (`cloud-itonami-{ISIC}` / `cloud-itonami-isco-{code}` /
  `cloud-itonami-cofog-{code}`): four structurally parallel families
  resolving through sibling registries with the same maturity-tier
  vocabulary.
- (+) 5 curated segments span distinct, non-overlapping niches from the
  existing ISIC blueprint set (apiary/pollination, tool-fleet rental,
  solar/EV electrical, IT asset disposition, industrial cleaning) rather
  than duplicating an existing `cloud-itonami-{ISIC}` business.
- (+) Explicit non-duplication with `20-actors/unspsc` (etzhayyim/root):
  this registry is coarser-grained and blueprint-purpose-built, never a
  competing source of commodity-level truth. Any blueprint that needs real
  commodity classification at runtime should call the etzhayyim actor's
  XRPC surface.
- (−) The segment list's provenance is weaker than `kotoba-lang/cofog`'s
  (general knowledge vs. a verified authoritative source file in this
  workspace) — flagged honestly rather than presented as authoritative.
  Follow-up: replace with a verified official UNSPSC segment dump when one
  becomes available in the workspace.
- (−) Segment-level granularity is inherently coarser than the ISIC/ISCO/
  COFOG blueprint level (a UNSPSC segment spans a wider variety of
  concrete goods/services than an ISIC class); each blueprint should be
  read as "one specialist niche within this segment," not "the entire
  segment."
- superproject registration: `orgs/kotoba-lang/unspsc` added to
  `manifest/repos.edn` / `manifest/west.yml`. The 5
  `cloud-itonami-unspsc-*` blueprint repos remain standalone, following
  the existing `cloud-itonami-*` convention.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/unspsc`
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-unspsc-{10,27,39,43,73}` (5 repos)

## References

- ADR-2607011000 (cloud-itonami robotics premise + ISIC 21/21)
- ADR-2607012000 (cloud-itonami-isco occupation blueprints)
- ADR-2607031600 (cloud-itonami-cofog government-function blueprints) —
  the immediate precedent this ADR is structurally parallel to
- ADR-2606172100 (etzhayyim/root — retire the UNSPSC per-code Python
  fleet, superseded by the clj `unspsc` actor) — prior art this ADR builds
  alongside, not on top of or in place of
- ADR-2605171300 (etzhayyim/root — the original, now-superseded, UNSPSC
  generative agent fleet)
- `20-actors/unspsc` (etzhayyim/root) — the workspace's authoritative,
  commodity-level UNSPSC data + dispatch surface, explicitly NOT
  duplicated by this ADR
