---
id: adr-2607011100-svg-era-office-repo-split
title: "ADR-2607011100: SVG-era office-causal/svgraph を com-junkawasaki に復元し、kotoba-lang CLJ/EDN 版と west で並走させる"
status: accepted
doc_type: adr
topic: org-taxonomy
authoritative: true
last_verified: 2026-07-01
authoritative_for:
  - office-causal / svgraph の GitHub org と west path の例外扱い
  - kotoba-lang の CLJ/EDN 版と com-junkawasaki の SVG-era 版を別 repo として共存させる方針
  - manifest/repos.edn の path-overrides から office-causal / svgraph を外す理由
related:
  - 90-docs/adr/2606302300-org-taxonomy-4-orgs.md
  - 90-docs/adr/2606271500-west-manifest-over-vcstool.md
  - 90-docs/adr/2606272237-west-manifest-api-single-entry.md
  - manifest/repos.edn
  - manifest/west.yml
supersedes: []
superseded_by: []
---

# ADR-2607011100: SVG-era office-causal/svgraph を com-junkawasaki に復元し、kotoba-lang CLJ/EDN 版と west で並走させる

**Status**: accepted  
**Date**: 2026-07-01  
**Deciders**: Jun Kawasaki

## Context

`office-causal` と `svgraph` は一度 `com-junkawasaki` から `kotoba-lang`
へ移され、CLJ/CLJC/EDN 寄りのライブラリとして再編された。

- `orgs/kotoba-lang/office`: CLJC OOXML reader / EDN graph / `ocz/causal.edn`
- `orgs/kotoba-lang/svgraph`: CLJ/nbb 寄りの `svgraph`
- `orgs/kotoba-lang/svg`: SVG-first SVGraph / DrawingML / PPTX converter

この移管は ADR-2606302300 の「library/substrate は `kotoba-lang`」に沿う。
一方で、旧 `com-junkawasaki/office-causal` と旧 `com-junkawasaki/svgraph`
には、SVG / TypeScript / browser / JSONL / DrawingML 前提の実装と公開面が残っていた。
CLJ/EDN 版と SVG 版を同じ repo 名・同じ west path に押し込むと、利用者・package 名・
GitHub Pages・Office SVG 連携の意味が混ざる。

## Decision

`kotoba-lang` 側はそのまま canonical CLJ/EDN 版として残す。あわせて、
SVG-era の旧版を `com-junkawasaki` に独立 repo として復元する。

| concern | repo | west path | role |
|---|---|---|---|
| CLJC / EDN Office graph | `kotoba-lang/office` | `orgs/kotoba-lang/office` | kotoba language substrate |
| CLJ/nbb svgraph | `kotoba-lang/svgraph` | `orgs/kotoba-lang/svgraph` | kotoba language substrate |
| SVG-first SVGraph / DrawingML / PPTX converter | `com-junkawasaki/svgraph` | `orgs/com-junkawasaki/svgraph` | SVG-era browser/Office tooling |
| TypeScript Office causal graph / JSONL / SVG overlay | `com-junkawasaki/office-causal` | `orgs/com-junkawasaki/office-causal` | SVG-era Office causal tooling |

Manifest policy:

- remove `orgs/com-junkawasaki/office-causal -> orgs/kotoba-lang/office`
  from `manifest/repos.edn :path-overrides`
- remove `orgs/com-junkawasaki/svgraph -> orgs/kotoba-lang/svgraph`
  from `manifest/repos.edn :path-overrides`
- add both restored paths to `manifest/repos.edn :extra-projects`
- record both restored projects in `manifest/west.yml`

Concrete pins restored on 2026-07-01:

- `orgs/com-junkawasaki/office-causal`:
  `374acbce9bb5a3bac3a0c3172f436e07bcfbb8c6`
- `orgs/com-junkawasaki/svgraph`:
  `f4e17a8d448a7cf2e2fa802ad9268b59afa56192`

Both GitHub repos are public and use `main`:

- <https://github.com/com-junkawasaki/office-causal>
- <https://github.com/com-junkawasaki/svgraph>

## Consequences

- `kotoba-lang` keeps the CLJ/EDN rewrite without rollback.
- SVG/browser/TypeScript package identity is preserved under
  `@com-junkawasaki/office-causal` and `@com-junkawasaki/svgraph`.
- ADR-2606302300 remains the default taxonomy, but this ADR is the explicit
  exception: these two `com-junkawasaki` repos are retained because they are
  legacy SVG-era product/tooling surfaces, not the canonical kotoba CLJ/EDN
  substrate.
- `west list` can show both lines of development at once, so migration work can
  compare or port behavior without renaming directories by hand.
- `manifest/west.yml` was updated with a minimal two-entry patch. Full
  regeneration must still follow the pin-regression warning in
  ADR-2606272237: align child repo HEADs before running
  `nbb scripts/gen-west-manifest.cljs`, otherwise unrelated checked-out repos may
  move their pins.

## Verification

Run on 2026-07-01:

- `npm run check` in `orgs/com-junkawasaki/office-causal`
- `npm run check:web` in `orgs/com-junkawasaki/svgraph`
- `python3 -m pytest tests/test_migration.py -q` in
  `orgs/com-junkawasaki/svgraph` (`54 passed`)

## References

- `manifest/repos.edn`
- `manifest/west.yml`
- ADR-2606302300: four-org taxonomy
- ADR-2606271500: west manifest over submodules/vcstool
- ADR-2606272237: west manifest single-entry workflow and pin-regression trap
