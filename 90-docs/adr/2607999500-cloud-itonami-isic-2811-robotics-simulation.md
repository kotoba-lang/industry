---
id: adr-2607999500-cloud-itonami-isic-2811-robotics-simulation
title: "ADR-2607999500: cloud-itonami-isic-2811（エンジン/タービン製造）に robotics-process-simulation を追加する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。digital-twin waveが完了、
    ユーザーが「個別vertical拡大を継続」を選択 → 自動車サプライチェーンの中核部品
    verticalのうち、robotics-simulation自体を一切持たないもの（isic-2811/2410/
    2431/2211/2013）を発見、第一弾としてisic-2811を選定）
related:
  - 90-docs/adr/2607142800-cloud-itonami-robotics-process-simulation.md（symbolic simulationパターンの原典）
  - 90-docs/adr/2607991500-cloud-itonami-isic-2620-robotics-simulation.md（直近の実装先例）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-robotics-coverage
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2811（turbine.robotics）が kotoba-lang/physics-2d への実依存を
    取り、エンジン/タービン製造にとって現実的なQA試験手順を1つ、physics-2dの物理
    シミュレーションで再現することの位置づけ"
---

# ADR-2607999500: cloud-itonami-isic-2811（エンジン/タービン製造）に robotics-process-simulation を追加する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

digital-twin wave（ADR-2607160000〜2607998500、isic-2930/2610/0810/2394/4211/4741）
完了後、自動車サプライチェーンの他の中核部品verticalを確認したところ、
isic-2811（エンジン/タービン製造）・isic-2410（粗鋼）・isic-2431（鋳造）・
isic-2211（タイヤ）・isic-2013（樹脂/合成ゴム）は`:implemented`（governance actor
自体は存在）だが、`robotics.cljc`/`simphysics.cljc`に相当するnamespaceが**一切存在
しない**——ADR-2607142800のsymbolic robotics-process-simulationパターンすら適用
されていない、という新しいギャップ種別を発見した。

isic-2811（エンジン/タービン）は自動車パワートレインの中核部品であり、第一弾として
選定する。

## Decision

1. **`turbine.robotics`（新規）を追加**し、ADR-2607142800のsymbolicパターン
   （まず`kotoba.robotics/mission`+`kotoba.robotics/action`+
   `kotoba.robotics/telemetry-proof`による3ステップmission、既存の自己申告
   evidence checklistフィールドを実データ比較のground-truth checkに置き換える）を
   isic-2811既存のfacts/registryに存在する実evidenceフィールドに対して適用する。
2. **物理シミュレーション（physics-2d実タイムステップ）を直接追加してもよい** ——
   isic-2620/2630同様、いきなりsymbolicを飛ばして実物理point-testを実装する方が
   このfleetの直近の慣例（2026-07-14以降の新規verticalは直接physics-2dを使う傾向）
   に合致する。エンジン/タービン製造にとって現実的なQA試験（例: コンロッド/ボルト
   の締結トルク・引張proof-load試験、ピストンリング嵌合力試験等）を実装セッションが
   選定・開示根拠付きで決める。
3. **governorの独立再検証discipline**（既存checkと同じ"ground truth, not self-report"）
   を維持、既存checkは変更しない、追加のみ。
4. **新規物理エンジン・CADカーネル・sibling design-libraryリポジトリは作らない。**
   `kotoba-lang/physics-2d`のみ再利用。
5. **registry.edn更新は本ADRのスコープ外。**

## Consequences

(+) 自動車サプライチェーンの中核部品verticalに、初めてrobotics-simulationが加わる。
(−) isic-2410/2431/2211/2013は本ADRのスコープ外、follow-up候補として残る。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-2811
- https://github.com/cloud-itonami/cloud-itonami-isic-2620（直近のsymbolic→実物理実装先例）
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（part-lot pull-testの参照パターン）
