---
id: adr-2607999600-cloud-itonami-isic-2410-robotics-simulation
title: "ADR-2607999600: cloud-itonami-isic-2410（粗鋼製造）に robotics-process-simulation を追加する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ADR-2607999500（isic-2811）に続き、
    robotics-simulationを持たない自動車中核部品verticalの第二弾。isic-2410は
    isic-0710（鉄鉱石採掘、本セッションで:implemented化済み）の直接の下流工程であり、
    「原材料から」チェーンの連続性という観点でも価値が高い）
related:
  - 90-docs/adr/2607142800-cloud-itonami-robotics-process-simulation.md
  - 90-docs/adr/2607999500-cloud-itonami-isic-2811-robotics-simulation.md（直近の同型ADR）
  - 90-docs/adr/2607141920-cloud-itonami-isic-0710-iron-ore-mining-coverage.md（本ADR対象の直接の上流工程）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-robotics-coverage
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2410（steelworks.robotics）が kotoba-lang/physics-2d への実依存を
    取り、粗鋼製造にとって現実的なQA試験手順を1つ、physics-2dの物理シミュレーションで
    再現することの位置づけ"
---

# ADR-2607999600: cloud-itonami-isic-2410（粗鋼製造）に robotics-process-simulation を追加する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

isic-2811に続き、robotics-simulationを一切持たない自動車中核部品verticalの第二弾。
isic-2410（粗鋼製造）は本セッションで`:implemented`化したisic-2710（鉄鉱石採掘）の
直接の下流工程であり、車のボディ/フレーム（isic-2920 bodyshop、isic-2930
auto-parts）の材料源でもある。

## Decision

isic-2811（ADR-2607999500）と同じ規律で`steelworks.robotics`（新規）を追加する:
`kotoba.robotics/mission`パターン、`kotoba-lang/physics-2d`への実git-coordinate依存、
既存evidence checklistフィールドへの追加（置換ではない）。粗鋼製造にとって現実的な
QA試験（例: 鋼材引張試験・鋼板曲げ試験・溶接部シャルピー衝撃試験等）を実装セッションが
選定・開示根拠付きで決める。governorの独立再検証discipline維持。新規物理エンジン・
CADカーネル・sibling design-libraryリポジトリは作らない。registry.edn更新は
本ADRのスコープ外。

## Consequences

(+) 「原材料（鉄鉱石）→粗鋼→部品→完成車」という自動車サプライチェーンの中で、
連続する2工程（isic-0710・isic-2410）が実物理検証を持つようになる。
(−) isic-2431/2211/2013は本ADRのスコープ外、follow-up候補として残る。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-2410
- https://github.com/cloud-itonami/cloud-itonami-isic-2811（直近の同型実装）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（pull-test参照パターン）
