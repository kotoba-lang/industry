# ADR-2607122300: kami-engine-cad と org-iso-10303 の位置付け明文化（非統合の確定）

- **Status**: accepted
- **Related**: ADR-2607121500（kami-app-cad/kami-engine-cad を Rhino3D相当として登録、本ADRが積み残した Open Question の1つを解決）、ADR-2607083900/2607084200（org-iso-10303（旧brep）の実装スコープと ISO 準拠リネーム）

## Context

ADR-2607121500 は「`org-iso-10303`（旧`brep`）との統合はしない」ことを Non-goal として明記したが、住み分けの理由を明文化していなかった。両リポジトリの実体を再確認した:

- **`org-iso-10303`**: ISO 10303-42（BREP topology）+ ISO 10303-21（STEP物理ファイル）に**準拠**するカーネル。`brep.kernel`/`brep.feature`/`brep.assembly`/`brep.tessellate`/`brep.step` の5モジュール構成。`brep.feature` の feature kind vocabulary 自体は `sketch/extrude/revolve/fillet/chamfer/sweep/loft/shell/pattern/boolean` を列挙済みだが、実際にソリッドを生成するのは `extrude`（箱）と `revolve`（軸対称完全回転体）のみ（ADR-2607083900）。**`kotoba-lang/engineer`**（`engineer.constraint`/`.parameter`/`.history`/`.measurement`/`.selection`/`.layer`/`.grid`/`.drc` — dft/spice/pdk/pnr/bim/rtl/cad/cae-solver/eda 等、org内の多数のCAD/EDA系リポジトリが consume する共有基盤)に依存し、その `constraint` vocabulary を鏡映している。
- **`kami-engine-cad`**: 2Dスケッチ+**自前の**拘束ソルバ（`solve-sketch`）+パラメトリックfeature-tree+ソリッド押し出し。`kotoba-lang/engineer` には**依存していない**（`deps.edn` 確認済み、consumer一覧に無い）— 拘束ソルバは独自実装。`kami-app-cad`（ブラウザ内インタラクティブCADアプリ）を駆動する唯一の実装。

両者は同じ「CAD」領域語彙（feature-tree、fillet/chamfer、sketch/constraint）を扱うが、**成立の経緯・消費者・実行モデルが全く別**: `org-iso-10303` はSTEPファイル交換・CAM連携（`org-iso-6983`が下流）を主目的とする、Rust復元由来の厳密トポロジーカーネル。`kami-engine-cad` はブラウザapp（`kami-app-cad`）を動かすためのインタラクティブ編集エンジンで、STEP準拠は目的にしていない。

## Decision

1. **統合しない。両者を別カーネルとして永続的に並存させる。** 目的が違う（ファイル交換 vs インタラクティブapp）以上、無理に一本化すると片方の要求（STEP準拠 or ブラウザ内リアルタイム編集）を歪める。
2. **重複コスト（拘束ソルバ・feature-tree表現の実装が2箇所に存在すること）は認識した上で許容する。** `kami-engine-cad` が `kotoba-lang/engineer` の `engineer.constraint` を採用しない現状維持を積極的に選ぶ理由: `kami-app-cad` はブラウザ shadow-cljs ビルドの一部としてリアルタイム性を要求し、`engineer.constraint` は"fixed point positions に対する status *evaluator*"（README に明記: 反復 Newton-Raphson 未実装、`kami-engine-cad` の `solve-sketch` は実際に水平/垂直/一致/距離拘束を解決する）であり、要求している能力の水準が異なる。今すぐの移行は正当化されない。
3. **将来の相互運用ポイントは明示しておく（実装はしない）**: `kami-engine-cad` で作ったソリッドをCAM/製造連携に出したくなった場合、`kami-engine-cad` を書き換えるのではなく、`org-iso-10303` の `brep.step` writer にエクスポートするブリッジを新設するのが正しい経路（`kami-engine-cad` 側の `solid`/`extrude-polygon` 出力を `brep.kernel` の vertex/edge/face/shell/solid 表現に変換し、`brep.step` で書き出す）。
4. **`kotoba-lang/engineer` は将来 `kami-engine-cad` の拘束ソルバを高度化する必要が生じた時の移行先候補として記録しておく**（現時点でアクション無し。移行するなら別ADRで判断）。

## Consequences

- (+) 両リポジトリとも自分の consumer（`org-iso-6983`/CAM連携 vs `kami-app-cad`）に最適化されたまま進化できる。
- (+) 「なぜ2つCADカーネルがあるのか」という将来の疑問に対する正本の回答ができた。
- (−) 拘束ソルバ・feature-tree語彙の実装重複は解消されない（意図的に許容、上記2参照）。
- (−) STEP相互運用ブリッジ（上記3）は本ADR時点で未実装 — 実需要が出た時点で着手する。

## Open Questions / Follow-up

- `kami-engine-cad` → `org-iso-10303` STEP export ブリッジの実装（実需要が出るまで着手しない）。
- `kami-engine-cad` の拘束ソルバを `kotoba-lang/engineer` の `engineer.constraint` に統合するかどうか（現状不要と判断、将来別ADR）。
