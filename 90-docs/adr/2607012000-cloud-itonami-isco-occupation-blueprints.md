# ADR-2607012000: cloud-itonami-isco — ISCO-08 occupation blueprints (occupation counterpart to ISIC)

**Status**: accepted
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context

`cloud-itonami-{ISIC}` publishes forkable OSS **business** blueprints, keyed by
what a business produces (ISIC Rev.5/Rev.4). `kotoba-lang/industry` maps ISIC
code → required `kotoba-lang` technology capabilities; the registry has since
grown to full ISIC Rev.4 class coverage (643 entries: 21/21 sections, 238/238
groups; 1 `:implemented`, 25 `:blueprint`, 617 `:spec` registry-only stubs).

ISCO-08 classifies what a **worker** does (occupation), not what a business
produces (industry). `com-etzhayyim-isco` already holds the authoritative
ILO ISCO-08 standard structure (10 major / 43 sub-major / 130 minor / 436 unit
= 619 nodes) as an EAVT Datom seed, used by etzhayyim actors (e.g.
`com-etzhayyim-omise`, `com-google-ads`) to tag occupation-scoped cohorts and
actor DIDs. No occupation-classification analog of the `cloud-itonami-{ISIC}`
business-blueprint pattern existed.

## Decision

### 1. `cloud-itonami-isco-{ISCO-08 code}` — sole-proprietor occupation blueprints

A new blueprint family, structurally identical to `cloud-itonami-{ISIC}`
(README + blueprint.edn + docs/business-model.md + docs/operator-guide.md +
docs/samples/operator-console.html + AGPL-3.0-or-later + CONTRIBUTING/
SECURITY/GOVERNANCE/CODE_OF_CONDUCT), but each repo designs a business for
**one sole-proprietor occupation** (1 ISCO-08 unit group = 1 independent
operator), not an industry vertical. Robotics premise carries over unchanged
(ADR-2607011000): a robot performs the physical domain work, an actor
proposes actions, an independent occupation-specific Governor gates them;
`:high`/`:safety-critical` actions require human sign-off.

**Naming requires the `isco-` infix**: ISIC and ISCO-08 codes overlap
numerically (e.g. both classifications use 4-digit codes in similar ranges),
so a bare `cloud-itonami-{code}` would be ambiguous between the two
registries. `cloud-itonami-isco-{code}` disambiguates unconditionally.

### 2. `kotoba-lang/occupation` — ISCO-08 registry, full 436/436 unit-group coverage

Mirrors `kotoba-lang/industry`'s current state (full ISIC class coverage,
mostly `:spec` stubs) rather than the original 26-entry ADR-2607011000
snapshot: the registry lists **all 436 ISCO-08 unit groups**, sourced from
`com-etzhayyim-isco`'s authoritative seed (with one upstream data-quality fix:
unit 6113's name was corrupted to a stray escaped-quote + "Gardeners" in that
source; corrected here to the official "Gardeners, Horticultural and Nursery
Growers"). Same contract shape as `kotoba.industry` (`get-occupation`,
`required-technologies`, `readiness`, `execution-plan`, `maturity`,
`maturity-summary`, `maturity-roadmap`), same `:robotics`-required-on-every-
entry rule.

9 curated entries — one representative unit group per non-armed-forces major
group (10/10 major groups have policy coverage; major group 0 "Armed Forces
Occupations" is intentionally registry-only, since a sole-proprietor civilian
OSS business blueprint doesn't fit an armed-forces occupation — the same kind
of principled exception `kotoba-lang/industry` already makes for some ISIC
sections) — are `:maturity :blueprint` and back a published
`cloud-itonami-isco-{code}` repo:

| ISCO-08 | Occupation | Blueprint |
|---:|---|---|
| 1321 | Manufacturing Managers | Independent Manufacturing Floor Management |
| 2221 | Nursing Professionals | Independent Home Nursing Practice |
| 3253 | Community Health Workers | Community Health Outreach Practice |
| 4321 | Stock Clerks | Independent Warehouse Stock Operations |
| 5322 | Home-based Personal Care Workers | Independent Home-Based Care Practice |
| 6112 | Market Gardeners and Crop Growers | Independent Market Gardening Operations |
| 7126 | Plumbers and Pipe Fitters | Independent Plumbing Practice |
| 8332 | Heavy Truck and Lorry Drivers | Independent Freight Driving Operations |
| 9312 | Civil Engineering Labourers | Independent Civil Labour Crew |

The remaining 427 unit groups are `:spec` registry-only stubs (minimal
`:required-technologies [:robotics :identity :audit-ledger]`,
`:operating-states [:intake :propose :approve :execute :audit]`), available
for future promotion via the same `maturity-roadmap` path `kotoba-lang/
industry` uses (`:spec` → publish blueprint repo → `:blueprint` → implement
actor → `:implemented`).

## Consequences

- (+) Occupation classification now has a first-class blueprint pattern
  symmetric with industry classification: `cloud-itonami-{ISIC}` (what the
  business produces) and `cloud-itonami-isco-{code}` (what the worker does)
  are structurally parallel, share the robotics-premise contract, and resolve
  through sibling registries (`kotoba-lang/industry` / `kotoba-lang/
  occupation`) with the same maturity-tier vocabulary.
- (+) Full 436/436 ISCO-08 unit-group registry coverage, machine-readable and
  `readiness`/`execution-plan`/`maturity-roadmap` verifiable (6 tests, 47
  assertions, all green).
- (+) `isco-` naming infix makes the ISIC/ISCO ambiguity structurally
  impossible rather than relying on operators to remember which registry a
  bare numeric code belongs to.
- (−) Major group 0 (Armed Forces) has no blueprint repo by design; if a
  civilian-adjacent veteran-transition business case emerges later, it can be
  added without renumbering anything else.
- (−) **Known pre-existing gap, not introduced by this ADR**: `kotoba-lang/
  industry` and `kotoba-lang/technology` themselves predate west
  registration and remain unregistered in `manifest/repos.edn` /
  `manifest/west.yml`, despite ADR-2607011000 stating they should be. This
  ADR registers `kotoba-lang/occupation` correctly but does not retroactively
  fix `industry`/`technology`'s registration — flagged here as a follow-up,
  not silently carried forward.
- superproject registration: `orgs/kotoba-lang/occupation` added to
  `manifest/repos.edn` / `manifest/west.yml` (`bb scripts/gen-west-manifest.bb
  --check` passes). The 9 `cloud-itonami-isco-*` blueprint repos remain
  standalone, following the existing `cloud-itonami-*` convention (blueprint
  repos are not west-managed).

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/occupation`
- blueprint repo (gftdcojp org, public, AGPL-3.0-or-later):
  `cloud-itonami-isco-{1321,2221,3253,4321,5322,6112,7126,8332,9312}`
  (9 repos)

## References

- ADR-2607011000 (cloud-itonami robotics premise + ISIC 21/21) — the pattern
  this ADR mirrors for occupations.
- `com-etzhayyim-isco` (`kotoba/isco-occupations.kotoba.edn`) — authoritative
  ISCO-08 source data (619 nodes, ILO ISCO-08 standard structure).
- 本 ADR とペアの `.edn`

## Addendum (2026-07-01, ADR-2607012100)

The 9 `cloud-itonami-isco-*` blueprint repos were published under the
`gftdcojp` org (public) as stated above, and have since been transferred to
the dedicated `cloud-itonami` org per ADR-2607012100 (visibility unchanged,
still public). The "gftdcojp org" wording above is kept as the historical
record of the original publish; current ownership is tracked by
ADR-2607012100.
