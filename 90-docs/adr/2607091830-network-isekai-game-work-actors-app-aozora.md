# ADR-2607091830: network-isekai の game ごとに Work Actor を発行し、aozora.app で embed URL 再生 + social post 化する

**Status**: accepted (implemented this session)
**Date**: 2026-07-09
**Deciders**: Jun Kawasaki

## Context

- オーナー指示: `network-isekai` の org/game ごとに actor を作成し、social
  post・aozora.app 上での embed URL ベース再生を、manga と同様の形で実現する。
- `manga と同様に` が指すのは、実際に実装済みの **軽量 Work Actor パターン**
  （ADR-2607070400 + ADR-2607070500 + ADR-2607021700）——鍵なし
  (`:keyed? false`) の projected identity を pure cljc レジストリから
  SPA コンパイル時に焼き込み、canonical URL + `app.bsky.embed.external` で
  social post 化するもの。**別に存在する、より重い未実装案
  ADR-2607031400**（itonami 8 games に限定した、per-game Ed25519 `did:key` +
  Worker custodial 署名 + Governor gated publish endpoint + 新規
  lexicon `network.isekai.session`）とは意図的に異なる経路を採る——
  ADR-2607031400 は「まだ実装されていない、より将来の keyed-actor 段階」
  として手つかずのまま残す（manga の `:keyed? false` が将来の鍵付き化を
  残しているのと同じ関係）。
- `network-isekai`（`orgs/gftdcojp/network-isekai`, isekai.network）の
  game は org バケット（`gftd` / `itonami`）× slug のディレクトリで
  `public/games/<org>/<slug>/game.edn` として存在する。実測 2026-07-09:
  `gftd` 23 件・`itonami` 8 件、計 **31 games**（先行研究時点の概算「20+8」
  よりバケット内訳が実態と異なった——`gftd` の方が多い）。`game.edn` は
  `:id/:title/:author/:source/:tags/:runtime/:files/:license` のみで
  **`:description`/`:thumbnail` フィールドは存在しない**。
- `isekai.network/<org>/<slug>` は常にフルの player shell（header・EDN
  editor サイドバー・AI co-design panel・Fork ボタン）を返し、`<iframe>`
  埋め込みに適したチャームレスな出力経路が存在しなかった（manga の
  ghosthacker のように aozora 側でネイティブに再実装するには、game
  エンジン全体（kototama コンパイル + kami-webgpu レンダラ）の移植が
  必要で、manga viewer の切り出しとはコスト規模が全く異なる——不採用）。

## Decision

**manga の軽量 Work Actor パターンを network-isekai の 31 games 全件に
一括適用する。per-game の手動ステップ（ADR-2607031400 の鍵 mint 相当）が
無い、完全にデータ駆動な設計なので、manga のような「まず1件だけ着地」の
段階的ロールアウトは適用しない——全件同時着地が妥当。**

1. **network-isekai 側: チャームレス `?embed=1` モード**
   （`src/isekai/site/play.cljc` + 手動同期した `public/play.html`)。
   `URLSearchParams` で `embed` を検出したら `<html>` に `.embed` class を
   付与し、CSS で `#side`（header/editor/AI panel/Fork）と mobile の
   `#seg-mount` を非表示、`#app` を 1 カラムに変更、canvas 上に最小限の
   footer（game タイトル + 埋め込み解除リンク）だけ残す。既存の
   `isekai.web/boot`/`isekai.game/start!` はそのまま——プレゼンテーション
   分岐のみで engine コードは変更しない。canonical embed URL は
   `https://isekai.network/<org>/<slug>?embed=1`（`public/_redirects` の
   `/<org>/* -> /play 200` rewrite がクエリ文字列を保持するため追加設定
   不要）。
   - **既知の別問題として発見**: `public/play.html` は `nbb render-site`
     の generator（`isekai.site.play`）が ADR-2607022800 の reagent-mount
     化リファクタ（`#btns-mount`/`#editor-mount`/`#status-mount`/
     `#studio-link`、PR #110）より古いままで、`nbb render-site` を実行すると
     本番相当の HTML が静的マークアップへ退行する。本 ADR はこの pre-existing
     drift を修正しない（別スコープ）——`public/play.html` を generator
     経由でなく直接 hand-patch し、`isekai.site.play` 側は
     "source of truth のつもり" として同内容を反映するに留めた。
2. **app-aozora 側: `aozora.appview.game-actors` レジストリ**
   （`40-engine/cljs/appview/src/`）——`aozora.appview.manga-actors` と
   同型（`find-actor`/`match-actors`/`actor-profile-entity`/
   `game-post-record`/`game-post-entity`/`actors->tx`/
   `actor-profile-view`/`actor-feed-view`）。handle/DID は
   `<org>-<slug>.games.aozora.app` / `did:web:` 同名（`.games.` サブスコープ
   で `.manga.`/ユーザー handle と衝突回避）。`:keyed? false` を全件に明示。
   `description` は `game.edn` の `:source` を流用、`avatar` は
   org バケットごとの汎用 placeholder SVG（`public/img/games/
   {gftd,itonami}-placeholder.svg`、実サムネイルが無い現状のギャップを
   埋める暫定処置）。
3. **生成スクリプト** `70-tools/scripts/gen-game-actors.cljs`（nbb）——
   network-isekai の `public/games/{gftd,itonami}/*/game.edn` を
   west-sibling 相対パスで読み、レジストリ cljc をまるごと再生成する。
   手作業のマッピングは無い——game 追加時は再実行するだけ。
4. **ルート/ページ**: `/games`（index、org バケットごとにグルーピング）・
   `/games/<org>/<slug>`（詳細: title/author/tags カード、embed-url を
   `<iframe>` で inline 再生、`isekai.network で開く / view source & fork`
   の outbound link、共有ボタン）。`router.cljc`/`shell/app.cljc` に
   `/manga` と同型の cond 節・case 分岐を追加。
5. **プロフィール/検索統合**: `profile.cljc` に `ga/find-actor` 分岐 +
   `game-actor-router-page`（resolveHandle 優先、レジストリへフォール
   バック——`work-actor-router-page` と同型）。`search.cljc` に
   `games-actor-section`（`manga-actor-section`/`creator-actor-section`
   と同型）を discover 状態・Actors タブ両方に配線。
6. **social post = `app.bsky.embed.external`**: 詳細ページの
   「共有して投稿」ボタンは既存の `:composer/open-with-embed` re-frame
   イベントへ `{:uri "https://aozora.app/games/<org>/<slug>" :title
   displayName :description description}` を渡すだけ——composer 側の
   変更は一切不要（manga の `bar-extra` と同一の呼び出し形）。canonical
   uri は aozora.app 自身の `/games/<org>/<slug>` ページ（isekai.network
   直リンクではない）——manga の非ネイティブ3作品と同じ「aozora が
   canonical、外部サイトが実体」の形。

### 意図的に今やらないこと（follow-up）

- **鍵付き actor 化 / ADR-2607031400 の実装**: per-game CACAO 鍵・Worker
  custodial 署名・Governor gated publish endpoint・`network.isekai.session`
  lexicon は未着手のまま。将来 keyed 化する際の対象は本レジストリの
  `:keyed? false` エントリ全件——ADR-2607031400 の設計（addendum 込み、
  `aozora.pds.actorkey` の HKDF 方式・per-actor graph 書込）がその時の
  出発点になる。
- **実サムネイル**: `game.edn` に `:thumbnail` フィールドが無いため
  org バケット単位の汎用 placeholder のまま。per-game 実画像は別途
  `game.edn` スキーマ拡張 + アセットパイプラインが必要（manga の
  panel 画像パイプライン、ADR-2607021700、に相当するものが games には
  まだ無い）。
- **kotobase graph への transact**: manga 同様、`actors->tx` は用意した
  が実際の `yoro-social` db への流し込みは行っていない（kotobase の
  transact 障害有無に関わらず、SPA ローカルレンダリングで完結する経路を
  今回も選んだ）。
- **`nbb render-site`／`isekai.site.play` の reagent-mount drift 修正**:
  本 ADR のスコープ外——`public/play.html` は当面 hand-patch 前提で
  運用する。

## Consequences

- (+) network-isekai の 31 games 全件が aozora.app 上に Work Actor
  profile を持ち、`/games` から一覧・`/games/<org>/<slug>` から
  inline iframe 再生・社内 social post 共有ができる——manga と同じ
  Work Actor モデルが games にも一段目として実装された。
- (+) 生成がデータ駆動（`gen-game-actors.cljs` 一発）なので、
  ADR-2607031400 と異なり「1件だけ先行着地して後で手動拡張」という
  段階運用が不要——network-isekai 側に game が増えるたびレジストリを
  再生成するだけで自動的に aozora.app 側へ反映される。
- (+) network-isekai 側の変更は `?embed=1` プレゼンテーション分岐のみ
  ——既存 game エンジン・Fork・Studio 導線に影響しない。
- (−) 作品同様、profile はまだ鍵なし——game actor 自身による post mint /
  follow 参加は未対応。
- (−) game のサムネイルは org バケット単位の汎用 placeholder——
  作品ごとの実画像が無い。
- (−) `public/play.html` と `isekai.site.play`（generator source）の
  pre-existing drift は未解消のまま——今後この generator を素朴に
  `nbb render-site` すると退行する状態が残る。

## Addendum (2026-07-09, production deploy)

両 repo を本番 deploy する過程で、本 ADR のスコープ外の pre-existing 問題を
1件検出・最小限で回避した:

- **network-isekai の `:app` release build が 2026-07-02（`71104cc`）以来
  壊れていた**: `isekai/play_ai.cljs`（AI co-design panel）の
  `(js* "import(~{})" ...)` 動的 import を Closure が `:goog`
  （classic-script）module format へ transpile できず
  （`Dynamic import expressions cannot be transpiled`）、`shadow-cljs
  release app` が fail していた。本 ADR の `?embed=1` 実装（d40a2c5）とは
  無関係——`71104cc` 自身が「本当の修正には `:module-format` 変更が要る」と
  follow-up 化していた既知の別問題。
- 正攻法（shadow-cljs の `:esm` build target への切替）を検証したが、
  実際には単なる compiler-options の変更ではなく **build target 自体の
  切替**（real ES-module 出力・chunk 分割）で、`window.isekai.*`
  グローバル経由で起動している 6 ページ全ての boot script パターンに
  影響しうる、本 ADR のスコープを大きく超える migration と判明——
  今回は着手しなかった。
- 代わりに **`isekai.play-ai` を `:app` の `:modules :entries` から一時
  除外**（`fix/app-build-esm-dynamic-import`、network-isekai main
  `b1795f82`）。AI co-design panel のマウント呼び出しは既に
  `window.isekai?.play_ai?.mount?.(...)` と optional-chain 済みなので、
  安全に no-op へ縮退する（`#ai-mount` が空のまま残るだけ、build 全体や
  他ページは無傷）。
- この状態で `shadow-cljs release app` → `wrangler pages deploy` を実行し、
  **isekai.network に本番 deploy 済み**（`?embed=1` 動作確認済み）。
  同時に app-aozora も `wrangler deploy` 済み（`aozora.app` 上で
  `/games` 動作確認済み）。
- **Follow-up（本 ADR のスコープ外、要 dedicated セッション）**: AI
  co-design panel を復旧するには shadow-cljs `:esm` target への正式な
  migration が必要——6 ページ（index/play/dance/assets/generate/preview）
  の boot script が `window.isekai.*` を読む前提を、real ESM
  `import`/`export` ベースの起動へ書き換える設計が要る。

## References

- ADR-2607070400（manga work actor profile — 本 ADR が games へ一般化
  した直接の precedent）
- ADR-2607070500（`kotoba-lang/manga-viewer` — 本 ADR では同等の新規
  viewer library は起こさず、既存 `isekai.network` player を `<iframe>`
  embed する設計を採った、その判断の対比先）
- ADR-2607021700（manga = canonical URL + `app.bsky.embed.external` の
  social post 化パターン——本 ADR の games 版もこれをそのまま踏襲）
- ADR-2607031400（itonami 8 games 限定・keyed actor 案——本 ADR が
  意図的に採らなかった、より重い代替設計。将来の keyed 化 follow-up の
  出発点として維持）
- ADR-2607031000（cloud-itonami × network-isekai job-simulator games ——
  `itonami/*` バケットの構造）
- `orgs/gftdcojp/network-isekai/src/isekai/site/play.cljc` /
  `public/play.html`（`?embed=1` 実装）
- `orgs/gftdcojp/app-aozora/40-engine/cljs/appview/src/aozora/appview/
  game_actors.cljc`（生成レジストリ）、
  `orgs/gftdcojp/app-aozora/70-tools/scripts/gen-game-actors.cljs`
  （生成スクリプト）
- 本 ADR とペアの `.edn`
