# ADR-2607031600: cloud-itonami-cofog — COFOG government-function blueprints (public-function counterpart to ISIC/ISCO)

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`cloud-itonami-{ISIC}` publishes business blueprints keyed by what a
business produces; `cloud-itonami-isco-{code}` publishes sole-proprietor
occupation blueprints keyed by what a worker does. No counterpart existed
for **what government function a service serves** — COFOG (UN
Classification of the Functions of Government, 10 divisions / 69 groups).

Two existing, DIFFERENT uses of COFOG already exist in this workspace and
must not be conflated:

- `matsurigoto` (etzhayyim/root, `20-actors/matsurigoto/data/cofog-standard.kotoba.edn`)
  uses the COFOG backbone as the SPINE of a SOVEREIGN e-government
  statecraft standard — either etzhayyim's own covenant self-governance, or
  an adopting nation-state's own execution (two principals,
  `:etzhayyim-sovereign` / `:nation-state-adopter`). It is the government
  itself, constitutionally gated (G1 no-operator-master-key / G2
  spec-derived-only / G3 authority-bearing).
- ADR-2606301900 (etzhayyim/root) proposed a parallel `com-etzhayyim-cofog`
  ORGANISM-ACTOR coordinator (one dispatchable organism per COFOG code,
  intents like `:lookup`/`:budget-route`/`:coverage`), used by `ooyake` and
  `danjo` for public-function routing and non-adjudicating budget/source
  coverage reporting. As of this ADR, `com-etzhayyim-cofog` remains
  **proposed, not yet implemented**.

Neither of the above is a commercial operator blueprint. There was no
COFOG analog of `cloud-itonami-{ISIC}` / `cloud-itonami-isco-{code}`: an
INDEPENDENT OPERATOR business that a government CONTRACTS to serve one
public function (e.g. a private fire-inspection contractor, a licensed
waste hauler) — structurally a THIRD, separate principal from the two
above.

## Decision

### 1. `cloud-itonami-cofog-{code}` — commercial-operator government-function blueprints

A new blueprint family, structurally identical to `cloud-itonami-{ISIC}` /
`cloud-itonami-isco-{code}` (README + blueprint.edn + docs/business-model.md
+ docs/operator-guide.md + AGPL-3.0-or-later + CONTRIBUTING/SECURITY/
GOVERNANCE/CODE_OF_CONDUCT), but each repo designs a business for an
**independent operator contracted by (or licensed to serve) a government
to perform ONE public function** — not the government itself, not a
sovereign statecraft standard. Robotics premise carries over unchanged
(ADR-2607011000): a robot performs the physical domain work, an actor
proposes actions, an independent function-specific Governor gates them;
`:high`/`:safety-critical` actions require human sign-off.

**Naming requires the `cofog-` infix**, matching the `isco-` precedent:
COFOG group codes (e.g. `04.5`) and ISIC/ISCO codes occupy different
numeric spaces (dotted division.group vs bare 4-digit), but the infix
still makes the classification family unambiguous at a glance and keeps
the three families visually parallel.

Divisions (`01`..`10`) are parent/queryable nodes only, not independently
blueprint-eligible — mirroring how `kotoba-occupation` treats ISCO-08
major groups. Groups (`01.1`, `04.5`, ...) are the blueprint-eligible
level, since they are specific enough to define one operator business
(matching ISIC "class" / ISCO "unit group" specificity).

### 2. `kotoba-lang/cofog` — COFOG registry, full 79/79 division+group coverage

Mirrors `kotoba-lang/occupation`'s current state (full ISCO-08 unit-group
coverage, mostly `:spec` stubs): the registry lists all 79 COFOG entries
(10 divisions + 69 groups), with English + Japanese labels reused
**verbatim** from `matsurigoto`'s authoritative COFOG backbone (itself
sourced from UN Statistics Division / OECD-Eurostat COFOG / IMF GFSM 2014)
rather than re-derived — the same reuse discipline `formation.registry`
(cloud-itonami-M6910) applied to `matsurigoto`'s LEI/MOD-97-10 arithmetic.
Same contract shape as `kotoba.occupation` (`get-cofog`,
`required-technologies`, `readiness`, `execution-plan`, `maturity`,
`maturity-summary`, `maturity-roadmap`), same `:robotics`-required rule on
every dispatchable (group-level) entry.

5 curated groups, spanning 5 of the 10 divisions (public order & safety /
economic affairs / environmental protection / housing & community
amenities / health), are `:maturity :blueprint` and back a published
`cloud-itonami-cofog-{code}` repo:

| COFOG | Function | Blueprint |
|---:|---|---|
| 03.2 | Fire-protection services | Independent Fire-Risk Inspection Robotics |
| 04.5 | Transport | Independent Road & Bridge Inspection Robotics |
| 05.1 | Waste management | Independent Municipal Waste Collection Robotics |
| 06.3 | Water supply | Independent Water Infrastructure Leak-Detection Robotics |
| 07.4 | Public health services | Independent Community Vector-Control & Environmental Health Monitoring |

The remaining 74 entries (10 divisions + 64 groups) are `:spec`
registry-only stubs, available for future promotion via the same
`maturity-roadmap` path `kotoba-lang/industry` / `kotoba-lang/occupation`
use (`:spec` → publish blueprint repo → `:blueprint` → implement actor →
`:implemented`).

## Consequences

- (+) Government-function classification now has a first-class blueprint
  pattern symmetric with industry (`cloud-itonami-{ISIC}`) and occupation
  (`cloud-itonami-isco-{code}`): what the business produces / what the
  worker does / what public function is served, three structurally
  parallel families resolving through sibling registries (`kotoba-lang/
  industry` / `occupation` / `cofog`) with the same maturity-tier
  vocabulary.
- (+) Full 79/79 COFOG registry coverage, machine-readable and
  `readiness`/`execution-plan`/`maturity-roadmap` verifiable (6 tests, 173
  assertions, all green).
- (+) The `cofog-` naming infix and the explicit "commercial operator, not
  the government" framing in each repo's README prevent confusion with
  `matsurigoto` (sovereign statecraft) or the proposed `com-etzhayyim-cofog`
  (non-adjudicating organism coordinator) — three distinct principals using
  the same authoritative COFOG labels without drifting into inconsistent
  readings.
- (−) Divisions have no blueprint repo by design (parent/queryable nodes
  only), mirroring ISCO major-group treatment.
- (−) This ADR does not implement `com-etzhayyim-cofog` (ADR-2606301900's
  proposed organism coordinator) — that remains a separate, not-yet-built
  piece of work; this ADR only adds the commercial-operator blueprint
  layer.
- superproject registration: `orgs/kotoba-lang/cofog` added to
  `manifest/repos.edn` / `manifest/west.yml`. The 5 `cloud-itonami-cofog-*`
  blueprint repos remain standalone, following the existing
  `cloud-itonami-*` convention (blueprint repos are not west-managed).

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/cofog`
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-cofog-{03.2,04.5,05.1,06.3,07.4}` (5 repos)

## References

- ADR-2607011000 (cloud-itonami robotics premise + ISIC 21/21) — the
  pattern this ADR mirrors.
- ADR-2607012000 (cloud-itonami-isco occupation blueprints) — the
  immediate precedent this ADR is structurally parallel to.
- ADR-2607012100 (cloud-itonami org split — public blueprint repos live in
  the dedicated `cloud-itonami` org).
- ADR-2606301900 (etzhayyim/root, ISCO/COFOG organism actors at UNSPSC
  granularity) — the proposed, not-yet-built `com-etzhayyim-cofog`
  organism coordinator this ADR is explicitly NOT implementing.
- `20-actors/matsurigoto/data/cofog-standard.kotoba.edn` (etzhayyim/root) —
  authoritative COFOG source data, reused verbatim for labels.
