---
id: adr-2607999700-cloud-itonami-isic-2211-robotics-simulation
title: "ADR-2607999700: cloud-itonami-isic-2211（タイヤ製造）に robotics-process-simulation を追加する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ADR-2607999500（isic-2811）・
    ADR-2607999600（isic-2410）に続く第三弾）
related:
  - 90-docs/adr/2607142800-cloud-itonami-robotics-process-simulation.md
  - 90-docs/adr/2607999600-cloud-itonami-isic-2410-robotics-simulation.md（直近の同型ADR）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-robotics-coverage
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2211（tyremfg.robotics）が kotoba-lang/physics-2d への実依存を
    取り、タイヤ製造にとって現実的なQA試験手順を1つ、physics-2dの物理シミュレーション
    で再現することの位置づけ"
---

# ADR-2607999700: cloud-itonami-isic-2211（タイヤ製造）に robotics-process-simulation を追加する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

isic-2811・isic-2410に続き、robotics-simulationを一切持たない自動車中核部品vertical
の第三弾。isic-2211（タイヤ製造）は自動車の直接接地部品であり、車体（isic-2920）・
部品（isic-2930）と並ぶ完成車（isic-2910）の主要インプットの一つ。

## Decision

isic-2811/isic-2410（ADR-2607999500/2607999600）と同じ規律で`tyremfg.robotics`
（新規）を追加する: `kotoba.robotics/mission`パターン、`kotoba-lang/physics-2d`への
実git-coordinate依存、既存evidence checklistフィールドへの追加（置換ではない）。
タイヤ製造にとって現実的なQA試験（例: ビードワイヤーの引張/引き抜き試験・トレッド
剥離試験・タイヤバースト圧力試験の代替可能な力学量等）を実装セッションが選定・
開示根拠付きで決める。governorの独立再検証discipline維持。新規物理エンジン・
CADカーネル・sibling design-libraryリポジトリは作らない。registry.edn更新は
本ADRのスコープ外。

## Consequences

(+) 自動車の主要部品（エンジン・粗鋼・タイヤ）にrobotics-simulationが揃う。
(−) isic-2431/2013は本ADRのスコープ外、follow-up候補として残る。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-2211
- https://github.com/cloud-itonami/cloud-itonami-isic-2410（直近の同型実装）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（pull-test参照パターン）
