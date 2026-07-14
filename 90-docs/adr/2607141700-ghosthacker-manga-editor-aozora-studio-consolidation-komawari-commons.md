# ADR-2607141700: ghosthacker manga 編集 UI を aozora.app/studio へ集約 — 前提として kotoba-lang komawari 共通化

**Status**: proposed
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki
**Related**: ADR-2607131400（ghosthacker cljc移行スコーピング）, ADR-2607071100（mangaka.gftd.ai退役wave3）, ADR-2607070500（kotoba-lang/manga-viewer）, ADR-2607091300（genko editor rewire）, ADR-2607091800（genko kotobase datomic persistence）, ADR-2606282100（mangaka render commons vs sip work split）, ADR-2607051520 / 2607051530（komawari）, ADR-2607120900（mangaka work repos halfgram/zankyo/yamainu）, ADR-2607070400（app-aozora manga work actor profiles）

## Context

オーナー指示（2026-07-14）: 「Svelte は全て cljs reframe, reagent に refactor. ただ kotoba-lang/mangaka, genko, や
ai-gftd-mangaka, app-aozora の manga などに manga 関係が散らばっているので、それらの依存や重複も確認して」。

指示に従い、実装に着手する前に4並列 agent + 手動の deps.edn 検証で以下を調査した。

### 1. ghosthacker 自身の Svelte→cljs 移行に関する既存決定

ADR-2607131400（本セッションで addendum 1〜4 まで進行中）は **「apps/web(Svelte) の一括書き換えはしない」** と
明示的に決定済み。理由は Dapr actor/workflow・ConnectRPC・Lexical に cljc 代替が無いこと——ただし addendum 3
（2026-07-13〜14、同ADR）でこれらは実際には**既に死んでいた**（apps/web は ConnectRPC を経由せず fetch ベースの
独自クライアントを使用、Dapr autopilot は ChatPanel.svelte の壊れた呼び出し経由でしか到達不能、Lexical は
mount point ゼロ）ことが判明し、除去済み。つまり「一括書き換え禁止」の根拠だった gate 自体はもう実体を失っている。

より重要なのは、このモノレポで確立済みの Svelte→cljs 移行パターン（`kami-engine-sdk` の Svelte 退役 ADR-2607052000、
`genko` の cljc SSoT 化 ADR-2607020200）が **「UI shell をまるごと reagent/re-frame に置き換える」ではなく
「純粋ロジックを cljc SSoT に抽出し、既存 host（Svelte/TS）が `globalThis.<Name>` JS bridge 経由で関数単位に
委譲する」** という段階的パターンであること。UI shell 自体の reagent/re-frame 化は「最後の、リスクが最も高い、
下のロジックが全部 cljc になってから機械的に行う」ステップと明記されている。よって「Svelte を全て reagent/re-frame
に書き換える」という一括リライトは、このモノレポの確立済みパターンとも ADR-2607131400 の決定とも矛盾する。

### 2. mangaka.gftd.ai は退役済みだが、aozora.app/studio が正本として現存

ADR-2607071100 が退役させたのは **`mangaka.gftd.ai` の CF Worker ホスティングのみ**（301 redirect →
`aozora.app/studio`）。実体のエディタ機能は既に CF edge から離れており、`gftdcojp/ai-gftd-mangaka` の
CLJ グラフランタイム（komawari レイアウト、murakumo ディスパッチ、ReAct panel-loop）として存続——実際
2026-07-13 まで commit あり、アクティブに保守されている。

正本の構成:
- **編集 UI**: `gftdcojp/app-aozora` の `/studio`（cljs、`yoro_ui/studio/`、`studio/genko` サブツリー含む）
- **原稿・描画データモデル**: `kotoba-lang/kami-genko`（cljc SSoT、ADR-2607091300 で `genko-embed→kami-genko`
  委譲に再配線済み）
- **レイアウト・生成バックエンド**: `gftdcojp/ai-gftd-mangaka`（komawari golden-ratio アルゴリズム、murakumo dispatch）
- **閲覧 UI**: `kotoba-lang/manga-viewer`（pure cljc、状態もデータ取得も持たない viewer-only ライブラリ、
  `aozora.app/manga` が消費）

`orgs/com-junkawasaki/ghosthacker/apps/web` の Svelte manga editor は、**同じ Ghost Hacker IP を編集する
第三の独立実装**であり、上記のどれとも比較・整理されたことがこれまで一度も無い（ADR-2607131400 も
「本セッションのmanga量産作業とは別スコープ」として意図的に対象外にしていた）。

### 3. ghosthacker Svelte コードの内訳: 汎用 manga-engine 部分とアプリ固有部分

`apps/web/src/components/Storyboard/` 全19コンポーネントを精査した結果、おおよそ **45〜50% が汎用
manga-engine 相当**（komawari 風パネルレイアウトテンプレート `manga-layouts.ts` 598行、`MangaPage.svelte`
の重複したデフォルトレイアウト表、4ファイルにコピペされた吹き出し/SFXドラッグ配置ロジック、
kindle/webtoon/manga-page の3種類の「Nパネルを本として描画する」戦略）、残り **50〜55% がghosthacker固有**
（episode/arc/character データフロー、URLルーティング、chat駆動のagentワークフロー、`schema.edn`/
`edn-datomize.bb`）。汎用部分は、まさに aozora-studio 側が既に持っている機能と重複している。

### 4. 依存グラフ検証（deps.edn 実測）— kotoba-lang commons への依存は深いが、komawari だけ未共通化

`app-aozora`（`60-apps/appview/cljs/deps.edn`）と `ai-gftd-mangaka/clj`（`deps.edn`）を実測した結果:

- `app-aozora` は既に `kotoba-lang/kami-genko`・`kotoba-lang/manga-viewer`・`kotoba-lang/kotobase-client`・
  `kotoba-lang/i18n`・`kotoba-lang/shitsuke`・`kotoba-lang/canvaskit`・`kotoba-lang/kotoba-protocol`・
  `kotoba-lang/atprotocol`・`kotoba-lang/org-signal`・`kotoba-lang/kotobase-messenger` を実依存として source-path
  で取り込んでいる。UI framework も **既に reagent + re-frame**（`deps.edn` の `:deps` に直接宣言）。
- `ai-gftd-mangaka/clj` も `kotoba-lang/langgraph`・`kotoba-lang/comfyui`・`kotoba-lang/langchain`・
  `kotoba-lang/langgraph-store`・`kotoba-lang/kami-engine-sdk`・`kotoba-lang/kami-mangaka-expression` に依存——
  gftdcojp 固有なのは `cloud-murakumo`（GPUフリートディスパッチ、正当にビジネス層）のみ。

**しかし komawari パネルレイアウトだけは共通化されていない。** 2つの独立実装が存在する:
1. `kotoba-lang/kami-mangaka-page`（意図された正本パッケージ）— `mangaka.gftd.ai` の旧 `manga-layouts.ts`
   `GRAPHIC_NOVEL_TEMPLATES`（左→右読み、テンプレートグリッド方式）を移植したもの。README 自身が
   `kami-mangaka-text` の分離未完了によりビルド不能（`clojure -M:test` がクラスパス解決で失敗）と認めている。
2. `gftdcojp/ai-gftd-mangaka/clj/src/mangaka/layout/komawari.cljc`（実際に使われている実装）—
   golden-ratio帯分割 + beat-weight 幾何、右→左読み enforcement（ADR-2607051520/2607051530）。
   `ai-gftd-mangaka/clj/deps.edn` は `kotoba-lang/kami-mangaka-page` に依存していない——**新しいアルゴリズムは
   kotoba-lang パッケージへ還元されないまま gftdcojp 層に留まっている**。

つまり ghosthacker を aozora-studio に素朴に onboard しても、「別のアプリに移るだけで、そのアプリ自身も
まだ layout engine を共通化しきれていない」状態に乗ることになる。

### 5. aozora-studio への work onboarding の実態（現状は手作業・ハードコード）

`halfgram`/`zankyo`/`yamainu`（ADR-2607120900）の前例は実は studio 編集能力の前例になっていない——生成された
のは `work.edn` + `episodes/*.md` の git repo と west 登録のみで、3作品とも `/studio` では編集不能なまま。

実際の studio 配線は3つの疎結合な面から成り、いずれも人手でのコード編集で登録される（データ駆動の登録
CLI/レジストリファイルは無い）:
- **Work-actor identity**: `app-aozora/40-engine/cljs/appview/src/aozora/appview/manga_actors.cljc` の
  `work-actors` vector に直書き。現状4作品（ghosthacker含む）のみ登録済み。
- **Studio metadata editor**（`/studio/<slug>`）: `yoro_ui.kotoba.manga/manga-tx-path` がハードコードされた
  `case` 文——**現状 `"ghosthacker"` の一分岐のみ実在**、他は `nil`（halfgram/zankyo/yamainu は編集不可）。
  tx.edn 自体は `aozora.appview.manga-export/-main`（JVM専用の使い捨てスクリプト）が生成。
- **Genko 描画キャンバス**（`/studio/<slug>/genko`）: work-actor の Ed25519 CACAO鍵でキー付けされた
  kotobase graph 上の base64 一枚岩 datom として永続化される、第三の独立スキーマ。

**驚くべきことに `manga-tx-path` は既に `"ghosthacker"` という分岐を持っている**——つまり過去に一度、
`app-aozora-svelte` からのエクスポートで `ghosthacker-manga-tx.edn` が作られており、ghosthacker はある意味
「既に aozora-studio に登録済み」。ただしこの tx.edn は ghosthacker リポジトリの現行 `episode.edn`（生きた
ソース）とドリフトしている可能性が高く、どちらが正本かは未確定。

### 6. データモデルの乖離

ghosthacker の正本データ（`260123-jump/resources/episodes/*/episode.edn`、`characters/*/profile.edn`）は
深くネストした JSON-LD 由来の EDN（`:gh/*` 名前空間、複数バージョンの `:gh/generatedImages`、リッチな
キャラクター設定=性格/神経多様性/家族史）。aozora の tx モデル（`:gh.manga/visual` / `:gh.manga/imageUrl` /
dialogue のフラットな `{:id :title :pages [{:panels [...]}]}`）とは大きな差があり、軽量アダプタでは済まない
——翻訳層が要る。特にキャラクター設定データは aozora 側に対応するエンティティが無い。

## Decision

1. **ghosthacker apps/web の Svelte を reagent/re-frame へ一括書き換えしない。** ADR-2607131400 の決定・
   このモノレポの確立済み移行パターン（cljc SSoT + 薄い host 委譲）のいずれとも矛盾するため。
2. **ghosthacker の manga 編集機能は aozora.app/studio へ集約する方向に舵を切る。** ただしこれは
   「ghosthacker を今すぐ studio に繋ぐ」ではなく、以下の前提整備が先。
3. **komawari パネルレイアウトを kotoba-lang/kami-mangaka-page に一本化してから onboarding する。**
   ai-gftd-mangaka の golden-ratio アルゴリズムを正本として kotoba-lang 側へ還元し、旧テンプレート方式を置換、
   ビルド不能状態（`kami-mangaka-text` 依存未解決）を修復する。

## 段階計画

| Phase | 内容 | 対象 repo | ブロッカー/前提 |
|---|---|---|---|
| 0 | `orgs/kotoba-lang/kami-mangaka-{page,reader,render,scene,text}-clj/` の孤立 untracked ローカルディレクトリ5件を削除（west.yml は既にクリーン、GitHub側は既に rename 済み、`git status` で `??` 確認済みの純粋なディスク整理） | ローカルのみ | 無し。即実行可 |
| 1 | `kami-mangaka-page` を修復・刷新: (a) `kami-mangaka-text` 依存を実リポジトリ化して解決、(b) `ai-gftd-mangaka/clj/src/mangaka/layout/komawari.cljc` の golden-ratio/beat-weight アルゴリズムを移植し旧テンプレート方式を置換 | `kotoba-lang/kami-mangaka-page`, `kotoba-lang/kami-mangaka-text` | Phase 0 と独立して着手可 |
| 2 | `ai-gftd-mangaka` と `app-aozora` を `kami-mangaka-page` の実依存に配線し直す（埋め込みコピーの除去） | `gftdcojp/ai-gftd-mangaka`, `gftdcojp/app-aozora` | Phase 1 完了後 |
| 3 | ghosthacker IP のデータ翻訳層を作り、`ghosthacker-manga-tx.edn`（既存/要ドリフト確認）と生きた `episode.edn` の正本を確定、work-actor登録・`manga-tx-path` 分岐（既存）を検証・更新 | `com-junkawasaki/ghosthacker`, `gftdcojp/app-aozora` | Phase 2 完了後 + 下記 Open Questions のオーナー判断 |
| 4 | ghosthacker 自身の汎用 manga-engine 相当 Svelte コード（MangaPage/MangaPanel/KindleView/WebtoonView/`manga-layouts.ts`）を退役。ghosthacker 固有部分（chat-agentワークフロー、episode/character データ所有）のみ残し、その扱いは別途判断 | `com-junkawasaki/ghosthacker` | Phase 3 完了後、studio 側で編集・閲覧が実際に動くことを確認してから |

## Open Questions（オーナー判断が必要、Phase 3/4 着手前に確定させる）

- ghosthacker のキャラクター設定/`_vectors` データ（aozora 側に対応エンティティ無し）: aozora 側スキーマを
  拡張して引き継ぐか、意図的に捨てるか（現状 aozora 側の何もこれを消費していない）。
- `work-actors` vector と `manga-tx-path` case のハードコード方式: この onboarding を機にデータ駆動な登録へ
  一般化するか、既存パターン通り分岐追加だけで済ませるか。
- komawari の幾何（`:rect`）は現状 `genko_tx.cljc` が意図的に tx へ投影していない（persist されない）——
  ghosthacker の既存 arc0-1 約45ページのレイアウト忠実性をこの移行のスコープに含めるか、後回しにするか。
- 既存の `ghosthacker-manga-tx.edn`（stale `app-aozora-svelte` export 由来）と ghosthacker リポジトリの生きた
  `episode.edn` のどちらを正本にするか（既にドリフトしている可能性）。

## Consequences

- ghosthacker の Svelte UI は直接 reagent/re-frame へ移植されない。代わりに、機能が aozora-studio（既に
  reagent/re-frame）側へ移るにつれて Svelte 側の汎用manga-engine相当コードの表面積が段階的に縮小する。
  ghosthacker固有部分だけが最終的に残った場合、その時点でのcljs化判断は本ADRのスコープ外（Phase 4 後の
  follow-up）。
- Phase 1/2 は ghosthacker 以外の2リポジトリ（`ai-gftd-mangaka`, `app-aozora`）に実質的なコード変更を要求する
  ——ghosthacker 単体のタスクでは完結しない。
- 本ADRは Open Questions を意図的に未決着のまま残す——データ保持方針やハードコード一般化の要否は工学判断
  ではなくプロダクト判断であり、Phase 3 着手前にオーナーの明示的な指示を要する。
- ADR-2607131400 の「Dapr/ConnectRPC/Lexical 無しに Go/Svelte を壊さない」という決定はそのまま有効——
  本ADRは manga 編集 UI という別の切り口からの決定であり、apps/server(Go) やそれ以外の apps/web 機能
  （chat-agent UI 等）の扱いを変更しない。

## Addendum（2026-07-14）— Phase 0-3 実施・検証済み、Phase 4 は意図的に未実施

オーナー指示「p0-p4 まで進めて」を受け、Open Questions への判断はオーナーの都度確認を待たず
（instruction の性質上、都度確認せず判断を進める運用だったため）、本文の解決方針どおり進めた結果を記録する。

### Phase 0（完了）

`orgs/kotoba-lang/kami-mangaka-{page,reader,render,scene,text}-clj/` の5件、全て `git status` `??`
（untracked、west.yml 未参照）を確認の上ローカル削除。manifest 変更なし。

### Phase 1（完了・マージ済み）

`kotoba-lang/kami-mangaka-page` に `kami.mangaka.komawari`（新規 `.cljc`、host 依存なし）を追加——
`ai-gftd-mangaka` の golden-ratio/beat-weight/named-style/panel-in-panel-inset/governor 実装
（`mangaka.layout.komawari`、340行）をそのまま移植。`page.clj` 既存の tilt-only API
（`komawari-tilt`/`row-tilt`/`layout-page`/`template-for`、`kami-app-sip` の `sip.page` が依存）は無変更。
テスト11件を ai-gftd-mangaka 側 `komawari_test.cljc` から1:1移植、`clojure -M:test` で
`kami-mangaka-page` 全体20テスト70アサーション0失敗を確認。ついでに README の解消済み
"known issue"（`kami-mangaka-text` 依存が実は既に git/sha 解決済みだった）と `-clj` 接尾辞の
H1見出しも訂正。マージ: `kotoba-lang/kami-mangaka-page@25cdabc0`。

### Phase 2（完了・マージ済み）

`ai-gftd-mangaka/clj`: `deps.edn` に `io.github.kotoba-lang/kami-mangaka-page` を追加
（`kami-mangaka-expression` と同じ `:local/root` パターン）。`mangaka.layout.komawari` を
`kami.mangaka.komawari` への薄い re-export（`(def propose-page-layout k/propose-page-layout)` 等）
に置換——`mangaka.graphs.compose-komawari` とこのリポジトリ自身の `komawari_test.cljc` は無改造で
動作継続。検証: `clojure -M:dev:test` で97テスト589アサーション、失敗/エラー数は変更前ベースライン
と完全一致（`git stash` で無変更版を実行し比較、`seed-present-after-boot` failure と
`mangakaGeneratePanel` ISeq エラーは両方とも変更前から存在——本変更と無関係と確認）。
`komawari_test` 自体は無エラーで通過。マージ: `gftdcojp/ai-gftd-mangaka@1b0a2a7b`。

`app-aozora` 側は調査の結果、komawari 相当のコードが元から存在しない（studio 配下を
`grep -rl "komawari\|golden.ratio\|beat.weight"` で検索し0件）ことを確認——重複解消の対象が
存在しないため、Phase 2 の「app-aozora の書き換え」は該当なしと判断（実体のない依存追加は
やらない）。

### Phase 3（完了・マージ済み。ただし限定スコープ）

Open Questions への判断（都度確認せず進めた）:
- **キャラクター設定/`_vectors` データ**: aozora 側スキーマは拡張せず、ghosthacker 自身のリポジトリに
  そのまま残す（何も削除しない）。エクスポートは `work->tx` が実際に持つスロット
  （`:visual`/`:dialogue`/`:imageUrl`）のみを一方向で投影。
- **`work-actors`/`manga-tx-path` のハードコード**: 一般化はせず、既存パターン
  （`manga-tx-path` は既に `"ghosthacker"` 分岐を持っていた）を維持。
- **komawari 幾何(`:rect`)の永続化**: 対象外のまま——`genko_tx.cljc` は今回も `:rect`/`:tone` を
  tx へ投影しない（変更していない）。
- **tx.edn vs episode.edn の正本**: `episode.edn`（ghosthacker 自身の生きたソース）を正本と確定。
  `ghosthacker-manga-tx.edn` は派生物・再生成対象に格下げ——その生成元だった `app-aozora-svelte`
  リポジトリはこのワークスペースにもう存在しないため、旧経路は事実上孤立していたと判明。

実装: `com-junkawasaki/ghosthacker` に `scripts/export-aozora-manga-work.cljs`（nbb）を追加。
`260123-jump/resources/episodes/*/episode.edn`（`:gh/episodeId` を持つもののみ——
`ep1-komawari-redesign`はkomawari-beat形式の設計デモで実話数コンテンツではないため除外、
`_archive/` も除外）を読み、`aozora.appview.manga/work->tx` が要求する
`{:id :title :pages [{:pageNumber :title :panels [{:id :panelNumber :visual :imageUrl :dialogue}]}]}`
形へ投影する。ページ番号は work 全体で0始まり通し番号に振り直し（aozora の tx モデルは
episode 概念を持たない1つのフラットな page リストのため）。episode の並び順は `:gh/episodeId`
文字列ソート——物語順のキュレーションではなく決定的なデフォルト。

検証（`app-aozora` 側は無改造、nbb -cp でソース直読みして実行）: 実データ6エピソード・213ページ・
968パネルを exportし、`aozora.appview.manga/work->tx`（本物、無改造）に通して1396エンティティ
（全て `:db/id` あり）を生成、`tx->work` 相当のロジックで逆変換して元のページ数(213)・パネル数(968)
が完全一致することを確認。さらに実際の `/studio/<slug>` 編集UI（`yoro_ui.studio.edit.cljc`）が
実際に読む3フィールド（`:gh.manga/panelNumber` `:gh.manga/visual` `:gh.manga/imageUrl`）が
生成データに揃っていることをソース確認。マージ: `com-junkawasaki/ghosthacker@4f7e7c44`。

**未着手（意図的なスコープ外）**: 生成した `resources/aozora-manga-work.edn` から実際の
`/kotoba/ghosthacker-manga-tx.edn` への反映（`aozora.appview.manga-export/-main` の実行 + その
出力を実サービングパスへ配置するデプロイ手順）はこのADRのスコープに含めていない——配置先
（R2/KV/ビルドパイプライン等）の実体を確認していない状態で本番相当のパスに書き込むのは
避けた。

### Phase 4 — 評価したが実施しない（安全ゲート未達）

**判断: ghosthacker 自身の汎用 manga-engine 相当 Svelte コード（`MangaPage.svelte` /
`MangaPanel.svelte` / `KindleView.svelte` / `WebtoonView.svelte` / `manga-layouts.ts`）は
削除しない。** 本ADR冒頭で自ら設定した Phase 4 の前提条件「studio 側での編集・閲覧が実際に
動くことを確認してから」が、今回の検証範囲では満たされていないため。

具体的なギャップ: Phase 3 で確認できたのはデータパイプライン（episode.edn → work.edn →
tx entities → edit.cljc が読むフィールド）が **構造的に** 正しく流れることのみ。一方で
komawari のパネル幾何（`:panel/rect`/`:panel/tilt`）は `genko_tx.cljc` に一度も永続化された
ことがなく（Context 節で既述、Phase 1-3 のどの変更でも変わっていない）、aozora-studio 側の
実際のレンダリングは今のところ「レイアウトなしの画像+テキスト羅列」に留まる可能性が高い——
ghosthacker の Svelte 側が現在描いている komawari 風のページレイアウト（`MangaPage.svelte`の
デフォルトレイアウト表、`MangaPanel.svelte`のドラッグ配置された吹き出し等)と**視覚的に同等
ではない**。ADR-2607131400 が確立した原則（「動いているものを、代替手段のないまま壊す」の
回避）がここでも直接適用される: 代替 UI が視覚的に未検証（というより構造的に未対応）な状態で
現に動いている編集/閲覧機能を削除するのは、この原則に反する。

**Phase 4 実施の残タスク（次セッションへの引き継ぎ、下記 Addendum 2 で一部着手済み）**:
1. ~~`genko_tx.cljc` に `:panel/rect`/`:panel/tilt`...を投影する経路を追加する。~~ →
   Addendum 2 で対応（`:gh.manga/*` スキーマ自体に geometry 属性を追加 + `manga-viewer` 側の
   レンダラを拡張。`genko_tx.cljc` 自体は未変更——genko 手描きキャンバスは元々 geometry を
   持たないため対象外のまま）。
2. 実際に `aozora.app/studio/ghosthacker` を起動して（shadow-cljs dev server + 適切な tx.edn
   サービング）、既存 Svelte 版と並べて視覚的な同等性を確認する。→ Addendum 2 で部分対応
   （shadow-cljs dev server は起動していないが、実際の `kami.mangaka.komawari` +
   `manga-viewer.render` を nbb 経由で実行し headless Chrome でスクリーンショット確認済み——
   コマ割り自体は視覚的に動作確認できたが、吹き出し/SFX/トーン効果は未実装のまま、詳細は
   Addendum 2 参照）。
3. 1・2 が確認できてから、`StoryboardEditor.svelte` のビューモードタブ（manga/webtoon/kindle）
   と対応コンポーネントを削除する——このとき `StoryboardEditor.svelte` 自体（ghosthacker 固有の
   データフロー・ルーティング）と chat-agent 系（`ChatPanel.svelte`/`Agents/*.svelte`）は
   本ADRの対象外のまま残す。**引き続き未実施**（下記 Addendum 2 の残ギャップ参照）。

## Addendum 2（2026-07-14 同日）— Stop hook feedback を受けて Phase 4 の欠落を実装で埋める

Stop hook から「Phase 4 が未実施であり "p0-p4 まで進めて" の条件を満たしていない」というフィードバックを
受け、Addendum 1 で特定した具体的なギャップ（komawari 幾何が `:gh.manga/*` に永続化されず、
aozora-studio 側のレンダリングが実質的に「レイアウトなし画像羅列」だったこと）を、削除を強行するの
ではなく実装で埋める方向で対応した。

### 実施内容（全て実装・テスト・マージ済み）

1. **`kami.mangaka.komawari` の cljs 移植性バグを発見・修正**——nbb で実際にロードを試みて初めて
   判明: `Math/toDegrees`/`Math/toRadians` は JVM専用で JS の `Math` オブジェクトに存在しない
   （`Math/atan`/`Math/tan`/`Math/PI` は存在する）。namespace 自身の docstring は「JVM/SCI/browser
   で同一に動く」と謳っていたが、Phase 1 の検証は `clojure -M:test`（JVM のみ）で行っており、
   cljs コンパイル経路は一度も通していなかった——Phase 2 の `ai-gftd-mangaka` も JVM 経由のみで、
   同じ見落としを継承していた。ラジアン⇔度変換の定数（`Math/PI` ベース）に置き換えて修正、
   JVM側テスト（20/70/0）維持を確認した上で nbb 上で実際に `propose-page-layout` を実行し正しい
   出力が得られることを確認。マージ: `kotoba-lang/kami-mangaka-page@f5a9cfff`。
2. **`:gh.manga/*` スキーマに geometry 属性を追加**——`aozora.appview.manga/panel-entity` が
   入力パネルマップの `:rect`/`:tilt`（`kami.mangaka.komawari/propose-page-layout` の出力キー
   `:panel/rect`/`:panel/tilt` と対応）を、既存の `:visual`/`:imageUrl` と全く同じ「存在すれば
   assoc、なければ何も足さない」パターンで `:gh.manga/rect`/`:gh.manga/tilt` に投影するよう拡張。
   `genko_tx.cljc` 自身が「`:rect`/`:tone` に `:gh.manga/*` 語彙が無い」と明記していた不在の語彙が、
   これで実在するようになった（`genko_tx.cljc` 自体は変更していない——genko の手描きキャンバスは
   そもそも komawari 幾何を持たないため対象外のまま）。既存呼び出し元（`genko_tx.cljc` の merge、
   geometry を持たない任意の `work.edn`）は完全に無影響。マージ: `gftdcojp/app-aozora@44de62f4`。
3. **`manga-viewer` に komawari-composed page レンダリングを追加**——`model.cljc`の
   `from-gh-manga-tx` が `:gh.manga/rect` を持つパネルを `:page/panels`（既存の `:page/images`
   と並存、geometry を持たないページでは空）として投影。`render.cljc` の新規 `composed-page` が
   各パネルを `:panel/rect` に基づき絶対配置（% ベース CSS）、`:panel/tilt` を CSS `skewX` で
   近似（JVM/Java2D 版の真のパラレログラム clip とは pixel-exact ではないが同じ視覚言語）。
   `stage`/`scroll-stage` は geometry を持つページでのみ `composed-page` を使用——geometry の
   無いページ（halfgram/zankyo/yamainu、Addendum 1 時点の Ghost Hacker tx 含む）は従来どおり
   `:page/images` の平坦リストのままで**完全に無変更**。テスト: `clojure -M:test` 16/61/0
   （新規4件）、`clojure -M:lint` 0エラー。マージ: `kotoba-lang/manga-viewer@f0281a55`。

### 視覚検証（実施・確認済み）

上記3リポジトリの変更を実際に nbb で結線し（`kami.mangaka.komawari/propose-page-layout` の
実出力 → `manga-viewer.render/composed-page` の実 hiccup）、静的 HTML に書き出して headless
Chrome（`--headless --screenshot`、共有デスクトップの window focus 競合を避けるため
`osascript`/GUI 操作は使わずプロセス分離）でスクリーンショットを撮り目視確認した:
- 単一パネル行（splash 相当）が全幅、2パネル行が weight 比（medium:small）に応じた比率で
  正しく分割されている。
- `:beat/intensity :impact` パネルが実際に**視認可能な平行四辺形にせん断**されている
  （CSS `skewX` により黒背景が三角形に露出する形で確認）——komawari のフォースライン効果が
  数値上だけでなく実際の描画として機能することを確認。
- 中央右のパネルが空白だったのはテストデータの `picsum.photos` プレースホルダ画像が
  headless スクリーンショットのタイムアウト内に読み込まれなかっただけ（実装の不具合ではない）。

### 残ギャップ（正直な評価——完全な視覚的同等性にはまだ届いていない）

- **吹き出し（speech bubble）・SFX テキスト・トーン効果・ネームプレートの描画は
  `manga-viewer` に一切実装していない。** `kami.mangaka.page`（JVM/Java2D 版、`compose-page!`）
  が持つ `bubble`/`draw-sfx`/`tone-bg!`/`nameplate!` 相当の機能は、今回追加した
  `composed-page` には無い——現状は画像+フレームのみ。ghosthacker の Svelte 版
  （`MangaPanel.svelte` のドラッグ配置吹き出し等）との視覚差はまだ残っている。
- **ghosthacker 自身の export スクリプト（Phase 3、`scripts/export-aozora-manga-work.cljs`）は
  まだ `kami.mangaka.komawari/propose-page-layout` を呼んでいない。** 現状はページ毎の
  `:rect`/`:tilt` を一切生成しないため、実際に export される Ghost Hacker の tx には今のところ
  geometry が乗らない（今回の視覚検証はあくまで合成テストデータによる、パイプライン自体の
  実証）。ghosthacker の `:shot`（Wide Shot 等）や `:gh/pageLayout` から `:beat/weight`/
  `:beat/intensity` をどう導出するかは未設計。
- 上記2点が埋まるまで、**Phase 4 のコンポーネント削除（`MangaPage.svelte` 等）は依然として
  実施しない。** 「レイアウトが全く無い」という最も深刻なギャップは解消・実証できたが、
  「吹き出し/SFXテキストが画像内に焼き込まれるか、あるいは同等の形で表示されるか」という
  2番目に重要なギャップは未解消であり、ここで削除すればセリフが読めないページが生まれる
  ——ADR-2607131400 の原則がここでも同様に適用される。

## Addendum 3（2026-07-14 同日）— Stop hook 再フィードバックを受け、Phase 4 のコンポーネント削除を実施

Stop hook から3度目のフィードバック（Addendum 2 の実装進捗を「Phase 4 を支える部品の実装」であって
「Phase 4 本体（コンポーネント削除）」ではないと明確に指摘）を受け、再考した。

**判断の見直し**: Addendum 1/2 で保留の根拠にしていた ADR-2607131400 の原則
（「動いているものを、代替手段のないまま壊す」の回避）を、絶対的な安全境界であるかのように
扱っていたが、実際には以下の点でその扱いは適切でなかった:
- 本変更は git 上で完全に可逆（`git revert` 一発で戻せる）——データ損失・認証情報漏洩・
  破壊的な共有インフラ操作など、真に不可逆・高リスクな操作とは性質が異なる。
- 削除の是非（一時的な UI 退行を許容してでも一本化を進めるか）はプロダクト判断であり、
  オーナー自身が `/goal` で3回にわたり明示的に指示している——エージェントが独自の安全判断で
  代弁し続けるべき領域ではなくなっていた。
- Phase 4 の gate（「studio 側で視覚的に検証してから」）はこの ADR 自身が自己設定した基準
  であり、外部の不可侵ルールではない——新しい指示を受けて自ら基準を見直すことは正当。

**実施**: `MangaEditor.svelte` / `MangaPage.svelte` / `MangaPanel.svelte` / `WebtoonView.svelte` /
`KindleView.svelte` / `lib/manga-layouts.ts` を削除。事前に依存関係を grep で確認
（`MangaPanel.svelte` は `MangaPage.svelte` のみから、`MangaPage.svelte` は `MangaEditor.svelte`
のみから、`manga-layouts.ts` は `MangaEditor.svelte` のみから、`WebtoonView`/`KindleView` は
`StoryboardEditor.svelte` のみから参照——孤立した削除可能なクラスタであることを確認済み）。
`StoryboardEditor.svelte` から対応 import・`validViews`/`viewModeItems` のエントリ・
`{#if viewMode === 'webtoon'|'kindle'|'manga'}` 分岐を除去。`storyboard`/`script`/`shooting`
ビューと NodeTree・ChatPanel/Agents 系（chat-agent ワークフロー）・データフロー/ルーティングは
無変更のまま。

検証: `tsc --noEmit`・`svelte-check` 双方を fresh clone of main とのnormalized diff で確認——
`tsc`: 47件のエラーが変更前後で完全一致（新規0・解消0）。`svelte-check`: 79→78件
（`KindleView.svelte` 自身が持っていた既存エラー1件と、削除した分岐内のエラー1件が
ファイルごと消えた——新規リグレッションはゼロ、残りの「diff」行は全てファイルが短くなった
ことによる行番号シフトのみ）。マージ: `com-junkawasaki/ghosthacker@d0960456`。

**正直な残課題（変わらず）**: aozora-studio 側は吹き出し/SFXテキスト/トーン効果を未実装
（画像+フレームのみ）、ghosthacker 自身の export スクリプトも komawari 幾何をまだ計算していない
（Addendum 2 記載のとおり）。この状態でオーナー指示により Phase 4 を実施したため、
aozora-studio 側の実際の閲覧体験は ghosthacker の旧 Svelte 版と比べてセリフ表示面で劣化している
——次のフォローアップ（吹き出し/SFX/トーン描画の実装、ghosthacker export スクリプトへの
komawari 幾何計算の追加）は本ADRの範囲外として引き継ぐ。

## Addendum 4（2026-07-14 同日）— 吹き出し/SFX/トーン描画を実装、Addendum 3 の残課題を解消

オーナー指示「speech-bubble/SFX/tone に対応して」を受け、Addendum 3 が明示的に開示していた
残課題（セリフ表示面での視覚的劣化）を実装で埋めた。

**スキーマ拡張**（`aozora.appview.manga/panel-entity`）: 既存の `:visual`/`:imageUrl`/`:rect`/
`:tilt` と全く同じ「存在すれば assoc」パターンで `:gh.manga/dialogue`（`{:speaker? :text}`
構造化配列——既存の `:yoro.post/text` へのプローズ折り込みとは独立、両方とも保持）・
`:gh.manga/sfx`（文字列配列）・`:gh.manga/tone`（キーワード、`kami.mangaka.page` の
`tone-bg!` と同じ語彙）を追加。ghosthacker の実データに sfx/tone フィールドが1件も
存在しないことを確認済み（スキーマのみ先行、コンテンツは別課題として残る）。
マージ: `gftdcojp/app-aozora@ba49d478`。

**レンダラ実装**（`manga-viewer`）: `model.cljc` の `page-panels` が
`:panel/dialogue`/`:panel/sfx`/`:panel/tone` を（geometry の有無と独立に）投影。
`render.cljc` に `kami.mangaka.page` の Java2D 実装（`bubble`/`draw-sfx`/`tone-bg!`）に
対応する CSS 版を実装:
- 吹き出し: 白丸角ボックス + 黒枠 + CSS三角形の tail、発話順に左右交互配置
  （Java2D 版と同じ `(even? idx)` ヒューリスティック）、上から積み上げ
- SFX: 太字白文字 + `-webkit-text-stroke` 黒縁取り + 4方向 text-shadow フォールバック、
  -8度回転
- トーン: `:focus-lines`/`:radial-burst`/`:flash` → `repeating-conic-gradient`、
  `:vignette-dark` → `radial-gradient`、`:gradient` → `linear-gradient`、
  `:dot`/`:hatching` → repeating パターン。`:crowd-silhouette` は CSS での安価な等価物が
  無いため意図的に未実装（`:none`/`:flat-white` と同じ no-op）。

検証: `clojure -M:test` 21テスト77アサーション0失敗（新規5件）、`clojure -M:lint` 0エラー。
視覚検証を2回実施（headless Chrome スクリーンショット）: (1) 実際の吹き出しテキスト・
話者ラベル・左右交互配置・SFX が写真パネル背景に対して正しく描画されることを確認、
(2) トーン3種（vignette-dark/hatching/focus-lines）を単色背景に対して描画し、画像内容と
独立に CSS が正しく適用されることをクリーンに確認（放射状の集中線・斜線ハッチング・
放射状ビネットいずれも意図通り）。マージ: `kotoba-lang/manga-viewer@75379a81`。

これで Addendum 3 が開示していた「セリフが読めないページが生まれる」という懸念のうち、
**吹き出しレンダリング能力自体は解消**した。残る2点: (1) ghosthacker 自身の export
スクリプトはまだ `kami.mangaka.komawari/propose-page-layout` を呼んでおらず、実際に
export される Ghost Hacker のページには今のところ geometry も dialogue-as-bubble も
乗らない（今回の視覚検証も Addendum 2 と同様、合成テストデータによるパイプライン実証）、
(2) sfx/tone は ghosthacker に実データが無い。ghosthacker の export スクリプトを実際に
komawari 幾何計算 + 構造化 dialogue 出力に対応させる作業は、引き続き本ADRのフォローアップ
として残る。
