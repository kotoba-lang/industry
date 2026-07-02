# ADR-2607021610: kotoba-lang/slides PPTX ラウンドトリップドリフト — 原因特定と修正

**Status**: accepted — 2/3 原因を修正・テスト済み、drawingml/presentationml の main へ反映済み
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

オーナーから「kotobase-lang/slides で PPTX を import, edit, export すると
微妙にずれる」と報告があり、原因調査を行った（`orgs/kotoba-lang/slides` /
`drawingml` / `presentationml` / `office-style` / `office` を横断して読解）。

`slides` の PPTX 変換パイプラインは
`office.opc` → `office-style.style-ir`（テーマ・スライド/レイアウト/マスターの
part 一覧抽出）→ `presentationml.parse/deck`（スライド XML → EDN）→
`drawingml.parse/shapes`（個別 shape XML → EDN）と積み重なる。調査の結果、
3 つの独立した原因を特定した。

### 原因1（最重要）: レイアウト継承なしの座標欠落

PowerPoint 実物のデッキは、動かされていないプレースホルダー（タイトル/本文等）
の `<a:xfrm>` を省略し、位置・サイズを `slideLayout`/`slideMaster` から継承する
のが標準的な挙動。`drawingml.parse/xfrm`（旧実装）はこれを一切考慮せず、
`<a:xfrm>` が無いシェイプは全部固定値 `(0.8in, 0.8in, 8.4in×0.7in)` に
フォールバックしていた（`drawingml/src/drawingml/parse.cljc:92-101`、旧行番号）。
`presentationml.parse`/`office-style.style` のどちらも slideLayout/slideMaster
の XML 本文を一切パースしておらず、継承解決コードが存在しなかった。実物の
複数プレースホルダーが xfrm を省略していると、インポート後に**全部同じ座標に
重なって出力される**。

### 原因2: テーマカラー（`schemeClr`）のドロップ

`drawingml.parse/first-color`（旧実装）は `<a:srgbClr>` と `sysClr
lastClr` のみを正規表現で拾い、`<a:schemeClr val="accent1"/>` のような
テーマ参照色は無視して呼び出し元のハードコードフォールバック
（`"17202A"`/`"EAF0F8"`）に落ちていた。`presentationml.parse/theme-colors`
はテーマ色を `:presentationml/colors` として抽出していたが、この抽出結果は
**個々の shape の色解決には一切渡されていなかった**（`deck` 関数内で
`theme` 計算と `slides` 計算が独立していた）。`test/slides/fixtures/
pptx_roundtrip_matrix.edn` の全ケースを確認したが `schemeClr` を使う
フィクスチャは1つもなく、既存テストで検出されないギャップだった。

### 原因3: パース例外時の全滅フォールバック（許容・未修正）

`slides.office/deck-from-office-bytes` は、`pptx-import/deck-from-entries`
が例外を投げるか `nil` を返した場合にのみ、`office.graph/analyze-bytes`
（テキストのみを抽出する汎用フォールバック）に切り替わる
（`slides/src/slides/office.cljc:138-171`）。このフォールバック経路は
元座標を一切見ずテキストを機械的に縦積みし直す（`text-shape-for-node`,
同ファイル 70-79 行）。通常の有効な `.pptx` では踏まないが、複雑な実デッキ
（グループ/チャート/表等の非対応要素で `office-style.extract-bytes` や
`opc.open-package` が例外を投げるケース）では位置情報が丸ごと失われる。
これは `office.graph` パッケージ自体（別リポ）の shape 位置抽出を新設する
必要がある大きめの変更のため、本 ADR では原因の記録のみとし、修正は
別スコープとする。

単位変換（EMU⇔inch, `emu-per-inch = 914400`）自体は書込側（`slides.pptx`）と
読込側（`drawingml`/`presentationml`）で一貫しており、ズレの原因ではないと
確認した。

## Decision

原因1・2を `drawingml`/`presentationml`（ともに `kotoba-lang` 傘下の別リポ、
`slides` の deps.edn `:local` alias でソース参照）で修正する。`slides` 自体は
無変更（`slides.pptx.import` は `presentationml.parse/deck` の出力をそのまま
写像するだけなので、下流の修正だけで解決する）。

- **`drawingml.parse`**: `xfrm-explicit`（`<a:xfrm>` が無ければ `nil` を返す）
  と `placeholder-geometry-index`（slideLayout/slideMaster XML から
  `[idx type]`/`[nil type]` キーでプレースホルダー位置を索引化）を新設。
  `xfrm` は「自身の明示 xfrm → opts の `:placeholder-geometry` 索引 →
  歴史的固定フォールバック」の順に解決するよう変更。`first-color`/
  `solid-fill`/`line-fill` は `theme-colors`（プレーンキーワード
  `{:accent1 "RRGGBB" ...}`）を受け取り、`srgbClr` が無ければ `schemeClr`
  を OOXML の既定 `clrMap`（`bg1→lt1`, `tx1→dk1`, `bg2→lt2`, `tx2→dk2`,
  他は素通し）経由で解決するよう変更。全 shape コンストラクタ
  （`text-shape`/`rect-shape`/`pic-shape`/`table-shape`/`chart-shape`）に
  `opts` を通す。
- **`presentationml.parse`**: `layout-path`/`master-path`（スライド→
  レイアウト→マスターの relationship 解決）、`placeholder-geometry`
  （レイアウト優先・マスターにフォールバックした索引の合成）、
  `theme-color-roles`（`presentationml.color/*` の名前空間付きキーを
  drawingml 向けプレーンキーワードへ変換）を新設し、`deck`/`slide` から
  `dml/shapes` の opts へ配線。

## Verification

- `drawingml`: 新規 `test/drawingml/parse_test.cljc`
  （`placeholder-geometry-inheritance-test`,
  `scheme-color-resolution-test`）+ 既存 `core_test.clj` — 10 tests /
  105 assertions, 0 failures.
- `presentationml`: 新規 `inherits-placeholder-geometry-and-scheme-color-from-layout`
  （slide→layout の relationship 解決を含む end-to-end テスト）+ 既存
  `core_test.clj` — 7 tests / 47 assertions, 0 failures.
- `slides`: `clojure -M:local:test`（drawingml/presentationml をローカル
  ソース override）— 130 tests / 631 assertions, 0 failures, 既存挙動への
  regression なし。

## Consequences

- (+) 実物の PowerPoint デッキ（プレースホルダーが xfrm 省略、テーマ色使用）
  を import → edit → export した際の位置・色ズレが解消する。
- (+) 新規テストが、修正前は検出されていなかった具体的な回帰シナリオ
  （レイアウト継承・schemeClr 解決）をカバーするようになった。
- (-) OOXML の `<p:clrMap>` オーバーライド（既定でない `bg1`→`dk1` 等の
  カスタムマッピング）は未対応。既定 clrMap のみサポート。実運用で
  カスタム clrMap を使うデッキに遭遇したら追加対応が必要。
- (-) プレースホルダーの `idx`/`type` マッチングは簡略化した近似
  （完全な OOXML placeholder-matching アルゴリズムではない）。
- (未対応) 原因3（パース例外時の全滅フォールバック）は記録のみ。
  `office.graph` の shape 位置抽出強化は別スコープ。

## References

- `orgs/kotoba-lang/drawingml/src/drawingml/parse.cljc`
- `orgs/kotoba-lang/presentationml/src/presentationml/parse.cljc`
- `orgs/kotoba-lang/slides/src/slides/office.cljc`
- `orgs/kotoba-lang/slides/README.md`（Office PPTX インポート節）
- 本 ADR とペアの .edn
