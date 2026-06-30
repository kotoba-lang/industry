# ADR-2606302300: Four-org taxonomy (kotoba-lang / etzhayyim / gftdcojp / com-junkawasaki)

**Status**: proposed
**Date**: 2026-06-30
**Deciders**: Jun Kawasaki

## Context

The superproject partitions work across four GitHub orgs checked out under
`orgs/<org>/`. The split has been **implicit** until now — referenced in passing
by many library ADRs ("最初の消費者は別 org(**公益=etzhayyim / 事業=gftdcojp**)
の actor が …") and encoded structurally in `manifest/repos.edn`
(`:remotes`, `:path-overrides`, `:extra-projects`) — but never recorded as a
single authoritative taxonomy. This ADR writes it down so new repos land in the
right org by design, not by accident.

## Decision

Four orgs, partitioned along two axes — **what kind of thing** (language /
agent / human-facing product / platform) and, for actors/products only,
**for whom** (公益 public-interest / ビジネス business):

| org | role (what) | scope (whom) | west remote | ~projects |
|---|---|---|---|---|
| `kotoba-lang` | **kotoba 言語** — pure-CLJC language/core libraries & tech (`kotoba`, `kotoba-code`, `kasane`, `kami-*`, `murakumo`, `office-*`, `svgraph`, …) + (migrating) all generic `-clj` infra | language substrate, consumed by all orgs | `git@github.com:kotoba-lang` | 85→growing |
| `etzhayyim` | **agent-centric** — dispatchable organism actors (`com-etzhayyim-*`, one per UNSPSC / ISCO / COFOG code) | **公益 (public-interest)** | `github.com/etzhayyim` | 170 |
| `gftdcojp` | **human-centric** — human-facing products & business services (`ai-gftd-*`, apps, HR `talent`) | **ビジネス (business)** | `git@github.com:gftdcojp` | 50 |
| `com-junkawasaki` | **transitional foundation** — generic `-clj` infra **migrating fully to `kotoba-lang`** (decided); retains cloud control planes + heavy/data projects | Jun Kawasaki platform/foundation (origin org) | `git@github.com:com-junkawasaki` | 53→shrinking |

### Axes

1. **What**: `kotoba-lang` is the *language/substrate* (and, after migration,
   the home of all `-clj` infrastructure libraries); `etzhayyim` and `gftdcojp`
   are *agents/products* built on it; `com-junkawasaki` is being *emptied of
   libraries* into `kotoba-lang`.
2. **Whom (actors/products only)**: `etzhayyim` = 公益 (common-good services
   delivered as agents); `gftdcojp` = ビジネス (commercial). The 公益/事業
   distinction was previously only asserted inline by the library ADRs
   (ci-clj, ddl-clj, policy-clj, bpmn-clj); this ADR is now its authority.

### Migration (com-junkawasaki → kotoba-lang) — DECIDED

Owner decision (2026-06-30): **the generic infrastructure `-clj` libraries
fully migrate to `kotoba-lang`**, using the same `repos.edn :path-overrides`
mechanism as the language-core.

- **Already migrated** (language-core): `kotoba`, `kotoba-code`, `kasane`,
  `kami-*`, `murakumo`, `office-*`, `svgraph`, `aiueos`, `kekkai`, `utsushi`,
  `vehicle-design-actor` → `kami-engine-vehicle-designer`, …
- **To migrate** (generic `-clj` infra): `aero-clj`, `bpmn-clj`, `ci-clj`,
  `ddl-clj`, `dmn-clj`, `crash-clj`, `cron-clj`, `datom-clj`, `graphql-clj`,
  `ical-clj`, `jsonlogic-clj`, `kagi-clj`, `kenchi-clj`, `kotobase-clj`, …
  (full inventory to be enumerated in the migration PR).
- **`com-junkawasaki` end-state**: **cloud control planes** (`cloud-manimani`,
  `cloud-murakumo`) + **heavy/data projects** (`spirit-in-physics`,
  `ghosthacker`) only. All libraries will have left for `kotoba-lang`.

Mechanism: add `<x>-clj` → `<x>` entries to `:path-overrides`; regenerate
(`bb scripts/gen-west-manifest.bb`) + `west update` to relocate checkouts;
verify `--check` canonical and `pin == repo HEAD`. GitHub org relocation per
repo is a separate decision (some repos may stay at `github.com/com-junkawasaki`
and be checked out under `kotoba-lang/` via `:remote-overrides`, as
`kami-engine-sdk` already is).

### Out of scope (not west-managed)

Personal / operational data lives **outside** west and is **not** part of this
taxonomy:

- `orgs/kawasakijun/` — personal ops/state (finances, goals, litigation, KPIs).
- `orgs/personal/` — life-data vault (mail, photos, takeout, accounts).

## Consequences

- **New-repo placement rule**:
  - **any library / substrate** (language or `-clj` infra) → `kotoba-lang`
  - public-interest agent → `etzhayyim/com-etzhayyim-*`
  - commercial product / actor → `gftdcojp/ai-gftd-*` (or `app-*`)
  - cloud control plane / heavy data → `com-junkawasaki` (the only thing that
    stays here once migration completes)

  Enforced through the standing "new project" workflow (CLAUDE.md) and the
  doc-only `:orgs` key in `repos.edn`.
- `:path-overrides` is the migration mechanism; `gen-west-manifest.bb --check`
  keeps `west.yml` canonical.
- This ADR is the single reference for the org taxonomy; the passing 公益/事業
  mentions in library ADRs now resolve here.

## Decisions (resolved 2026-06-30)

- **`com-junkawasaki` end-state**: the generic `-clj` infrastructure libraries
  **fully migrate to `kotoba-lang`** (same `:path-overrides` mechanism as the
  language-core). `com-junkawasaki` will retain only **cloud control planes
  (cloud-manimani, cloud-murakumo) + heavy/data projects (spirit-in-physics,
  ghosthacker)**. Migration in progress; tracked via `repos.edn
  :path-overrides`.
