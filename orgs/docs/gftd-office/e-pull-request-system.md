# (e) GitOffice — オフィス文書の GitHub 風 Issue / PR / Review / Merge システム設計

`slides.gftd.ai` / `docs.gftd.ai` / `sheets.gftd.ai` と、それらが書き出す
`.pptx` / `.docx` / `.xlsx` に対して、**GitHub のように** バージョン管理・Issue・
Pull Request・レビュー・マージができるシステムの設計書。

> 前提：本書は doc a〜d（kotoba 基盤オフィス）の続き。a〜d で
> 「datom 文書モデル / org・grant / CACAO・passkey / svgraph・office-causal 取り込み」が
> **実コードで検証済み**なので、本書はその上に**協調ワークフロー層**だけを足す。

---

## 0. 核心となる設計判断

GitHub をオフィスに移すとき、最大の落とし穴は「`.pptx` を git に入れて差分を取る」こと。
OOXML は ZIP+XML で、保存ごとに要素順・空白・rId が変わる → **テキスト差分が無意味**になる。

**判断：正準表現（canonical）は datom-native の構造木にする。OOXML は import/export の成果物に過ぎない。**

```
.pptx/.xlsx/.docx  ──(import: svgraph / office-causal)──▶  datom 構造木（正準・差分対象）
        ▲                                                          │
        └──────────────(export: svgToPptx / embedDataPart)────────┘
```

これにより：

- **差分・マージ・レビューは安定 ID を持つ構造木の上で行う**（テキスト差分ではなく**意味差分**）。
  - office-causal の `ocz1:`（content-addressed・保存で揺れない）= スライド要素 / セル / 段落の安定 ID。
  - 既存 office スキーマの `:block/*`（fractional index 順序）+ `:cell/*` + `:slide/*`。
- **履歴・タイムトラベル・コミット DAG は kotoba の CommitDag に既に存在**（無料で付く）。
- OOXML との往復は svgraph / office-causal が担保（**ベンダーロックインなし**・PowerPoint で開ける）。

> なぜ git そのものではなく datom か：GitHub の git は「行指向テキストの 3-way merge」が核。
> オフィス文書は**木構造 + 順序 + リッチ属性**で、行 merge は破綻する。kotoba の datom（EAVT）+
> CommitDag は **木の content-addressed merge** に向く。なお相互運用のため、文書の各リビジョンは
> `kotoba-git` で**本物の git commit/tree/blob としても投影**でき（`:git/*` datom、SHA↔CID 橋）、
> `git clone` 互換のミラーも出せる（§9）。

---

## 1. GitHub 概念 → kotoba primitives 対応表

| GitHub | GitOffice | kotoba 実体 | 既存/新規 |
|---|---|---|---|
| Repository | 文書（または文書束=プロジェクト） | org の data-graph 上の `:repo/*` エンティティ | 新規スキーマ |
| File tree | 文書の構造木 | `:doc/* :block/*` / `:slide/* :shape/*` / `:sheet/* :cell/*` | 既存(a) + 拡張 |
| Blob（行指向） | スライド要素 / セル / 段落（**意味単位**） | 安定 ID = `ocz1:` または block CID | 既存(c) |
| Commit | リビジョン | **CommitDag `Commit{root_cid, prev_cid, seq}`**（正準会計チェーン） | 既存(kotoba) |
| Branch | 名前付き ref | `:ref/*`（name → commit CID） | 新規（kotoba-git `:git.ref/*` を流用） |
| Tag / Release | 公開版 | `:ref/*` (kind=tag) + 署名コミット | 既存 commitSigned |
| Diff | **意味差分**（追加/削除/移動/属性変更） | 2 commit 間の datom Δ を木整列して算出 | 新規 differ |
| Issue | 課題 | `:issue/*` エンティティ | 新規 |
| Pull Request | 提案マージ | `:pr/*`（base ref, head ref, status） | 新規 |
| Review | レビュー | `:review/*` + 行アンカー `:comment/*`（anchor=安定 ID） | 新規 |
| Review comment（行コメント） | 要素アンカーコメント | `:comment/anchor` = `ocz1:` / cell ref / block CID | 新規 |
| Approval | 承認 | `:review/state :approved` + **W3C VC 署名**（kotoba-vc） | 新規 + 既存 |
| Merge | マージ | 3-way 木マージ → 新 commit（2 parent） | 新規 merger |
| Merge conflict | 競合 | 同一安定 ID への base≠ours≠theirs の変更 | 新規 |
| CODEOWNERS / branch protection | 権限・必須レビュー | `:grant/*`（access DAG）+ CACAO capability + merge policy | 既存(a) + 拡張 |
| Actions / CI | 文書 CI | office-causal 解析・MECE・coverage を merge gate に | 既存(c) |
| Identity | 著者 | DID（passkey 由来）+ org node | 既存(a,b) |

---

## 2. データモデル（datom スキーマ）

既存の `office.cljc` スキーマ（`:org/* :grant/* :doc/* :block/*`）に、協調レイヤを追加する。
**すべて datom**なので `datomicQ` で横断クエリでき、CommitDag に履歴が残る。

```clojure
;; --- repository（文書 or プロジェクト）---
:repo/id          {:db/valueType :string  :db/cardinality :one :db/unique :identity}
:repo/kind        {:db/valueType :keyword :db/cardinality :one}  ; :repo.kind/doc|sheet|slide|project
:repo/owner-org   {:db/valueType :ref     :db/cardinality :one}  ; org（既存）
:repo/default-ref {:db/valueType :string  :db/cardinality :one}  ; "refs/heads/main"
:repo/doc         {:db/valueType :ref     :db/cardinality :many} ; プロジェクトなら複数文書

;; --- ref（branch / tag）= kotoba-git :git.ref/* を流用 ---
:ref/name         {:db/valueType :string  :db/cardinality :one :db/unique :identity} ; "refs/heads/feature-x"
:ref/repo         {:db/valueType :ref     :db/cardinality :one}
:ref/kind         {:db/valueType :keyword :db/cardinality :one}  ; :ref.kind/branch|tag
:ref/commit       {:db/valueType :string  :db/cardinality :one}  ; CommitDag commit CID（HEAD）
:ref/protected    {:db/valueType :boolean :db/cardinality :one}  ; branch protection

;; --- issue ---
:issue/id         {:db/valueType :string  :db/cardinality :one :db/unique :identity}
:issue/repo       {:db/valueType :ref     :db/cardinality :one}
:issue/number     {:db/valueType :long    :db/cardinality :one}  ; repo 内連番
:issue/title      {:db/valueType :string  :db/cardinality :one}
:issue/body       {:db/valueType :string  :db/cardinality :one :db/encrypted true} ; 機密は signal:v1:
:issue/author     {:db/valueType :did     :db/cardinality :one}
:issue/state      {:db/valueType :keyword :db/cardinality :one}  ; :issue.state/open|closed
:issue/label      {:db/valueType :string  :db/cardinality :many}
:issue/anchor     {:db/valueType :string  :db/cardinality :many} ; 任意：要素 ocz1: に紐付く課題
:issue/created-at  {:db/valueType :long   :db/cardinality :one}

;; --- pull request ---
:pr/id            {:db/valueType :string  :db/cardinality :one :db/unique :identity}
:pr/repo          {:db/valueType :ref     :db/cardinality :one}
:pr/number        {:db/valueType :long    :db/cardinality :one}
:pr/title         {:db/valueType :string  :db/cardinality :one}
:pr/body          {:db/valueType :string  :db/cardinality :one}
:pr/author        {:db/valueType :did     :db/cardinality :one}
:pr/base-ref      {:db/valueType :string  :db/cardinality :one}  ; "refs/heads/main"
:pr/head-ref      {:db/valueType :string  :db/cardinality :one}  ; "refs/heads/feature-x"
:pr/base-commit   {:db/valueType :string  :db/cardinality :one}  ; 作成時 base CID（merge-base 計算用）
:pr/head-commit   {:db/valueType :string  :db/cardinality :one}  ; 現 head CID
:pr/merge-base    {:db/valueType :string  :db/cardinality :one}  ; 算出した共通祖先
:pr/state         {:db/valueType :keyword :db/cardinality :one}  ; :pr.state/open|merged|closed|draft
:pr/closes-issue  {:db/valueType :ref     :db/cardinality :many}
:pr/merged-commit {:db/valueType :string  :db/cardinality :one}  ; merge 後の 2-parent commit

;; --- review ---
:review/id        {:db/valueType :string  :db/cardinality :one :db/unique :identity}
:review/pr        {:db/valueType :ref     :db/cardinality :one}
:review/reviewer  {:db/valueType :did     :db/cardinality :one}
:review/state     {:db/valueType :keyword :db/cardinality :one}  ; :review.state/approved|changes-requested|commented
:review/at-commit {:db/valueType :string  :db/cardinality :one}  ; どの head に対するレビューか（stale 判定）
:review/vc        {:db/valueType :string  :db/cardinality :one}  ; W3C VC CID（署名付き承認, kotoba-vc）

;; --- comment（PR/Issue 共通。要素アンカー付き = GitHub の行コメント相当）---
:comment/id       {:db/valueType :string  :db/cardinality :one :db/unique :identity}
:comment/on       {:db/valueType :ref     :db/cardinality :one}  ; :pr/* or :issue/* or :review/*
:comment/author   {:db/valueType :did     :db/cardinality :one}
:comment/body     {:db/valueType :string  :db/cardinality :one :db/encrypted true}
:comment/anchor   {:db/valueType :string  :db/cardinality :one}  ; 安定 ID（ocz1:/cell ref/block CID）= 行番号の代替
:comment/side     {:db/valueType :keyword :db/cardinality :one}  ; :side/base|head（差分のどちら側か）
:comment/resolved {:db/valueType :boolean :db/cardinality :one}
:comment/created-at {:db/valueType :long  :db/cardinality :one}
```

**設計上の要点：**

- `:pr/*` `:review/*` 等の**メタデータは平文 datom**（検索・集計のため）。本文（`:issue/body`
  `:comment/body`）だけ `assertEncrypted` で `signal:v1:` 暗号化（doc a の方針を踏襲）。
- ref / commit は kotoba-git のスキーマ（`:git.ref/name` `:git.commit/parent` cardinality many）と
  **同形**にしてある → §9 で本物の git にミラーするとき変換不要。
- `:review/at-commit` で「承認後に head が進んだ」= **stale review** を `datomicQ` で検出できる
  （GitHub の "approved on outdated commit"）。

---

## 3. 意味差分（Semantic Diff）

行差分ではなく、**安定 ID をキーにした木の差分**。3 アプリで differ は共通インターフェース、
ノード抽出だけ文書種別で替える。

### 3.1 共通アルゴリズム

```
diff(base_commit, head_commit, repo):
  B = nodes(base_commit)   ; {stable-id -> node}（datomicQ で構造木を取得）
  H = nodes(head_commit)
  for id in keys(B) ∪ keys(H):
    cond
      id ∉ B            → :added    H[id]
      id ∉ H            → :removed  B[id]
      B[id] ≠ H[id]:
        order 変化のみ   → :moved   {from, to}      ; :block/order / :cell ref
        属性/本文変化    → :modified {attr-deltas}   ; per-attribute Δ
      else              → :unchanged
```

ノードの同一性 = **安定 ID**：

| アプリ | stable-id | 順序キー | ノード単位 |
|---|---|---|---|
| docs | block CID（content-addressed）or `ocz1:` | `:block/order`（fractional index） | 段落 / 見出し / リスト / 表セル |
| sheets | `Sheet!A1` セル参照（絶対） | 行列インデックス | セル（値 + 数式 + 書式） |
| slides | `ocz1:`（office-causal の shape ID） | slide index + z-order | スライド / シェイプ / テキストラン |

### 3.2 文書種別ごとの差分粒度

- **docs**：段落単位の add/remove/move + テキスト属性差分。テキスト本文は段落内で
  **文字単位 LCS**（差分表示用）にしてもよいが、マージ単位は段落（安定 ID）。
- **sheets**：セル単位。`B2: =A1*1.1 → =A1*1.2` は 1 セルの `:cell/formula` Δ。
  **数式の依存（derives-from）が office-causal にあるので「この変更が波及するセル」も差分に併記**できる
  （GitHub にない強み）。
- **slides**：シェイプ単位の add/remove/move（位置・サイズ含む）+ テキスト/塗り属性差分。
  視覚差分は svgraph で **before/after を SVG レンダ → オーバーレイ**（後述 §7.2）。

### 3.3 差分の表現

差分自体も datom 化（`:diff/*`）してキャッシュ可能。CommitDag は content-addressed なので
`blake3(base_cid ++ head_cid)` をキーに **materialized view** として保存（kotoba の MV-CID 機構を再利用）。

---

## 4. 3-way マージ

GitHub の merge = base（共通祖先）・ours・theirs の 3-way。GitOffice も同じだが**木の上で**行う。

```
merge(pr):
  M = merge_base = LCA(pr.base-commit, pr.head-commit)   ; CommitDag を遡る
  O = nodes(pr.base-commit)   ; ours   (base ブランチの現在)
  T = nodes(pr.head-commit)   ; theirs (PR ブランチ)
  Bm = nodes(M)               ; merge base
  result = {}
  for id in all-ids:
    o = O[id]; t = T[id]; b = Bm[id]
    cond
      o == t                         → keep o                 ; 同一
      o == b                         → take t                 ; theirs だけ変更
      t == b                         → take o                 ; ours だけ変更
      (o ≠ b) and (t ≠ b):
        属性レベルで非交差            → 属性ごとに merge        ; 例: ours が色, theirs が位置
        同一属性で衝突                → CONFLICT {id, attr, b, o, t}
  ; 順序（:block/order）の衝突は fractional index の性質上ほぼ起きない（挿入は中間値を取れる）
```

**マージが GitHub より素直になる点：**

- **fractional index**（`:block/order` を `"a0" < "a0V" < "a1"` の文字列で持つ）のおかげで、
  「両者が別位置にブロック挿入」は**衝突しない**（git のテキスト行 merge で頻発するやつが消える）。
- **属性レベル merge**：1 シェイプに対し ours が塗りを、theirs が位置を変えた → 自動マージ可能
  （行 merge ではファイル全体が衝突する）。
- セル：別セルへの編集は常に非衝突。同一セルの値衝突のみ CONFLICT。

**衝突の表現と解決 UI：**

- 衝突は `:conflict/*` datom（`{pr, anchor, attr, base, ours, theirs}`）として PR にぶら下げる。
- 解決 UI は「3 ペイン（base / main / PR）」を**その文書ビューア自身でレンダ**して、
  要素単位で ours/theirs/手動 を選ぶ（テキストの `<<<<<<<` マーカーは出さない）。
- 解決結果 → 新 commit（2 parent = base-commit, head-commit）→ `:ref/commit` を前進、
  `:pr/state :merged`、`:pr/merged-commit`。

> マージは CommitDag 上の **2-parent commit** を作る。kotoba の `:git.commit/parent`（cardinality many）と
> 同形なので、§9 の git ミラーでもそのまま merge commit になる。

---

## 5. ワークフロー（end-to-end）

```
1. Issue 起票
   datomicQ で repo の next issue number → :issue/* を transact → commit → (任意)sync
   要素に紐付けるなら :issue/anchor = ocz1:（「このスライドのこの主張が誤り」）

2. ブランチ作成
   :ref/* (kind=branch, commit = base の HEAD) を transact
   = HEAD commit の "別名ポインタ"。文書の実体はコピーしない（content-addressed 共有）

3. 編集
   既存 editor.cljs で retract+assert → commitToIdb（オフライン可）
   commit は branch ref を前進（:ref/commit 更新）。各編集が CommitDag に積まれる

4. PR 作成
   :pr/* (base-ref, head-ref, base-commit, head-commit) を transact
   merge-base = LCA を算出して :pr/merge-base に保存
   diff(base, head) を materialize（§3）→ PR ビューに表示

5. レビュー
   reviewer が差分ビューで要素アンカーにコメント（:comment/anchor = ocz1:/cell ref）
   office-causal CI（§8）が refuted な因果主張・MECE 違反を自動コメント
   承認 = :review/state :approved + W3C VC 署名（kotoba-vc, :review/vc）

6. マージ
   merge policy（§6）を満たすか datomicQ で判定（必須承認数・stale review・protected）
   3-way merge（§4）→ 衝突なければ 2-parent commit → base ref 前進
   :pr/state :merged, :issue closed（:pr/closes-issue 経由）

7. リリース（任意）
   :ref/* (kind=tag) + commitSigned → .pptx/.xlsx/.docx を export（§7.3）
```

すべて **datom transact + commit** で表現されるので、ローカルファースト（doc a）・
オフライン編集 → SW バックグラウンド同期（既存 syncq）・CACAO 同期（doc b）が**そのまま効く**。

---

## 6. 権限・ID・マージポリシー

doc a/b で実装済みの **org / grant（access DAG）/ CACAO / depth-2 委任 / passkey** を再利用。

- **著者性**：commit / review / comment の author = DID（passkey PRF 由来の Ed25519）。
- **書き込み権限**：branch への push = グラフへの `datom:transact` CACAO。
  org グラフへの member 書き込みは **depth-2 delegation**（実装済）で表現。
- **branch protection**：`:ref/protected true` のブランチへの直接 commit を deny し、PR 経由必須に。
  これは merge 実行時に `:grant/cap` と policy datom を `datomicQ` でチェックして強制。
- **必須レビュー（CODEOWNERS 相当）**：

```clojure
:policy/repo            {:db/valueType :ref :db/cardinality :one}
:policy/required-approvals {:db/valueType :long :db/cardinality :one}     ; 例 1
:policy/required-reviewer  {:db/valueType :did  :db/cardinality :many}    ; 特定 DID 必須
:policy/dismiss-stale      {:db/valueType :boolean :db/cardinality :one}  ; head 前進で承認失効
:policy/require-ci         {:db/valueType :keyword :db/cardinality :many} ; :ci/coverage :ci/no-refuted
```

merge gate（マージ可否）はクエリで判定：

```clojure
;; この PR の有効な承認数（stale を除外）
[:find (count ?r)
 :in $ ?pr ?head
 :where [?r :review/pr ?pr] [?r :review/state :review.state/approved]
        [?r :review/at-commit ?head]]   ; 現 head と一致する承認のみ
```

- **承認の改ざん耐性**：`:review/vc` に W3C VC（kotoba-vc、`verify_did_key_proof`）を持たせ、
  「誰がどの commit を承認したか」を**署名付きで**残す。CommitDag が content-addressed なので
  承認対象 commit の差し替えは不可能（CID が変わる）。

---

## 7. OOXML ラウンドトリップ（svgraph / office-causal）

### 7.1 Import（`.pptx/.xlsx/.docx` → repo）

```
drop file → office-causal analyze()  →  CausalGraph（ocz1: 安定 ID 付き）
          → svgraph buildSVGraph()    →  視覚 IR（slide/shape geometry）
          → datom 変換（doc c のスキーマ）→ transact → 初期 commit → :repo/* 作成
```

すべてブラウザ内（アップロードなし）。`ocz1:` がそのまま差分・コメントアンカーの安定 ID になる。

### 7.2 視覚差分（slides 専用の強み）

svgraph で base/head のスライドを **SVG レンダ**し、office-causal の `overlayCausal` /
`renderSlideCausalSvg` を使って **追加=緑 / 削除=赤 / 移動=矢印**のオーバーレイを出す。
レビュアーは「PowerPoint を 2 つ開いて見比べる」必要がない。

### 7.3 Export（repo → `.pptx/.xlsx/.docx`）

```
datom 構造木 → svgraph svgToPptx()         → 編集可能 PPTX（他社にない）
            → office-causal embedDataPart() → ocz/causal.jsonl 同梱（解析付き）
```

PowerPoint / Excel / Word で普通に開ける。タグ付きリリース（§5-7）で添付。

### 7.4 .pptx 直接 PR（外部コラボレータ向け）

GitHub の「diff のある .docx を PR」を真似る経路：誰かが `.ocz.pptx` をドロップ → import で
`ocz1:` 一致により**既存ノードに retract+assert**（idempotent, doc c）→ それが head-ref の新 commit
→ 自動的に PR の diff が出る。`ocz/causal.jsonl` が **append-only でほぼ git 差分**なのと相性が良い。

---

## 8. 文書 CI（GitHub Actions 相当）

merge gate に「文書の健全性チェック」を挟む。office-causal の解析を CI ステップ化：

| CI チェック | 実体 | gate 例 |
|---|---|---|
| coverage | svgraph coverage 診断（未対応要素＝往復で壊れる箇所） | 新規 coverage 低下を warn |
| no-refuted | office-causal で `:causal.status/refuted` な主張が増えていないか | 増えたら block |
| MECE | `mece()` 結果（網羅・排他の崩れ） | スコア低下を warn |
| so-what | `consult()`（結論の含意）を PR コメントに自動投稿 | 情報提供 |
| broken-ref | sheets の数式 derives-from に dangling 参照 | block |

これらは **WASM UDF / kotoba-runtime** で実行し（サーバ側）、結果を `:ci/*` datom 化して
PR に出す。`:policy/require-ci` で必須化。

> 純ブラウザでも `buildStructuralGraph()`（LLM 不要・決定的）と svgraph coverage は走る。
> LLM 必要なチェック（refuted/MECE）は WebGPU 端末 or サーバ UDF。

---

## 9. git 相互運用（kotoba-git）

「本物の GitHub / git クライアントと相互運用したい」要件のための**任意のミラー層**。

- 各リビジョンを `kotoba-git` で **git tree/blob/commit に投影**：
  - 文書構造木 → 決定論的な tree（例：`slides/3/shapes/<ocz1>.edn`、`sheets/Sheet1/B2.edn`）。
    blob 中身は **正準 EDN**（保存揺れのない安定シリアライズ）→ git でも意味のある行差分が出る。
  - GitOffice の `:ref/commit`（CommitDag CID）↔ `:git.commit`（SHA-1）を `:git/oid`↔`:git.object/cid` で橋渡し。
  - PR merge の 2-parent commit ↔ git の merge commit（`:git.commit/parent` many）。
- `kotoba-git` の `repo` export（loose objects + refs）で **`git clone` 可能なミラー**を出せる。
- 逆に外部 git からの push を取り込むときは blob(EDN) → datom に戻す（idempotent）。

> 位置づけ：**正準は datom（差分・マージはこちら）**。git 投影は「外部ツール互換・バックアップ・
> 監査用の決定論的スナップショット」。OOXML を git に入れない代わりに、安定 EDN を git に入れる。

---

## 10. アーキテクチャ層

```
┌─────────────────────────────────────────────────────────────┐
│ UI 層（CLJS / Hiccup, 既存 editor.cljs を拡張）              │
│  ・差分ビュー（docs=段落, sheets=セル, slides=SVGオーバーレイ）│
│  ・PR / Issue / Review パネル  ・3ペイン衝突解決             │
├─────────────────────────────────────────────────────────────┤
│ 協調ロジック層（CLJS .cljc, 新規 — office.cljc の隣）        │
│  ・differ（§3） ・3-way merger（§4） ・merge-base/LCA       │
│  ・pr/issue/review/comment 変換器（datom ⇄ モデル）         │
│  ・merge policy 判定（datomicQ） ・VC 署名（kotoba-vc）      │
├─────────────────────────────────────────────────────────────┤
│ 文書 IR 層（既存）                                           │
│  ・office.cljc（:doc/* :block/* :org/* :grant/*）           │
│  ・svgraph（SVG⇄PPTX）  ・office-causal（OOXML→因果, ocz1:） │
├─────────────────────────────────────────────────────────────┤
│ kotoba コア（既存・Rust/WASM）                               │
│  ・datom / CommitDag（履歴）  ・datomicQ（差分・gate クエリ）│
│  ・CACAO / depth-2 / passkey  ・kotoba-git（§9 ミラー）      │
│  ・IndexedDB/OPFS + SW 同期（ローカルファースト）            │
└─────────────────────────────────────────────────────────────┘
```

新規実装は**協調ロジック層と UI 層だけ**。下 2 層は doc a〜d で検証済み。

---

## 11. 段階的実装計画

| Phase | 内容 | 完了条件 |
|---|---|---|
| 0 ✅ | スキーマ確定（§2）+ **blob⇄要素粒度 datom 正規化**（§13 選択肢1）+ revision 橋渡し + pr/issue/review/comment ⇄ datom 変換器（`p1-gitoffice/gitoffice.cljc`） | **完了**: `bb test` → 13 tests / 40 assertions / 0 fail（round-trip + idempotent + stale-review 判定） |
| 1 ✅ | differ（§3, docs+sheets）+ CommitDag merge-base/LCA（`gitdiff.cljc`） | **完了**: `bb test` → +7 tests / 22 assertions。add/remove/move/modified（属性 delta 付）+ DAG LCA（merge commit / criss-cross / disjoint） |
| 2 ◐ | branch ref + PR 作成 + 差分ビュー（docs）。**正規化/差分/マージ/gate を kotoba CLJS + 既存アプリ edge に移植済** | **移植完了**: ① kotoba CLJS（`kotoba.gitoffice/gitdiff/gitmerge/gitpolicy` + `gitoffice-node` interop）`shadow-cljs release` **0 warnings** + bb 36 tests、ESM export（`storeDocumentBody`/`storeWorkbookGrid`/`evaluateMerge`）② 既存 docs/sheets アプリの edge adapter（`gitoffice_normalize.py`）pytest 12 + 既存回帰なし。PR 作成 UI / 差分ビューは残 |
| 3 ✅ | 3-way merger（§4）+ 衝突表現 + 解決 API（`gitmerge.cljc`）。解決 UI は別途 | **完了**: `bb test` → +8 tests / 25 assertions。属性レベル自動マージ / 同一属性・delete-modify 衝突検出 / **fractional index で並行挿入が非衝突**を実証 |
| 4 ◐ | レビュー（要素アンカーコメント + 承認 VC）+ **merge policy gate**（`gitpolicy.cljc`） | **gate ロジック完了**: `bb test` → +8 tests / 28 assertions（必須承認・stale 失効・必須レビュアー・required CI・land-pr）。UI と VC 署名は Phase 2 と併せて移植時 |
| 5 | sheets 差分/マージ（セル単位 + derives-from 波及表示） | セル衝突のみ CONFLICT、波及セルを併記 |
| 6 | slides 差分/マージ（svgraph SVG オーバーレイ視覚差分） | シェイプ add/remove/move を視覚表示・属性マージ |
| 7 | 文書 CI（§8）を merge gate に（coverage / no-refuted / MECE） | 必須 CI 失敗で merge block |
| 8 | git ミラー（§9, kotoba-git）+ OOXML 直接 PR（§7.4） | `git clone` 可能 / `.ocz.pptx` ドロップで PR 差分 |

推奨着手順：**Phase 0→1→2→3 を docs 一本で貫通**（最小の Issue→PR→Review→Merge ループを実証）→
sheets/slides に横展開 → CI / git ミラーは差別化として後乗せ。

---

## 12. 既知のリスク / 正直なギャップ

- **同時編集（同一ブランチ）は依然 last-write-wins**（doc a と同じ）。PR ワークフロー自体は
  ブランチ分離で並行作業を捌くが、1 ブランチ内のリアルタイム共同編集は別途 CRDT が要る。
  fractional index を入れてあるので将来の CRDT 移行は容易。
- **merge-base/LCA のコスト**：CommitDag を遡る。長い履歴ではキャッシュ（`:pr/merge-base` 保存）必須。
  CommitDag は content-addressed なので LCA 結果も安定キャッシュ可。
- **意味差分の同一性は安定 ID 依存**：office-causal `ocz1:` は FNV-1a（非暗号学的・衝突可能性）。
  論理キーに留め、物理同一性は内容ハッシュで補強（doc c の指摘どおり `:ocz/cid` 併記を推奨）。
- **OOXML 往復のカバレッジ**：svgraph は「生成オフィス図形の実用サブセット」。複雑な既存 .pptx の
  全要素は往復しない（coverage 診断で可視化済み）。往復対象外の部分は import 時に「不透明 blob」として
  保持し差分対象から外す設計（壊さない）。
- **slides 視覚差分の精度**：svgraph レンダはブラウザ近似。ピクセル厳密ではない（要素単位の構造差分が主、
  視覚オーバーレイは補助）。
- **文書 CI の LLM 依存**：refuted/MECE は Gemma/WebGPU。非対応端末はサーバ UDF か構造チェックのみに
  degrade（doc c と同じトレードオフ）。
- **スケール**：PR/Issue/comment が datom として増える。CommitDag 肥大化を避けるため、
  協調メタの commit は文書編集 commit と**別グラフ**（`:repo/meta-graph`）に分けることを検討
  （文書履歴とレビュー履歴の分離 = git の refs/notes 的）。

---

## 13. 既存実装とのギャップ（最重要・着手前に要確認）

調査時点（2026-06）で、**実際にデプロイされている** `docs/sheets/slides` は doc a の
CLJS ローカルファースト設計とは**別系統**で動いている：

| | 本書/doc a の前提 | 実デプロイ（`etzhayyim/root/60-apps/etzhayyim-project-{docs,sheets}`） |
|---|---|---|
| 文書実体 | **要素粒度の datom**（`:block/*` 木 + fractional index） | **文書まるごと 1 JSON blob**（`:doc/bodyJson` / `:sheet/gridJson`）+ 1 entity |
| 版管理 | CommitDag（content-addressed 履歴） | `:doc/revisionId` / `:sheet/revision`（ETag/If-Match の単調カウンタ） |
| フロント | CLJS + Hiccup | Svelte + SvelteKit |
| バックエンド | ブラウザ WASM `KotobaNode` 直叩き | Python FastAPI / LangGraph（Google Docs v1 / Sheets v4 / MS Graph 互換 API） |
| 正準ストア | kotoba datom（同じ） | kotoba datom graph `docs-v1` / `sheets-v1`（同じ） |
| 共同編集 | last-write-wins（未実装） | 未実装（revision ETag のみ） |

**含意：本書の意味差分・3-way マージは「文書 = 1 JSON blob」では成立しない**
（blob 全体が 1 つの不透明値 → 要素単位の add/move/属性マージができず、衝突が常に文書全体になる）。
GitHub 風 PR を載せる前提条件として、**body を要素粒度の datom に正規化する**必要がある。

3 つの選択肢：

1. **正規化（推奨）**：`:doc/bodyJson` → `:block/*` 木、`:sheet/gridJson` → `:cell/*`、
   slides → `ocz1:`/`:shape/*` に展開する変換器を入れる。Google/MS 互換 API はそのまま維持
   （edge で blob ⇄ datom 木 を相互変換）。本書の差分/マージがそのまま効く。
   **revisionId は CommitDag の seq/CID に橋渡し**（ETag の後方互換を保ちつつ履歴を content-addressed に）。
2. **粗粒度のまま**：blob 単位の revision を branch/PR に使い、マージは「全体 take ours/theirs」のみ。
   実装は軽いが**意味差分・属性マージ・要素アンカーコメントという差別化が全部消える** → GitHub 移植の価値が薄い。
3. **ハイブリッド**：保存は blob のまま、PR を開いた瞬間だけ blob を木に parse して差分/マージ → 結果を blob に戻す。
   1 への移行を急がず差別化を出せるが、parse の安定性（同じ blob → 同じ木 ID）に注意。

> **着手前の意思決定ポイント**：1（正規化）に倒すのが本書の設計と一致する。
> その場合 Phase 0 に「blob ⇄ 要素粒度 datom 変換器 + 既存 revisionId 互換ブリッジ」を含める。
> なお `etzhayyim/root` は operating-entity 側コードなので、変更可否は所有者確認が要る。

---

## 付録：差別化（なぜ GitHub for Office が GitHub の単純移植ではないか）

1. **意味差分**：行ではなく段落/セル/シェイプ単位。保存で壊れない（content-addressed 安定 ID）。
2. **属性レベル自動マージ**：同一要素への直交変更（色 vs 位置）が衝突しない。
3. **因果 CI**：office-causal が「この PR で論理破綻（refuted な主張・MECE 崩れ）が増えた」を検出。
   = **意思決定の構造をレビューできるオフィス**（doc c の差別化を協調に接続）。
4. **データ主権**：未ログイン・オフライン・E2E 暗号・端末から出ない（doc a/b）。GitHub と違い中央集権でない。
5. **ベンダーロックインなし**：いつでも編集可能 PPTX/XLSX/DOCX に書き戻せる（svgraph）。
6. **git 互換ミラー**：必要なら本物の git として clone でき、外部ツールと相互運用（kotoba-git）。
