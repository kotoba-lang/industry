---
id: adr-2607084200-brep-cnc-iso-conformance-rename
title: "ADR-2607084200: kotoba-lang/cnc を org-iso-6983 へ、kotoba-lang/brep を org-iso-10303 へrename — 実際に規格に準拠していることを確認した上での reverse-domain 命名"
status: accepted
doc_type: adr
topic: kotoba-lang-cad-cam-integration
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - cnc→org-iso-6983、brep→org-iso-10303 のrename決定と根拠
  - brep.step（ISO 10303-21 STEP物理ファイルreader/writer）の実装スコープ
  - この2件が、ADR-2607083200のCAE 6リポジトリ（reverse-domain不採用）と異なる扱いを受ける理由
related:
  - 90-docs/adr/2607083200-kami-engine-cae-rename-batch.md
  - 90-docs/adr/2607083900-brep-cnc-engineering-capability.md
  - orgs/kotoba-lang/org-iso-6983
  - orgs/kotoba-lang/org-iso-10303
supersedes: []
superseded_by: []
---

# ADR-2607084200: brep/cnc ISO conformance rename

- Status: accepted (2026-07-08)
- Deciders: Jun Kawasaki

## Decision

`kotoba-lang/cnc` を `kotoba-lang/org-iso-6983` へ、`kotoba-lang/brep` を
`kotoba-lang/org-iso-10303` へGitHub renameする。

- **cnc → org-iso-6983**: `kotoba.cam.gcode`が出力するG/M-code語彙
  （G00/G01/G02/G03/G21/G90/M03/M05/M06/M08/M09/M30等）は、G-codeの国際
  規格**ISO 6983-1**（1963年のEIA RS-274に起源、1979年RS-274-D改訂を経て
  1980年にISOが標準化）そのものである。
- **brep → org-iso-10303**: `brep.kernel`のvertex/edge/face/shell/solid
  トポロジーモデルは、STEPのgeneric resource層である**ISO 10303-42**
  （"Geometric and topological representation"）が定義するモデルと直接
  一致する。加えて本ADRの一部として新規実装した`brep.step`が、
  **ISO 10303-21**（STEP物理ファイル形式）の実際の読み書きを行う。

## Context

前回のセッションで「これらの工学的なものにも公式団体・基準団体の参照
ドメインはないか」という問いを受け、Web調査の結果、上記2つの規格との
一致を確認した。ただし当初の調査時点では:

- `cnc`はG-codeの**語彙**（コマンド体系）自体が規格由来であり、実際に
  パース可能なG-codeテキストを出力するため、既存の`org-iso-jpeg`/
  `org-iso-h264`/`org-iso-isobmff`（実際にそのバイナリ形式を読み書き
  する場合に使う命名）と同じ基準を満たすと判断——即rename。
- `brep`はトポロジーの**概念**はISO 10303-42と一致するが、STEPファイル
  （.step/.stp）の読み書きを一切実装しておらず、EXPRESS言語の正式な
  entity名とも異なる独自データ構造だったため、rename前に**実際に
  STEPファイルI/Oを実装する**ことをオーナーが選択した。

## brep.step の実装（rename の前提条件）

`brep.step/write-step`・`read-step`を新規実装した（ADR-2607083900より
後、本renameの直接の前提）:

- **entity mapping**は steptools.com の参照用AP203サンプル`block.stp`
  （第三者が作成した実在のSTEPファイル）に対して事前に検証した
  （記憶やAIの推測だけで実装していない）。CARTESIAN_POINT/DIRECTION/
  VECTOR/AXIS2_PLACEMENT_3D/LINE/PLANE/VERTEX_POINT/EDGE_CURVE/
  ORIENTED_EDGE/EDGE_LOOP/FACE_BOUND/ADVANCED_FACE/CLOSED_SHELL/
  MANIFOLD_SOLID_BREPの13種類のentityとパラメータ順序を実データで確認。
- **スコープ**: PLANE面・LINE辺のみ（`make-box`が生成するもの）。
  `make-cylinder`はwrite-stepが`:error`をthrowして拒否する
  （多角形近似の円柱を、STEPの解析的なCYLINDRICAL_SURFACE/CIRCLE
  entityとして偽って書き出すのは不正確なため、フォローアップ課題として
  明記し先送り）。
- **正しさの検証**: round-trip（自作の立体をwrite→read→face数/edge数/
  vertex数/bounding box/体積/表面積が完全一致することを確認）で担保。
  `read-step`は汎用STEPパーサではなく、write-stepが出力するentity
  部分集合のみを認識する（INTERSECTION_CURVE等、実際の第三者STEPファイル
  が使う他のentityは非対応、と明記）。

## Consequences

- `kami-engine-vehicle-designer`のdeps.edn座標を新名称へ更新
  （`io.github.kotoba-lang/org-iso-10303`・`io.github.kotoba-lang/org-iso-6983`）。
  Clojureの`require`先namespace（`brep.*`/`kotoba.cam.*`）はrename対象
  ではないため変更不要、regressionなしを確認。
- brep: 30→35テスト(386→409アサーション)、cnc(=org-iso-6983)は変更なし
  （STEP I/Oはbrep側の作業）。両repo clj-kondo clean。
- ADR-2607083200（CAE 6リポジトリのkami-engine-*統一）との対比:
  あちらは実装が「自社開発のclean-room縮退モデル」で外部規格の実装で
  なかったためreverse-domainを不採用にした。本ADRの2件は逆に、
  実際にフォーマット/語彙レベルで規格に準拠していることを確認した上で
  reverse-domainを採用した——同じ判断枠組みを一貫して適用した結果。

## 却下案

- **確認なしに`brep`も即rename**: 実際にSTEPファイルを読み書きできない
  状態で「ISO 10303-21準拠」を名乗るのは実態と異なる主張になる。
  ADR-2607083200のCAE solversの反省と同じ理由で、rename前にI/O実装を
  要求する判断をオーナーが下した。
- **`brep.step`でINTERSECTION_CURVE等も含めた汎用STEPパーサを実装**:
  スコープが大きく広がり、正確性の検証（第三者ファイルとの照合）も
  複雑化するため、今回はLINE/PLANEのみに限定し、round-trip検証という
  達成可能で誠実な基準を選んだ。
