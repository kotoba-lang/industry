# P1 — GitOffice Phase 0（正規化 + 協調変換器の参照実装）

doc e（`../e-pull-request-system.md`）の **Phase 0** を、p0 と同じ「純ロジック + bb
round-trip テスト」で実証した参照実装。**選択肢 1（正規化）** に倒した結果、
GitHub 風 PR の前提条件である「文書 = 要素粒度 datom」への変換を最初に固める。

## なぜこれが Phase 0 か

実デプロイの `docs/sheets`（`etzhayyim/root/60-apps/`）は文書を **1 JSON blob**
（`:doc/bodyJson` / `:sheet/gridJson`）+ ETag リビジョン（`:doc/revisionId` / `:sheet/revision`）で
保存している。blob のままでは意味差分・3-way マージが成立しない（doc e §13）。
そこで **blob ⇄ 要素粒度 datom** の双方向変換と revision 橋渡しを最初に用意する。

## 構成

| ファイル | 内容 |
|---|---|
| `gitoffice.cljc` | **Phase 0**: スキーマ登録 + 変換器（下記 3 群） |
| `gitoffice_test.clj` | Phase 0 round-trip + 不変条件テスト |
| `gitdiff.cljc` | **Phase 1**: 意味差分（docs/sheets）+ merge-base/LCA（doc e §3-4） |
| `gitdiff_test.clj` | Phase 1 差分 + LCA テスト |
| `gitmerge.cljc` | **Phase 3**: 3-way マージ（属性レベル自動マージ + 衝突 + 解決）（doc e §4） |
| `gitmerge_test.clj` | Phase 3 マージ + 衝突 + 並行挿入テスト |
| `gitpolicy.cljc` | **merge gate**: 必須承認 / stale 失効 / 必須レビュアー / required CI + land-pr（doc e §5-6） |
| `gitpolicy_test.clj` | merge gate + land-pr テスト |
| `bb.edn` | `bb test` タスク（全モジュールを実行） |

### 変換器（実コードで検証済）

1. **正規化（blob ⇄ 要素粒度 datom）**
   - docs：`:doc/bodyJson`（`{elementId,kind,headingLevel?,text}` の順序リスト）⇄ `:block/*`
     （flat、`:block/order` は fractional-index 文字列。`elementId` を安定 ID にして再正規化は idempotent）
   - sheets：`:sheet/gridJson`（`{title [[cell]]}`）⇄ `:cell/*`（**sparse**、id=`"<sheet>!<A1>"`。
     空セルは持たない＝差分の基本単位）
   - revision 橋渡し：`:doc/revisionId "rev-N"` / `:sheet/revision N` ⇄ `:rev/*`（+ CommitDag seq/commit）
2. **fractional indexing**：`order-between` / `initial-orders`（挿入で衝突しない順序キー）+ A1 変換
3. **協調メタ ⇄ datoms**：`issue` / `pr` / `review` / `comment`（要素アンカー付き）

## 実行

```bash
cd docs/gftd-office/p1-gitoffice
bb test
# gitoffice 13/40 ; gitdiff 7/22 ; gitmerge 8/25 ; gitpolicy 8/28 ; 0 failures, 0 errors
```

### Phase 1 変換器（実コードで検証済）

- **意味差分** `diff-nodes` / `diff-doc` / `diff-sheet`：安定 ID をキーに
  `:added / :removed / :moved / :modified`（属性ごとの `[old new]` delta 付）を算出。
  移動（順序のみ変化）と内容変更を区別。セルは位置=ID なので move 概念なし。
- **merge-base/LCA** `ancestors` / `merge-base` / `merge-base-1`：commit DAG
  （`{commit {:parents [..]}}`）上の共通祖先。merge commit・criss-cross・disjoint を処理。

### Phase 3 変換器（実コードで検証済）

- **3-way マージ** `merge3` / `merge-doc` / `merge-sheet`：base/ours/theirs の node set を
  **属性レベル**で 3-way マージ。`{:merged {id attrs} :conflicts {id info}}` を返す。
  - **属性レベル自動マージ**：同一要素でも ours と theirs が別属性を変えたら衝突しない。
  - **衝突**は narrow：同一要素・同一属性が両側で別値（`:attr`）、または削除 vs 編集（`:delete-modify`）。
  - `resolve-conflict` で解決値を入れて衝突を解消。`merged-doc->body` で bodyJson 復元。
- **並行挿入の非衝突**：fractional index のおかげで ours/theirs の挿入は 2 つの add になり
  衝突しない（`concurrent-inserts-do-not-conflict` テストが `A < X < Y < B` を実証）。

### merge gate（実コードで検証済）

- **`evaluate-merge`**：PR がマージ可能かを判定 → `{:mergeable? :reasons [...] + 診断}`。
  必須承認数 / **stale 承認の失効**（`:review/at-commit ≠ head` を dismiss）/ 必須レビュアー /
  changes-requested ブロック / **required CI**（head で pass 必須、fail はブロック）/ PR が open か。
  ポリシー無し = 無保護（GitHub の unprotected branch と同じく mergeable）。
- **`land-pr-tx`**：マージ確定の retract/assert ops — base ref を merge commit に前進、
  PR を `:merged` + `:pr/merged-commit`、`:pr/closes-issue` の issue を close。

## 検証された不変条件

| テスト | 内容 |
|---|---|
| `body-roundtrip` | bodyJson ⇄ blocks が identity（heading-level は存在時のみ） |
| `body-normalize-idempotent` | 再正規化で安定 ID（elementId）が一致 |
| `grid-roundtrip` / `grid-trailing-empties-trim` | gridJson ⇄ sparse cells が trim 正準形で一致、空セル不保持 |
| `order-strictly-increasing` / `order-between-inserts` | 順序キーが厳密増加、隣接間に常に挿入可 |
| `a1-roundtrip` | col⇄A1 が round-trip |
| `rev-roundtrip` | revision（commit 任意）⇄ datoms 一致 + `"rev-N"` パース |
| `issue/pr/review/comment-roundtrip` | 協調メタ ⇄ datoms 一致（任意フィールド込み） |
| `stale-review-detectable` | `:review/at-commit ≠ :pr/head-commit` で stale 承認を判定可能 |

## 実装中に見つけた実バグ（修正済）

p0 由来の `v-one`（`some` ベース）は **格納された boolean `false` を「未検出」として落とす**
（`:comment/resolved false` が消える）。`reduce`+`reduced` 版に修正。p0/office.cljc も同様の
潜在バグを持つ（boolean 属性を入れた時に顕在化）ので、移植時に同じ修正が要る。

## 実装の所在（3 実装を lockstep に保つ）

正規化・差分・マージ・gate のロジックは **3 箇所**にあり、parity テストでドリフトを防ぐ：

| 実装 | 場所 | 役割 | 検証 |
|---|---|---|---|
| 参照 (.cljc) | `docs/gftd-office/p1-gitoffice/` | 仕様 + bb テスト | `bb test`（36 tests/115 assert） |
| kotoba CLJS | `kotoba/crates/kotoba-wasm/web/cljs/src/kotoba/gitoffice*.{cljc,cljs}` | 本番（ブラウザ wasm node 駆動 + ESM export） | `shadow-cljs release` **0 warnings** + 同 36 bb tests |
| Python edge | `etzhayyim/root/60-apps/etzhayyim-project-{docs,sheets}/lg/.../gitoffice_normalize.py` | 既存 FastAPI アプリの edge で blob⇄datom | pytest（docs 7 / sheets 5）+ **既存回帰なし** |

Python 版は fractional-index の初手キーが `.cljc` と一致（`"i"`）する parity アサーション付き。
canonical はあくまで Clojure 側（doc e）。Python はデプロイ済みアプリが FastAPI のための鏡。

## これは何でないか / 次

- 参照実装。エンティティ ID は論理 ID（実運用は kotoba `commit()` の CID）。
- Phase 0=正規化、Phase 1=差分+LCA、Phase 3=3-way マージ まで純ロジックで完了。
- 次：① **kotoba CLJS 層へ移植**（`transact()`→`commitToIdb()` + 既存 Google/MS 互換 API の
  edge で blob⇄datom 相互変換）、② **解決 UI / PR ビュー**（doc e Phase 2,4 — 差分3ペイン・
  要素アンカーコメント・承認 VC・merge policy gate）、③ slides（svgraph 視覚差分, Phase 6）。
