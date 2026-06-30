---
id: adr-2606301100-kotoba-lang-engine-repo-migration
title: "ADR-2606301100: CFD / vehicle designer / kekkai repos を kotoba-lang の public repos へ移管する"
status: accepted
doc_type: adr
topic: kotoba-lang-repo-boundaries
authoritative: true
last_verified: 2026-06-30
authoritative_for:
  - kami engine 周辺 repo の GitHub owner と canonical repo name
  - kekkai の actor suffix 撤去
  - vehicle-design actor の kami-engine namespace への編入
related:
  - 90-docs/adr/2606272130-vehicle-design-actor-bev-fcev.md
  - 90-docs/adr/2606272230-vehicle-design-sim-verify-datafied-process.md
  - 90-docs/adr/2606272330-cae-shared-libs-and-seeds.md
  - 90-docs/adr/2606272350-kudaki-nagare-highfidelity-fea-cfd-backends.md
  - orgs/kotoba-lang/kekkai
  - orgs/kotoba-lang/kami-engine-cfd
  - orgs/kotoba-lang/kami-engine-vehicle-designer
supersedes: []
superseded_by: []
---

# ADR-2606301100: kotoba-lang engine repo migration

**Status**: accepted
**Date**: 2026-06-30
**Deciders**: Jun Kawasaki

## Decision

`com-junkawasaki` 配下にあった CFD / vehicle design / kekkai actor 系 repo を
`kotoba-lang` 配下の public repos として扱う。

Canonical mapping:

| before | after |
|---|---|
| `com-junkawasaki/kekkai-actor` | `kotoba-lang/kekkai` |
| `com-junkawasaki/kami-cfd` | `kotoba-lang/kami-engine-cfd` |
| `com-junkawasaki/vehicle-design-actor` | `kotoba-lang/kami-engine-vehicle-designer` |

`kekkai` は domain 名そのものを repo 名にし、`-actor` suffix を落とす。
`kami-engine-cfd` は kami-engine 周辺の high-fidelity CFD backend として命名する。
`kami-engine-vehicle-designer` は clean-sheet vehicle design actor を kami-engine の設計 surface
として位置付ける。

## Context

これらの repo は kotoba/kami engine の公開 surface に属する一方、旧 owner と旧名は
責務境界を曖昧にしていた。

- `kami-cfd` は engine backend であり、単独の個人 repo 名より `kami-engine-*` namespace が適切。
- `vehicle-design-actor` は設計 loop だが、実際には kami-engine の sim / CAE / manufacturing flow と結合する。
- `kekkai-actor` は actor 実装より zero-trust mesh coordination domain そのものを表す。
- `kotoba-lang` は public repos を前提とする org なので、移管後は public visibility を維持する。

## Consequences

- west manifest の canonical path は `orgs/kotoba-lang/...` へ移る。
- 旧 GitHub URLs は GitHub redirect として残るが、文書と manifest は新 canonical name を使う。
- 新規設計・CI・PR は `kotoba-lang/kekkai`、`kotoba-lang/kami-engine-cfd`、
  `kotoba-lang/kami-engine-vehicle-designer` を対象にする。
- 過去 ADR に残る旧名は履歴上の文脈として許容するが、新しい参照では本 ADR の canonical mapping を優先する。
