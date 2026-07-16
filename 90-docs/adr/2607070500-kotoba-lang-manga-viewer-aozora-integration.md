# ADR-2607070500: kotoba-lang/manga-viewer — pure cljc manga viewer library + aozora.app /manga 統合

**Status**: accepted (implemented this session)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

- `https://aozora.app/manga/ghosthacker` の現行 viewer（ADR-2607021700）は
  全ページ・全パネルを 1 本の縦スクロールに並べるだけで、manga.gftd.ai の
  reader が持つ読書 UX（作品一覧 grid / 見開き・単頁・縦読モード / ページ送り /
  ページカウンタ / 自動で隠れるトップバー）が無い。
- manga.gftd.ai の reader（`mangaka-reader` Worker）は同 UX を **Worker 内の
  手書き HTML/CSS/JS** で実装しており、ライブラリとして再利用できない。
  viewer のロジック（RTL 見開きページング等）が 2 実装に分裂し始めている。
- オーナー指示（2026-07-06）: aozora.app の manga viewer を manga.gftd.ai の
  ような viewer として設計統合し、**viewer 本体は kotoba-lang org の独立 repo**
  として設計する。
- kotoba-lang には「純 cljc・zero-deps・hiccup を返す view 層」の前例が確立
  している（`liquid-glass-ui`: tokens + style + pure-hiccup components、
  consumer 側が reagent/SSR を選ぶ）。

### manga.gftd.ai reader の観察（実測 2026-07-06）

- index: 表紙 3:4 の card grid（タイトル / 作者・話数・ページ数 / ジャンル tag）。
- reader: `DATA = {title, author, pages [{n, src}]}` を受け、
  - モード: 見開き（RTL — cover 単独 → 以降 2 頁組、**大きい頁番号が左**）/
    単頁 / 縦読。820px 以下は単頁が既定。
  - 操作: 画面端タップゾーン（左=次、右=前 — RTL）、← → キー、F 全画面、
    ページカウンタ pill、バー自動隠し。

## Decision

**viewer を `kotoba-lang/manga-viewer`（public、pure cljc、zero runtime deps）
として切り出し、aozora.app の `/manga` はその consumer になる。**

### 1. kotoba-lang/manga-viewer（新規 repo）

| ns | 責務 |
|---|---|
| `manga-viewer.model` | 正規化 work モデル `{:manga/id :manga/title :manga/author :manga/episode :manga/tags :manga/cover :manga/url :manga/pages [{:page/number :page/title :page/images [url] :page/text}]}` + adapter: `from-gh-manga-tx`（aozora の `:gh.manga/*` tx EDN entity 群 → work。panel 画像は 1 頁複数 images）/ `from-mangaka-data`（manga.gftd.ai reader の `DATA` 形 → work。1 頁 1 image）+ `validate` |
| `manga-viewer.pagination` | 純ロジック: `units`（`:spread` = RTL 見開き `[[0] [1 2] [3 4] …]` / `:single`）、`clamp-unit`、`supported-modes`（全頁 1 image → `[:spread :single :scroll]`、複数 image 頁あり → `[:single :scroll]`） |
| `manga-viewer.style` | `css` 文字列（`manga-viewer__*` class、CSS custom properties でテーマ差替え可。既定は manga.gftd.ai と同系の dark reader 配色） |
| `manga-viewer.render` | pure hiccup: `works-grid [works {:href-fn …}]`（index card grid）と `reader [work {:mode :unit} handlers]`（バー / ステージ / タップゾーン / カウンタ。見開きは unit 内の images を **逆順（右→左）** に描画）。状態は持たず、consumer（reagent / SSR）が `{:mode :unit}` と handler を注入する |

- 実行環境優先順位（CLAUDE.md 2026-07-06 決定）に従い純 cljc・interop 無し
  （render の hiccup は素の vector、event handler は consumer 注入なので
  cljs/JVM 両対応。kototama の実行手段は現状無いので分岐は書かない）。
- deps.edn は runtime deps ゼロ + `:test`（cognitect test-runner、
  liquid-glass-ui と同型）。visibility は org 既定で **public**
  （repos.edn `:orgs`、ADR-2607021330）。

### 2. aozora.app 統合（app-aozora）

- SPA classpath に `kotoba-lang/manga-viewer/src` を追加（west 兄弟 layout、
  kotobase-client と同じ流儀）。
- **`/manga`（新 index route）**: ADR-2607070400 の work-actor registry
  （`aozora.appview.manga-actors`）を `works-grid` で描画。ghosthacker は
  SPA 内 `/manga/ghosthacker` へ、他 3 作品は manga.gftd.ai の reader へ
  （registry の canonical URL に従う）。
- **`/manga/ghosthacker`（reader 置換）**: 既存の全頁縦並びを
  `manga-viewer.render/reader` に置換。ghosthacker はパネル画像
  （1 頁 = 複数 WebP）なので `supported-modes` により 単頁（頁送り）+ 縦読
  の 2 モード（見開きは 1 頁 1 画像の作品のみ）。← → キー / タップゾーン /
  カウンタ対応。既存の「共有して投稿」ボタンと作者 byline（ADR-2607070400）は
  バーに残す。
- viewer CSS は `manga-viewer.style/css` をページ root に inline
  `[:style]` で同梱（tailwind と独立、自己完結）。

### 意図的に今やらないこと（follow-up）

- **mangaka-reader Worker 側の manga-viewer 置換**: manga.gftd.ai の Worker
  HTML を本ライブラリの SSR（hiccup→HTML）で再実装すれば viewer 実装が
  1 本化されるが、稼働中 Worker の置換は別スコープ。
- **他 3 作品の aozora ネイティブ読書**: ページ画像の blob key（`-v2` 等の
  cache-bust suffix）が D1 の page row にしかなく、公開 JSON API が現 deploy
  に無いため、index からは manga.gftd.ai reader へリンクする。
- 全画面（F）・バー自動隠しは v1 では省略（ライブラリの handler 契約で
  consumer 側から足せる形にはしてある）。

## Consequences

- (+) 読書 UX が manga.gftd.ai と同等の構造（一覧 → reader、モード切替・
  頁送り）になり、viewer ロジック（RTL 見開き等）が pure cljc として単体
  テスト可能・再利用可能になる。
- (+) kotoba-lang の view 層前例（liquid-glass-ui 型: pure hiccup +
  css 文字列 + consumer が runtime を選ぶ）に沿った 1 repo が増え、将来
  mangaka-reader Worker の SSR 置換の受け皿になる。
- (−) viewer 実装は当面 2 本のまま（manga.gftd.ai Worker は手書きのまま）。
- (−) manga-viewer は works-list の取得を持たない（データ供給は consumer
  責務 — aozora は registry、mangaka は D1）。
