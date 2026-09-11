---
name: docs-adr-authoring
description: 90-docs の ADR / datom catalog / ledger を書く・直す・引くときの正本。ADR は `.kotoba` の S 式 tx-data のみ（`.md` も `.edn` も新規に置かない）、`:adr/id` は slug 形、heredoc で書いた EDN/kotoba の `\"` と バッククォートと `(str …)` が静かに壊す形、横断 query（edn-query.cljk、裸文字列属性）、datom 面に載っている dataset 一覧、「文書は最新状態のみ・履歴は git」と append-only 例外、supersede の作法。「ADR を書く」「adr-new」「90-docs」「.kotoba の文書」「edn-query」「datoms.edn」「supersede」で発火。CLAUDE.md の docs/ADR 節から 2026-09-11 に切り出した正本（ADR-2609112300）。
---

# docs-adr-authoring — ADR は `.kotoba`、履歴は git

**CLAUDE.md の「docs / ADR は kotoba only + DataScript query」節はここへ委譲している。**
CLAUDE.md 側には形式・id・上書き規則の不変条件だけが残っており、コマンド・実測・罠は
この文書が正本。

---

# CLAUDE.md に 2026-09-11 まで残っていた本文（逐語、ADR-2609112300）

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-11、ADR-2609112300。AGENTS.md の
読み込み上限 31,457 字に合わせて CLAUDE.md を不変条件だけに絞った）。CLAUDE.md 側には
skill を読まなくても効く規則だけが残っている。ここが理由・実測・罠の正本。

## docs / ADR は kotoba only + DataScript query（2026-07-17、ADR-2607171600。形式は 2026-09-10 に edn→kotoba へ訂正）

- **`90-docs/adr/` の正本は `.kotoba` のみ。`.md` も `.edn` も新規に置かない。**
  各 ADR は S 式の tx-data
  `(vector (map (:db/id -1) (:adr/id ...) (:adr/title ...) (:adr/status ...) (:adr/body ...)))`。
  `.kotoba` のデータファイルは S 式なので `cljs.reader` がそのまま読む。
  入れ子 map/vector は `pr-str` した string blob（`manifest/edn-datomize.cljk` と同型）。
  ⚠ **この節は 2026-07-17 に `.edn` only として書かれ、tree に追い越された。**
  実測 2026-09-10: ADR 面は **2,884 件すべて `.kotoba`**。⚠ **その数時間後に `.edn` の ADR が 1 件着地した**
  （`2609101900-q9-rename-reverted-pending-admission.edn`）—— 下の `adr-new.cljs` の
  欠陥の実例である。**この規則は現状の記述ではなく方向であって、件数で読まない。**
  ⚠ **`90-docs/` 全体は kotoba only では*ない*。** datom catalog と ledger は `.edn` のまま
  **617 件**在り（business 165 / lake 77 / community-coverage 46 / maturity 42 / evidence 39 …）、
  `.md` も 88 件残っている。gate `docs-edn-check` は `.edn` と `.kotoba` の両方を受ける。
  **「ADR は kotoba」と「90-docs は kotoba」を混同しない** —— 後者を書けば 617 件が
  一夜で違反になる。
  ⚠ **`scripts/adr-new.cljk` は `.edn` と bare な数値 id を出す。** どちらもこの節と
  下の `:adr/id` slug 規則に反するので、使ったら拡張子と id を直してから commit する。
  ⚠ **`manifest/docs-edn-only.cljk` のヘッダは今も「正本は `*.edn`」と書いている。**
  移行ツールとしては生きているが、その一文は現在地ではない。
- **`.kotoba` / `.edn` 文書を heredoc で書いたら、reader を通してから commit する。`read-string`
  が throw しないことは無傷を意味しない。** shell heredoc の中の `\"` はファイル上で
  **バックスラッシュ 2 つ + 引用符**になり、EDN では「エスケープされたバックスラッシュ」+
  「文字列を閉じる引用符」と読まれる。そこで本文が終わり、続く語が**キーとして**読まれ、
  次の引用符から新しい文字列が始まる。**引用符の個数の偶奇が合えば map も vector も
  閉じるので、reader は何事もなく値を返す。**

  実測 2026-08-19〜20、**別々のセッションが 3 日で 4 文書**をこの形で壊した:

  | 文書 | 症状 |
  |---|---|
  | `2608190400` / `2608190600`（cloud-itonami-app） | 読めず。着地から closing まで誰も気づかず |
  | `2607211400-wave-2-…` | **読める**。`:scope` が 1,465 字 → 604 字、`commit-dag` と `\|quad-store` がキー |
  | `2608198700-amus-jvm-suite-…` | **読める**。heredoc の `\"` が 4 箇所 |

  後ろ 2 つが厄介で、**parse 検査は緑で通す**。検査は「キー位置に裸のシンボルが
  無いこと」で、2,350 文書に当てて偽陽性 0・真陽性 2。fleet gate は
  `docs-edn-check.cljs --strict-keys`（`root` と `cloud-itonami-app` で有効）。

- **この危険は EDN に限らない。「テキストを機械で書き換える」操作すべてが同じ形を持つ
  —— 変換は成功し、意味だけが静かに変わる。**（2026-09-06 に 2 つ新しい顔を踏んだ）

  | 顔 | 何が起きるか | 防ぎ方 |
  |---|---|---|
  | **unquoted heredoc の中のバッククォート** | shell が**コマンド置換として実行**し、その語がファイルから消える。残りは完全に妥当なコードで、テストは緑のまま | heredoc は必ず `<<'EOF'` と**引用符で閉じる**。変数展開が要るときだけ開き、その塊にバッククォートを入れない |
  | **一括正規表現の書き換えが docstring / コメントまで当たる** | 文字列の中にキーを差し込んで**その文字列を早期に閉じ**、以降がコードとして読まれる。壊れ方は当たった場所依存なので、動く例を見ても安心できない | 置換後に**必ず読み直す**（compile / reader / `kbb -M:test`）。`grep` で件数だけ数えて済ませない |
  | **データファイルに Clojure の *ソース* イディオムを書く**（2026-09-08） | `.edn` の値として `(str "…" "…")` と書くと、**`edn/read-string` は throw せず**その項目を `PersistentList` として返す。ファイルは読め、件数も合い、目視でも普通に見える —— **文字列を期待している下流だけが静かに壊れる**。EDN に評価は無い、が理由 | reader を通すだけでは足りない。**読んだ値の「型」を assert する**（`string?` / `number?`）。実測: 13 tissue の出典欄がこの形で、`edn/read-string` は 13 件すべてを clean に返していた |

  3 つとも「書けた」と「意図どおり書けた」が出力で区別できない。**書き換えたファイルは、
  書き換えた直後に読み返す** —— そして **reader が返した値の型まで見る**。
  「壊れていれば reader が throw する」は真ではない。

  「全キーが keyword」ではない —— それは 8 件を赤くし、うち 7 件は正当だった
  （`"p50"` `".cljs"` `"stripe.com"` `0 1 2 3`。EDN の map は文字列キーも整数キーも取る）。

- **`:adr/id` は slug 形 `adr-<番号>-<slug>` にする。bare な `ADR-<番号>` や
  `<番号>` を新規に使わない。** 番号だけの id は衝突する —— 並行セッションが同じ
  日時 prefix で採番するため、**08-16〜08-19 の 4 日で新規衝突が 7 件**出た。
  実測 2026-08-19: その 7 件を解消した 1 時間後に、同じ番号で 8 件目が生まれている。
  slug を含めれば同じ番号でも id は分かれ、`:adr/related` の参照先も一意に決まる
  （既存 1,362 件が既にこの形。bare は 353 / 65）。検査は
  `kbb --backend sci --classpath ".:scripts/nbb_compat" scripts/verify-adr-identity.cljk`、
  fleet gate は `root-adr-identity`。**既知の衝突 23 件は据え置きで、表を増やさない**
  —— 新しい衝突は fail させる。
- **横断 query**:
  `kbb --backend sci --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src" manifest/edn-query.cljk count`
  `kbb --backend sci --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src" manifest/edn-query.cljk q '[:find ?id :where [?e "adr/id" ?id] [?e "adr/status" "accepted"]]'`
  属性は datascript.js 向けに **裸文字列**（`"adr/id"`、コロン無し）。
- **この面は 90-docs だけではない（2026-07-25 拡張、ADR-2607252000）。** 企業データと
  fleet 状態も同じ面に載っており、`:company/lei` を結合キーに **repo を跨いで join
  できる**。出自は `"source/dataset"` で区別する（属性名に出自を埋め込むと結合キーが
  壊れるのでそうしない）。現在載っている dataset:
  `market-intel`（SEC EDGAR 財務。`orgs/gftdcojp/cloud-murakumo-market-intel`）/
  `cloud-itonami-lei`（法人実体 blueprint）/ `cloud-itonami-lei-tos`（ToS アーカイブ）/
  `fleet-db`・`fleet-db-remote`・`fleet-ci`（fleet 状態）/ `yabai-passive-dns` /
  `tadori-threat-intel` / `toshokan-patents` / `repo-maturity`・`itonami-fleet-audit` /
  **`repo-taxonomy`**（repo の 3 面分類。ADR-2607289600。`:repo/path` で repo-maturity と、
  `:company/lei` で market-intel / cloud-itonami-lei と join できる）/
  **`internet-accounts`**（公開アカウント・ディレクトリ。ADR-2608059100。
  `etzhayyim/global-accounts-datoms`）。
  ⚠ **`internet-accounts` は catalog(20 directory) + coverage(2) + service(790) だけで、
  account 行 174,592 件はこの面に載せない** —— account が join できる先は
  `:account/service-host` → `:service/domain` だけで、その join は service さえ在れば
  成立する。個人識別子を全 query の作業集合に常駐させない。account を引くなら
  repo 側の `adapters/read_only.clj`（公開 query のみ）を通す。
  `:service/domain` は yabai-passive-dns / tadori-threat-intel のドメイン文字列と join できる。
  **`:service/source` を見ずに service を数えない** —— `:self-reported`（NodeInfo）と
  `:observed`（PDS をアカウント側から数えたもの）は同じ列に見えて出所が違う。
  ```bash
  # 財務 × 法人実体 × ToS を 1 クエリで
  kbb --backend sci --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src" \
    manifest/edn-query.cljk q \
    '[:find ?legal ?juris ?rev ?url :where
      [?a "company/lei" ?lei] [?a "source/dataset" "market-intel"] [?a "company/revenue-usd" ?rev]
      [?b "company/lei" ?lei] [?b "company/legal-name" ?legal] [?b "company/jurisdiction" ?juris]
      [?c "company/lei" ?lei] [?c "tos/source-url" ?url]]'
  ```
  **ローダは shape 不一致を nil で握り潰す**（1 ファイルの破損で面全体を落とさないため）
  が、握り潰した分は必ず **stderr に WARNING で報告する**（`warn-skipped!`）。
  count が「全部載っている」ように読めてしまうのを防ぐため。新しい corpus を足す時も
  この報告を必ず付ける。実例: tos.journal.edn の一部が source 側の破損
  （ToS 本文の未エスケープ引用符でファイルが 1 個の巨大タプルに潰れる）で 0 entity。
- **schema**: `manifest/schema.edn`（自動生成、手編集禁止）。
- **検証**: `kbb --backend sci --classpath ".:scripts/nbb_compat" manifest/docs-edn-only.cljk verify`。
- **移行ツール**: `manifest/docs-edn-only.cljk`（`migrate` / `status` / `verify`）。
- multi-entity catalog（`*.datoms.edn`）は複数 entity のまま、query ローダが全 entity を読む。
- 新規 ADR は最初から `.kotoba` の S 式 tx-data で書く（`.md` も `.edn` も起こしてから変換しない）。
- **文書は「最新状態のみ」を表す。履歴は git に任せる**（オーナー判断 2026-07-25、
  ADR-2607257000）。`status accepted` の ADR であっても、決定が変わったり現在地が
  進んだりしたら **`:adr/body` や `:adr/status` をその場で書き換える**。同じ規則が
  `90-docs/` の md・`90-docs/task-graphs/*.datoms.edn`（`:task/status` を直接更新）・
  子リポの `docs/*.md` にも適用される。**append-only の「追記して既存行は触らない」
  運用はしない。** 何がいつ変わったかは `git log -p <file>` / `git blame` が持つ。
  - **旧方式は撤去済み**: `90-docs/adr-ledger/adr-ledger.edn` と
    `scripts/adr-ledger-append.cljk`、`90-docs/task-graphs/task-graph-ledger.edn` と
    `scripts/task-graph-ledger-append.cljs` は削除した。既存 76 件の ADR amendment は
    `scripts/fold-adr-ledger.cljk` で各 ADR の `:adr/body` 末尾「## 改訂履歴」節へ
    統合済み。**これらのスクリプトを再導入しない**。ADR-2607181900 の ledger 部分と
    ADR-2607173000 decision item 6、ADR-2607202800 の ledger 半分を supersede する。
  - **書き換えの作法**: 決定を反転させるときは古い記述を黙って消さず、`:adr/status` を
    `superseded` にして後継 ADR を `:adr/superseded-by` で指すか、本文に「いつ・なぜ
    変えたか」を1〜2文残す。読み手が現在地を1回で読めることが目的であって、
    経緯の抹消が目的ではない。
  - **実装スナップショットを言語にしない**: ある日の天井（emitter、backend、
    その日の切り方）を『こう書くもの』として standing に残していないか疑う。
    status の棚卸しは `adr-inventory`（中身の正しさは見ない）。中身の切り方が
    まだ適切かは `rule-kaizen`（ADR-2608261200、1 反復 = 1 finding）。
  - **例外（従来どおり append-only を維持する）**: `90-docs/business/canvas-ledger.edn`・
    `90-docs/design-quality/design-quality-ledger.edn`・`manifest/fleet-db.ledger.edn`。
    これらは「文書」ではなく**測定・イベント列**（時系列そのものが値）または**署名付き
    VCS プレーン**で、上書きすると時系列分析や quorum モデルが壊れる。

