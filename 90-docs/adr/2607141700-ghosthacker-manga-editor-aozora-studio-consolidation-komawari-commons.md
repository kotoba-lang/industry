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
