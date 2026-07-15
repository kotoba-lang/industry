---
id: adr-2607999800-cloud-itonami-isic-2431-robotics-simulation
title: "ADR-2607999800: cloud-itonami-isic-2431（鋳造）に robotics-process-simulation を追加する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ADR-2607999500/2607999600/2607999700
    に続く第四弾）
related:
  - 90-docs/adr/2607142800-cloud-itonami-robotics-process-simulation.md
  - 90-docs/adr/2607999600-cloud-itonami-isic-2410-robotics-simulation.md（直近の同型ADR、粗鋼引張試験）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-robotics-coverage
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2431（foundrymfg.robotics）が kotoba-lang/physics-2d への実依存を
    取り、鋳造にとって現実的なQA試験手順を1つ、physics-2dの物理シミュレーションで
    再現することの位置づけ"
---

# ADR-2607999800: cloud-itonami-isic-2431（鋳造）に robotics-process-simulation を追加する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

isic-2811・isic-2410・isic-2211に続き、robotics-simulationを一切持たない自動車
中核部品verticalの第四弾。isic-2431（鉄鋼鋳造）はエンジンブロック（isic-2811）等の
鋳造部品の上流工程。

## Decision

isic-2811/isic-2410/isic-2211（ADR-2607999500/2607999600/2607999700）と同じ規律で
`foundrymfg.robotics`（新規）を追加する: `kotoba.robotics/mission`パターン、
`kotoba-lang/physics-2d`への実git-coordinate依存、既存evidence checklistフィールド
への追加（置換ではない）。鋳造にとって現実的なQA試験（例: 鋳物の引張試験・シャルピー
衝撃試験・ゲート部の除去強度試験等）を実装セッションが選定・開示根拠付きで決める。
governorの独立再検証discipline維持。新規物理エンジン・CADカーネル・sibling
design-libraryリポジトリは作らない。registry.edn更新は本ADRのスコープ外。

## Consequences

(+) 自動車中核部品verticalのrobotics-simulationカバレッジがさらに1業種進む。
(−) isic-2013はfollow-up候補として残る。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-2431
- https://github.com/cloud-itonami/cloud-itonami-isic-2410（直近の同型実装）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（pull-test参照パターン）
