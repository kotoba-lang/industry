---
id: adr-2607999900-cloud-itonami-isic-2013-robotics-simulation
title: "ADR-2607999900: cloud-itonami-isic-2013（プラスチック/合成ゴム一次製品製造）に robotics-process-simulation を追加する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ADR-2607999500/2607999600/
    2607999700/2607999800に続く第五弾かつ最終弾——本セッションで発見した
    robotics-simulation欠如verticalの最後の1つ）
related:
  - 90-docs/adr/2607142800-cloud-itonami-robotics-process-simulation.md
  - 90-docs/adr/2607999800-cloud-itonami-isic-2431-robotics-simulation.md（直近の同型ADR）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-robotics-coverage
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2013（resinmfg.robotics）が kotoba-lang/physics-2d への実依存を
    取り、プラスチック/合成ゴム一次製品製造にとって現実的なQA試験手順を1つ、
    physics-2dの物理シミュレーションで再現することの位置づけ"
---

# ADR-2607999900: cloud-itonami-isic-2013（プラスチック/合成ゴム一次製品製造）に robotics-process-simulation を追加する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

isic-2811・isic-2410・isic-2211・isic-2431に続き、robotics-simulationを一切持たない
自動車中核部品verticalの第五弾かつ最終弾。isic-2013（プラスチック/合成ゴム一次製品
製造）は内装部品・タイヤ用合成ゴム等、自動車の樹脂/ゴム部品の材料源。

## Decision

isic-2811/isic-2410/isic-2211/isic-2431（ADR-2607999500〜2607999800）と同じ規律で
`resinmfg.robotics`（新規）を追加する: `kotoba.robotics/mission`パターン、
`kotoba-lang/physics-2d`への実git-coordinate依存、既存evidence checklist/plausibility
checkフィールドへの追加（置換ではない）。プラスチック/合成ゴム一次製品にとって現実的な
QA試験（例: 樹脂ペレット/コンパウンドの引張試験・伸び試験等）を実装セッションが
選定・開示根拠付きで決める。governorの独立再検証discipline維持。新規物理エンジン・
CADカーネル・sibling design-libraryリポジトリは作らない。registry.edn更新は
本ADRのスコープ外。

## Consequences

(+) 本セッションで発見した5業種（isic-2811/2410/2211/2431/2013）全てに
robotics-simulationが揃う。
(−) このADR群のスコープ自体、自動車サプライチェーンの一部業種に限定されており、
他業種（スマホ隣接・その他ISIC全体）の同種ギャップは未調査のまま。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-2013
- https://github.com/cloud-itonami/cloud-itonami-isic-2431（直近の同型実装）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（pull-test参照パターン）
