# ADR-2607021649: kotoba-lang/slides — 表・画像・グラフのネイティブPPTXエクスポート

**Status**: accepted — 実装・テスト・LibreOffice/Keynote視覚検証済み、`slides` main へ push 済み
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021610 の Addendum で「表/グラフ/画像は `slides.pptx/write-pptx!`
（フル再生成）経由でエクスポートすると素朴なテキストボックスに縮退する」
既知の制約を記録した。オーナーから「これを解決したい」「google slide,
docs, sheets と同じ coverage」との指示を受け、実装した。

事前調査（fork による `slides.pptx`/`slides.model` 読解）で判明した前提:

- `slides.pptx.import/shape-kind` は **既に** `:pic→:image`,
  `:table→:table`, `:chart→:chart` を区別しており（誤って `:text` に
  収束させているという当初の想定は誤り）、`:drawingml/rows`（表セル
  グリッド）も `:slides/rows` として既に流れていた。ギャップは
  **write 側のみ**だった。
- `update-pptx-bytes`（`:ooxml/source` を持つ shape をベース PPTX に
  差分パッチする経路）は表/画像/グラフの元の native XML をそのまま
  保持するため、**既に**正しく動作していた。今回対象にしたのは
  `pptx-bytes`/`write-pptx!`（ゼロから regenerate する経路）のみ。
- 表は自己完結的な XML 生成で済む（新規バイナリ part 不要）。画像は
  埋め込みバイナリ part + 動的 relationship が必要。グラフは
  chart XML 一式に加え、`kotoba-lang` のどこにも存在しなかった
  **xlsx writer をゼロから書く**必要があり、3つの中で最も規模が大きい
  （事前調査で確認済み）。

## Decision

`slides.pptx`（`orgs/kotoba-lang/slides`）に3つのネイティブ writer を追加。
`slides.model`/`office`/`presentationml` 等、他リポジトリの変更は不要
（import 側は既に十分な構造を持っていたため）。

### 表 (`table-shape`)

`:slides/rows` から `<p:graphicFrame><a:tbl>` を直接生成。PowerPoint 既定の
テーブルスタイル GUID（"Medium Style 2 - Accent 1"）を設定（`tableStyles.xml`
パート自体は同梱していないため、実際の配色は失われるが構造は完全に保持
される）。行/列数が不揃いな `:slides/rows` は空文字列でパディングして
常に正当なグリッドになるようにした。

### 画像 (`pic-shape` / `model/image`)

`slides.model/image` を新設（`:slides/image-data` = base64 文字列。EDN/
transit-safe な移植性を optimize し、生の byte array は使わない）。
`pptx-files` は全スライドを走査して画像 shape ごとに:
- base64 をデコードして `ppt/media/imageN.<ext>` バイナリ part を追加
- スライド単位の relationship（`rId2` 以降、`rId1` はレイアウト用に予約）を
  動的生成（従来 `slide-rels` は全スライド共通の静的定数だったため、
  ここが最大の構造変更）
- `[Content_Types].xml` に使用された画像拡張子の `Default` エントリを追加

デコード失敗（不正な base64）は例外ではなく `nil` を返し、呼び出し元は
壊れた `r:embed` 参照を埋め込む代わりに従来通りプレーンテキストへ
フォールバックする。

### グラフ (`chart-shape` / xlsx writer)

`:slides/chart-data`（`update` の chart-data パッチと同じ `{:rows [...]}`
形式 — 新規生成と既存パッチが同じ入力形状を共有）から:
- `ppt/charts/chartN.xml`: `<c:chartSpace>/<c:barChart|lineChart|
  pieChart>/<c:ser>` をゼロから合成。既存の `cache-pt`/`str-cache`/
  `num-cache`（従来は「既存チャートの `<c:ser>` を書き換える」patch 用
  にしか使われていなかった）を新規生成にも再利用。
- `ppt/charts/_rels/chartN.xml.rels` + `ppt/embeddings/
  Microsoft_Excel_SheetN.xlsx`: **ゼロから書いた最小 xlsx writer**
  （それ自体が独立した OPC zip package — `[Content_Types].xml`/
  `_rels/.rels`/`xl/workbook.xml`/`xl/_rels/workbook.xml.rels`/
  `xl/worksheets/sheet1.xml`）で同じ行データを埋め込み、PowerPoint の
  「データの編集」が実データを開けるようにした（キャッシュ表示値だけ
  ではない）。セル書き込みは `patch-workbook-bytes` と同じ
  `cell-value-xml`/`offset-cell-ref` を再利用し、初期生成と後続の
  `update` パッチで一貫性を保つ。
- スライド側の `rId` はカウンタを画像と共有し（画像が先、グラフは
  `2 + 画像数` から開始）、画像とグラフが同一スライドに同居しても
  衝突しない。

### 移植性の修正

画像実装で追加した `decode-base64` が `#?(:clj)` 限定になっており、
CLJS ビルドで `slides.pptx` 名前空間全体のコンパイルを壊す可能性が
あった（`slide-image-entries` が無条件に参照するため）。`decode-base64`
と新設の `xlsx-bytes` の両方を `:cljs` 分岐で `nil` を返すよう修正
（`pptx-bytes` 自体が既に `:cljs` で例外を投げる＝バイト生成は元々
JVM 限定という設計と整合させつつ、名前空間自体はポータブルに保つ）。

## Verification

- `slides` テストスイート: 144 tests / 705 assertions, 0 failures
  （新規: `writes-table-shape-as-native-graphic-frame`,
  `table-shape-with-ragged-or-empty-rows-*`,
  `writes-image-shape-as-native-pic-with-embedded-media`,
  `image-shape-with-invalid-image-data-falls-back-to-text-safely`,
  `writes-chart-shape-as-native-graphic-frame-with-embedded-workbook`,
  `chart-shape-with-multiple-series-and-line-type-*`,
  `chart-shape-without-chart-data-falls-back-to-text-safely`）。
- 視覚検証: python-pptx 生成の複合デッキ + 手組み EDN デッキの両方を
  LibreOffice headless（`render-pptx` CLI）と Keynote（computer-use
  スクリーンショット）でレンダリング。ネイティブ表（罫線グリッド）、
  ネイティブ画像（実バイト埋め込み、色一致確認）、ネイティブ棒グラフ・
  折れ線グラフ（軸・凡例・系列色が正しく描画）を確認。特にグラフは
  参照実装なしで OOXML DrawingML chart スキーマから直接組んだ XML が
  独立した2つのレンダラ（LibreOffice / Keynote）双方で正しく解釈された
  ことが、スキーマ準拠の強い裏付けとなる。

## Consequences

- (+) `pptx-bytes`/`write-pptx!`（フル再生成）経由でも表・画像・グラフが
  ネイティブ構造を保った PPTX として書き出せるようになった。
  `update-pptx-bytes`（既存ファイルへの差分パッチ）は元々問題なかった。
- (+) `kotoba-lang` に初めて（xlsx を含む）SpreadsheetML writer が
  加わった。将来 `sheets` パッケージが独自の xlsx export を必要とする
  際の参照実装になりうる。
- (-) 表のスタイル GUID は `tableStyles.xml` パート非同梱のため配色が
  適用されない（構造は保持、装飾のみ簡易）。
- (-) `update` パス（`append-shapes-xml`、`:ooxml/source` を持たない
  新規追加 shape）経由で新規に画像/グラフを足す場合は、rel 配線を
  実装していないため従来通りテキストへフォールバックする
  （**既存**の画像/グラフを編集する場合は元々 `:ooxml/source` 経由で
  完全に保持される。ギャップは「`update` セッション中に足された全く
  新規の画像/グラフ」のみ、比較的狭い）。
- (-) グラフは棒/折れ線/円のみ対応。散布図・面グラフ等は未対応
  （必要になれば `chart-space-xml` の `case` に追加する形で拡張可能）。
- (-) PowerPoint の「データの編集」で埋め込み xlsx を開いて保存し直す
  実地確認（アプリ自体での round-trip）はしていない（LibreOffice/
  Keynote の描画確認のみ）。

## References

- `orgs/kotoba-lang/slides/src/slides/pptx.cljc`
- `orgs/kotoba-lang/slides/src/slides/model.cljc`（`image` shape
  constructor）
- `90-docs/adr/2607021610-slides-pptx-roundtrip-drift-fix.md`
  （Addendum で本 ADR の対象となった制約を記録）
- 本 ADR とペアの .edn
