---
id: adr-2607083400-vdesign-cad-cam-bridge
title: "ADR-2607083400: kami-engine-vehicle-designer を kotoba-lang/brep(CAD) と kotoba-lang/cnc(CAM) へ橋渡しする — released spec に :geometry を昇格し、packaging envelope BREP → 実CAM toolpath/G-code まで繋ぐ"
status: accepted
doc_type: adr
topic: kotoba-lang-cad-cam-integration
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - kami-engine-vehicle-designer の released spec に :geometry フィールドを追加する決定
  - vdesign.cad（BREP packaging envelope bridge）の新設と、その誠実なスコープ（styled body ではなく bounding-box envelope）
  - vdesign.process の CAM 生成を手組み G-code 文字列から実 kotoba-lang/cnc エンジンへ置き換える決定
  - kotoba-lang/cad が geometry kernel ではなく artifact registry/maturity scoring 層であるという事実の明記
related:
  - 90-docs/adr/2606272330-cae-shared-libs-and-seeds.md
  - 90-docs/adr/2606301100-kotoba-lang-engine-repo-migration.md
  - 90-docs/adr/2607083200-kami-engine-cae-rename-batch.md
  - orgs/kotoba-lang/kami-engine-vehicle-designer
  - orgs/kotoba-lang/brep
  - orgs/kotoba-lang/cnc
  - orgs/kotoba-lang/cad
supersedes: []
superseded_by: []
---

# ADR-2607083400: vehicle-designer → CAD(brep) → CAM(cnc) bridge

- Status: accepted (2026-07-08)
- Deciders: Jun Kawasaki

## Decision

1. `vdesign.design/geometry-of` を新設し、released spec に `:geometry`
   （`:wheelbase-m` `:floor-area-m2` `:frontal-area-m2`）を追加する。
   これらは以前から `simverify.geom`（class別ルックアップ）と
   `proposer.classes`/aero caseの内部値として存在したが、最終出力へは
   一切伝播していなかった。
2. 新規 `vdesign.cad` namespace（`kotoba-lang/brep` に依存）が `:geometry`
   から packaging envelope BREP solid を生成しtessellateする。
3. `vdesign.process` の CAM 生成を、手組みの G-code 文字列テンプレート
   から実 `kotoba-lang/cnc`（`kotoba.cam.stock`/`tool`/`toolpath`/`gcode`)
   呼び出しへ置き換える。加えて packaging envelope のtessellate結果を
   `kotoba.cam.stock/from-mesh` に直結し、design-review用の「buck」CAM
   job（`envelope-buck`）を新設する。
4. 生成される全出力ファイル（各CAM jobの `.nc`、envelopeの `.stl`）を
   `kotoba-lang/cad`（`kotoba.cad.core/classify-artifact`）で分類する。

## Context

ユーザーから「vehicle-designer / CAD / CAM の統合を設計・実装してほしい」
という依頼を受けて調査した結果:

- `kami-engine-vehicle-designer` の released spec は質量/エネルギー/出力の
  スカラーのみで、幾何情報を一切含んでいなかった。
- `kotoba-lang/cad` は名前に反してジオメトリカーネルではなく、CADアーティ
  ファクト（DXF/STEP/STL/gcode）の成熟度スコアリング・ガバナンス層
  （`classify-artifact`/`score`/`runner-plan`）だった。
- 実際のBREPカーネルは `kotoba-lang/brep`（`brep.feature`のsketch/extrude/
  revolve/fillet/chamfer/boolean等）で、現状 `:extrude` の `:operation :new`
  のみが実際にsolidを生成する（固定 ±0.5 unitの正方形断面を extrude
  distance分だけ押し出したbox。sketch entityの内容自体は現時点で
  `evaluate` に消費されない。revolve/fillet/chamfer/boolean は upstream の
  未実装TODO）。
- `kotoba-lang/cnc`（`kotoba.cam.*`）はtoolpath生成のうち `:pocket`
  （zigzag）と `:drill`（peck）のみ実装済み、他はplaceholder。
- `vdesign.process` は既に BOM→CAM→4D組立順を生成する `plan` 関数を
  持っていたが、そのCAM部分は「kami-cam語彙を借用する」と自称しつつ
  実際は独自の文字列テンプレートでG-codeを生成しており、
  `kotoba.cam.gcode/generate-gcode` を一度も呼んでいなかった。
- vehicle-designer・brep・cnc・cad の4リポジトリ間に実コード上の接続は
  一切存在しなかった。

## 誠実なスコープの明記

- **packaging envelope であって styled body ではない**: `brep.feature/evaluate`
  の現在の実装能力に忠実に従い、生成されるのは車両のバウンディングボックス
  近似（矩形箱）に留まる。実車のスタイリングされた外板形状は生成できない。
- **envelope の寸法導出は近似**: `frontal-area-m2` を高さと幅に分解する際、
  `assumed-height-m`（1.5m固定）を仮定して幅を逆算し、長さは
  `wheelbase-m × overhang-ratio`（1.15固定）で近似する。実測値ではない。
- **CAM toolpath は pocket/drill のみ**: 5軸加工や実際の外板追従加工は
  範囲外。envelope-buck は「design-reviewの検討用buck」を粗く1パスで
  ポケット加工する想定で、量産加工プロセスではない。
- `kotoba-lang/cad` へは新規ロジックを追加していない。既存の
  `classify-artifact` をそのまま呼び出すだけで用途を満たすことを確認した。

## Consequences

- `kami-engine-vehicle-designer` の `deps.edn` に `brep`/`cnc`/`cad`（いずれも
  `kotoba-lang` org）が新規依存として加わった。`cad` は reagent/re-frame/
  shadow-cljs 等の重い依存を持つが（ブラウザUIアプリのため）、JVM側では
  `kotoba.cad.core/classify-artifact` という純粋関数しか使わないため実害は
  ダウンロード量のみ。
- 新規テスト `test/vdesign/cad_bridge_test.clj` は実StateGraph
  （`vdesign.design/build`）経由で `:geometry` が実際に埋まった状態を検証
  する（既存 `closure_contract_test.clj` の `release` ヘルパーは
  `:geometry` 新設以前のもので、これを一切セットしないため別テストとした）。
  BREP boxの三角形数が12（矩形箱の理論値=6面×2）であることまで検証し、
  「例外が飛ばない」以上の実質的検証を行っている。
- `process/plan` は `:geometry` が無い design（例えば将来の別呼び出し元）
  に対して `envelope-buck`/一部`artifacts` が nil/減った状態に自然degrade
  する（例外を投げない）。

## 却下案

- **sketchエンティティで断面形状を精密制御しようとする**: `brep.feature/evaluate`
  が現状sketch entityを一切評価しないため技術的に不可能。虚偽の精度を
  主張しないよう、素直にbox近似＋post-scaleとし、この制約をdocstringに
  明記する方針を採った。
- **envelope全体を1つのCAM stockとして全部品を機械加工する**: 物理的に
  不合理（小部品の加工に車両サイズのstockを使う）。既存の部品別CAM job
  はそのまま部品サイズのstockを使い、envelope-buckは別ジョブとして分離。
