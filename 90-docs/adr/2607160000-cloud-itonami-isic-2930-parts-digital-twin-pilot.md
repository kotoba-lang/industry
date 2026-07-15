---
id: adr-2607160000-cloud-itonami-isic-2930-parts-digital-twin-pilot
title: "ADR-2607160000: cloud-itonami-isic-2930（自動車部品）に部品完成段階の real digital-twin（CAD/CAM形状＋WebGPUレンダリング＋動線計画）を pilot 導入する"
status: accepted
date: 2026-07-15
deciders:
  - Jun Kawasaki（「cloud.itonami で製造業関係の coverage は? スマートフォン、車は原材料から
    全てのサプライチェーンが設計実装、シュミレーションできているか?」→ 調査の結果、
    isic-2910（完成車組立）だけが実BREP/CAM形状+実物理+実WebGPUレンダリング+動線計画の
    フルセットを持ち、isic-2930（自動車部品）を含む他6業種は「1つの実世界QA試験手順の
    2D物理再現」のみ（形状もレンダリングも無い）と回答 →「では部品完成までのデジタルツイン」
    → AskUserQuestionで「設計ADRを書く」「実際に実装を始める」の両方を選択）
related:
  - 90-docs/adr/2607151600-cloud-itonami-real-engineering-simulation-integration.md（自動車
    完成車pilot。本ADRが再利用する vdesign.cad/vdesign.simphysics/vdesign.scene/
    vdesign.motionplan の正本パターン）
  - 90-docs/adr/2607152000-cloud-itonami-real-engineering-simulation-fleet-extension.md（本
    ADRが拡張元とする isic-2930 の現状 = physics-2d 単体の pull-test 物理のみ、CAD/CAM/
    webgpu-scene bridge は明示的にスコープ外とされていた）
  - 90-docs/adr/2607142800-cloud-itonami-robotics-process-simulation.md（robotics-process-
    simulationパターンの原典）
  - 90-docs/adr/2607083500-cloud-itonami-vehicle-design-link.md（"cloud-itonami は
    kami-engine-*/brep/cnc への依存を持たない"というデフォルト原則。自動車のみ
    ADR-2607151600で例外化済み。本ADRは isic-2930 についても同様に例外化する）
supersedes: []
superseded_by: []
last_verified: 2026-07-15
doc_type: adr
topic: cloud-itonami-manufacturing-digital-twin
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-2930（autoparts）が org-iso-10303（BREP）/kotoba-lang/webgpu の
    scene契約形状/kotoba-lang/physics-2d への実git-coordinate依存を取ることの位置づけ
    （ADR-2607083500の『依存を持たない』デフォルト原則の、isic-2930限定の例外）"
  - "autoparts.cad/autoparts.scene/autoparts.motionplan を isic-2930の実digital-twin
    正本コードとする位置づけ（vdesign.cad/vdesign.scene/vdesign.motionplanの直接移植
    パターン、新規sibling design-libraryリポジトリは作らない）"
  - "他の部品/中間製造業種（isic-2610 fab等）への同パターン拡張は本ADRのスコープ外の
    follow-upであるという明記（自動車pilot→6業種拡張と同型の二段階ロールアウト）"
---

# ADR-2607160000: cloud-itonami-isic-2930（自動車部品）に部品完成段階の real digital-twin を pilot 導入する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

前セッションで「スマートフォン・車は原材料から全てのサプライチェーンが設計実装・
シミュレーションできているか」という質問に対し、`orgs/kotoba-lang/industry/registry.edn`
と各cloud-itonami子リポジトリの実GitHub state（ローカルcheckoutはstaleだったため
`gh api`で直接確認）を調査した結果を回答した。要点:

- 原材料採掘（ISIC 0710/0729）は**未実装**（0710は実装を試みてコンパイルエラーで
  revert済み、0729はリポジトリ自体が存在しない）。
- 完成車組立（isic-2910）だけが、ADR-2607151600により **実BREP/CAM形状
  （`vdesign.cad/envelope-solid`、org-iso-10303の feature-tree）+ 実物理タイムステップ
  （`vdesign.simphysics`、kotoba-lang/physics-2d）+ 実WebGPUレンダリング用データ整形
  （`vdesign.scene`、kotoba-lang/webgpuの`kami.webgpu.mesh`契約）+ ロボット動線計画
  （`vdesign.motionplan`）** のフルセットを持つ。
- isic-2930（自動車部品）を含む他6業種（ADR-2607152000）は、**溶接/締結の proof-load
  pull-testを2D物理でタイムステップ計算するだけ**——形状もレンダリングも動線計画も無い。
  現に`cloud-itonami-isic-2930`の`autoparts.robotics`自身のdocstringが明記している:
  「this ns has no CAD/BREP pipeline, unlike automotive's envelope-solid bridge」。
  jaw/fixtureのAABB寸法（`jaw-half-w-m` 0.01等）も部品ロットごとの実形状から導出されず、
  固定定数のままである。

「では部品完成までのデジタルツイン」という指示を受け、この「完成車組立だけがフル
digital-twinで、部品段階は物理点検査のみ」というギャップを埋める作業に着手する。

## Decision

1. **isic-2930（自動車部品）を pilot に選ぶ**（isic-2910の完成車pilotと対になる、
   「部品完成」段階の代表例として。他の部品/中間製造業種——isic-2610 fab等——への
   同パターン拡張は本ADRのスコープ外、follow-up）。
2. **新規sibling design-libraryリポジトリは作らない。** ADR-2607152000がisic-2930に
   確立した「design-repoを介さず、実git-coordinate依存を直接取る」という最小主義を
   継承する（自動車が`kami-engine-vehicle-designer`という別repoを介したのは、
   `vehicle-design-link`（ADR-2607083500）という既存ペアリングがあったため——isic-2930
   にはそれが無く、新規に作る理由も無い。部品1点のBREP/CAM/scene/motionplanは、
   完成車のような多機能design-review基盤を要しない）。
3. **`cloud-itonami-isic-2930`自身の`autoparts.*`namespace内に直接実装する**（`vdesign.*`
   の対応物をそのまま移植・適応）:
   - `autoparts.cad`（`vdesign.cad`の直接移植・適応）: `org-iso-10303`の
     `brep.feature`/`brep.tessellate`を使い、part-lotの実寸法（joint-mass-kg等、既存の
     part-lot記録フィールドから導出できる代表寸法——正確な導出式は実装セッションで
     part-lotの実データ形状を確認して決める）から溶接/締結ジョイント specimen の
     単純BREP feature-tree（sketch+extrudeのbox、`vdesign.cad/envelope-solid`と同型）
     を構築・tessellateする。**正直な限定を継承する**: 完成車と同様、これは
     styled surfaceではなくbox近似（packaging envelope相当）。
   - `autoparts.robotics/run-pull-test`を、固定定数
     （`jaw-half-w-m`/`fixture-half-w-m`/`limit-boundary-half-w-m`等）ではなく
     `autoparts.cad`が導出した実寸法を使うよう更新する——これが
     「CAD/BREPパイプライン無し」という現状の docstring の限定を実際に解消する。
   - `autoparts.scene`（`vdesign.scene`の直接移植・適応）: `autoparts.cad`の
     tessellated mesh（mm）+ `autoparts.robotics`の物理trajectory（m）を、
     `kotoba-lang/webgpu`の`kami.webgpu.mesh/upload-mesh!`/`render-frame!`が
     そのまま消費できる`{:positions :normals :indices :frames}`形状に変換する
     （面法線は実三角形から計算、mm→m単位変換を行う——`vdesign.scene`と同じ2つの
     ギャップ処理をそのまま踏襲）。
   - `autoparts.motionplan`（`vdesign.motionplan`の直接移植・適応）: 完成車のような
     多工程assembly-order BOMはisic-2930に存在しないため、既存の
     `autoparts.robotics/mission-actions`（cmm-dimensional-scan →
     fastener-torque-check → weld-joint-ultrasonic-scan の3ステップ）を「駅
     （station）」列とみなし、各ステップに実working-height（`autoparts.cad`の実寸法
     由来）を持つCartesian waypointを割り当てる——新規BOM/assembly-orderシステムは
     作らない、既存の3ステップmissionをそのまま再利用する。
4. **新規物理エンジン・CADカーネル・Rustは一切書かない。** 既存の
   `kotoba-lang/physics-2d`（既にisic-2930の依存）・`org-iso-10303`（BREP）・
   `kotoba-lang/webgpu`のcontract形状のみを再利用する（自動車pilotと同じ規律、
   ADR-2607151600 Decision #1）。
5. **正直なスコープ声明**（自動車pilotのADR-2607151600 Decision #4と同型）: 本ADRが
   実装するのは、isic-2930について——実タイムステップ物理（既存）+ 実（box近似)BREP
   形状 + 実WebGPUレンダリング用データ整形 + 実Cartesian動線waypoint列。
   **モデル化しないもの**（自動車pilotと同じ境界）: 3D形状（2Dのみ）、ジョイントの
   材料/剛性モデル（`physics-2d`にforce-deflectionモデルなし）、実ロボット制御・
   IK解、実PLC/MES/SCADA接続、実load-cell/DAQ接続。あくまで"policy, not control"の
   シミュレーションレイヤーの拡張であり、実機統合ではない。

## Consequences

(+) 「部品完成」段階（isic-2930）が、完成車組立（isic-2910）と同じ4要素
（形状・物理・レンダリング・動線）を持つ真のdigital-twinパターンに揃う。
(+) 新規リポジトリ・新規物理エンジン・新規CADカーネルを一切作らず、既存componentの
再利用のみで完結する——ADR-2607151600/2607152000と同じ規律。
(−) 他の部品/中間製造業種（isic-2610 fab、isic-2811エンジン、isic-2920車体等）は
本ADRのスコープ外——「部品完成まで」全体がdigital-twin化されたわけではなく、
isic-2930という1つのpilotのみ。follow-upとして明記する（自動車のADR-2607151600→
ADR-2607152000という二段階ロールアウトと同型のパターンを踏襲する予定）。
(−) スマートフォン側（isic-2610 fab、isic-2620/2630/2640）は本ADRでは一切触れない
——前回の回答で指摘した「2620/2630/2640はロボティクスsimulationすら無い」という
ギャップは未解消のまま。
(−) box近似・2D物理という同じ限定を継承するため、真に詳細な部品形状
（ねじ山・溶接ビード形状等）は表現しない。

## Verification

実装セッションのcommit/PRを本ADRに追記する（テスト green・`clojure -M:lint` clean・
`autoparts.scene`が`kami.webgpu.mesh/upload-mesh!`の入力契約と型互換であることを
テストで確認、を着地条件とする——自動車pilotの`scene_test.cljc`と同じ検証水準）。

## References

- 90-docs/adr/2607151600-cloud-itonami-real-engineering-simulation-integration.md
- 90-docs/adr/2607152000-cloud-itonami-real-engineering-simulation-fleet-extension.md
- 90-docs/adr/2607142800-cloud-itonami-robotics-process-simulation.md
- 90-docs/adr/2607083500-cloud-itonami-vehicle-design-link.md
- https://github.com/cloud-itonami/cloud-itonami-isic-2930（実装対象）
- https://github.com/kotoba-lang/kami-engine-vehicle-designer（vdesign.cad/simphysics/scene/motionplanの参照実装）
- https://github.com/kotoba-lang/org-iso-10303（BREPカーネル）
- https://github.com/kotoba-lang/webgpu（kami.webgpu.mesh契約）
- https://github.com/kotoba-lang/physics-2d（既存の物理依存）
