# ADR-2606282101: mangaka multilingual text layer + cljc/hiccup/reagent reader

- **Status**: accepted — landed (2026-06-28), tests green
- **Date**: 2026-06-28
- **Context tags**: mangaka, manga-reader, i18n, lettering, sfx, cljc, hiccup, reagent, re-frame, cljs, kami-engine, ssr
- **Related**: ADR-2606282100 (mangaka render/page/scene commons), `orgs/com-junkawasaki/org-spirit-in-physics-comics`, ADR-2605141200 (mangaka 3D scene facade)

## Context

`comics.spirit-in-physics.org`（`org-spirit-in-physics-comics` の `build.clj` が
GitHub Pages に吐く静的リーダー）は chapter 01 を render 済み PNG の縦スクロールで
出すが、**セリフ・SFX をまったく表示していなかった**（スマホで「絵はいいが文字が無い」）。
storyboard には `:dialogue`/`:narration` が既にあるのに、reader が画像を並べるだけで
テキスト層を出していなかった。SFX(擬音) はそもそもデータに無い。

調査で、生成系の 2 つの先例が **同じ設計**に収束していることが分かった:

- **ghosthacker / jump**: `MangaText` proto（`text` / `type:"dialogue"|"sfx"|"thought"|
  "narration"` / `x,y(%)` / `font_size` / `style`）で**画像とテキストを分離**し、
  **HTML/CSS の絶対座標オーバーレイ**で後乗せ（画像には焼き込まない）。SFX は
  `type:"sfx"`（筆書体 + 白縁取り）。i18n は server の `en` フィールド fallback の
  枠だけ存在し実装途上。
- **kami-engine genko エディタ**: `text`/`fukidashi` ノード（座標・font・**dir
  vertical/horizontal**・color、`writing-mode: vertical-rl`、吹き出し oval/jagged/
  cloud/square/wavy）。`kami-mangaka-scene`(3D) と WIT cine は**テキストを扱わない**
  （別層）。`kami-text`(Rust) は CJK フォント routing を持つ。

どちらも locale キーは未実装。一方 comics の既存データは既にバイリンガル規約を持つ
（`:dct/title_ja`/`_en`、作者ノート `:en`/`:ja`）—— セリフ/SFX だけ取り残されていた。

## Decision

**テキスト(lettering)は画像と分離した locale-keyed データ層**とし、**画像は言語中立**の
まま 1 回だけ render、**テキストだけ locale で差し替える**（焼き込まない）。レンダリングは
**hiccup を共有データ表現**にして、静的 build は SSR(clj/bb)、ブラウザは reagent+re-frame
(cljs) が **同じ cljc コンポーネント**を描く。すべて kami-engine の `kami-mangaka-*` commons。

### 新 crate（kami-engine、ADR-2606282100 の family に追加）

- **`kami-mangaka-text-clj`**（cljc）— `kami.mangaka.text`: lettering 層の正本。
  要素は ghosthacker の `MangaText` 形だが `:text` は**ロケールマップ** `{:ja … :en …}`
  （bare string は `{:ja s}` に coerce）。`localize`/`panel->elements`/`overlay`
  （hiccup を返す）+ 既定 CSS（吹き出し・キャプション・SFX・`writing-mode:vertical-rl`）。
  `kami.mangaka.hiccup` = 依存ゼロの hiccup→HTML SSR（babashka-safe）。8+5 tests。
- **`kami-mangaka-reader-clj`**（cljc/cljs）— `kami.mangaka.reader.views`(cljc, hiccup
  コンポーネント: index/chapter/page-figure/locale-switch) を、`reader.ssr`(clj, 静的
  HTML) と `reader.app`(cljs, reagent+re-frame、locale を re-frame state で切替し
  overlay だけ再描画) の**両方が同一 cljc を使う**。19 tests。

### 設計原則
- **画像は言語中立**（再 render 不要）、テキストのみ locale 差し替え = ghosthacker・genko・
  cine の設計が示す唯一スケールする道。
- **hiccup が SSR と reagent の共通表現**（"build も cljs/reagent/re-frame、HTML は hiccup"）。
- **SFX は `:kind :sfx`** の locale-keyed 要素（擬音は言語で別物: `ちゃぷ` ↔ `lap`）。
- 既存 `_ja`/`_en` 規約に整合。日本語は縦書き(`vertical-rl`)を既定。

### 適用（org-spirit-in-physics-comics）
- `build.clj` を **commons の SSR に置換**（文字列連結 HTML を撤去、hiccup 経由）。
  render(page,seq)↔storyboard panel を対応づけ `text/panel->elements` で overlay 生成。
- chapter 01 panels に **`:gh/sfx`（日英擬音）を新規 authoring**（6 パネル）。
- 既存セリフ/ナレーション（JA 文字列）は coerce で `{:ja}` 化 → そのまま多言語対応 ready。
  検証: `bb build` → ch01 に吹き出し/キャプション/SFX/言語スイッチ/縦書きが出力、
  画像 src は JA/EN で不変。

## Consequences

- **正**: スマホで crisp・reflow・選択可能なテキスト。1 スキーマ + 2 レンダラ。新作品/
  新 locale はデータ追加のみ。SIP comics も ghosthacker jump も同一 commons で動く。
- **解決済**: comics の commons 依存は **kami-engine が PUBLIC** なので **git dep**
  （pinned sha + `:deps/root`、transitive な text-clj は同一 checkout 内の
  `:local/root` で解決）で消費。シークレット不要で `bb build` がローカル/CI とも解決
  （2026-06-28 検証）。ローカル commons 作業時のみ `:local/root` で override。
- **EN 翻訳 / bake 統一 (済)**: ch01 の全セリフ・ナレーション(37本)を locale マップ化、
  SFX も日英。`kami-mangaka-page-clj`(bake) は `MangaText`/locale + SFX 描画に統一済み
  （PR kami-engine#76）。
- **未着手**: 未 render ページ（13/16/17 等）の render（AnimagineXL/MPS GPU 要）、
  座標(`x,y`)指定の精密配置、他章の EN 翻訳。

## Deployment (2026-06-29 追記)

- **GitHub Pages は使えない**: comics は **private** で、現プランでは private リポの
  Pages が非対応（`POST /repos/.../pages` が HTTP 422「current plan does not support
  GitHub Pages」）。従来の Pages ワークフローは `configure-pages` で失敗していた。
- **Cloudflare Pages に移行**: ビルド済み `public/` を既存 CF Pages プロジェクト
  **`org-spirit-in-physics-comics`**（カスタムドメイン **comics.spirit-in-physics.org**
  をバインド済み）へ **Direct Upload**。本番反映を確認（chapter 01 = `#mk-app` +
  `window.__manga` + `reader.js` + 吹き出し + JA/EN payload + SFX、HTTP 200）。
- **CI**: `.github/workflows/deploy.yml`（`bb build` → `cloudflare/wrangler-action`、
  PR comics#4）を追加。**auto-deploy は当面オフ**（option 2）— 有効化には repo secret
  `CLOUDFLARE_API_TOKEN`（+ `CLOUDFLARE_ACCOUNT_ID 4da88288dc30d9ee257f319d3c33ecf0`）
  が必要。1Password の既存 CF トークン3件（`gftd.cloudflare/API_TOKEN`・`/CF_API`・
  `gftd.cf/API_TOKEN`）は Cloudflare 検証で **Invalid（失効）** のため未設定。当面は
  wrangler OAuth セッションでの手動 `wrangler pages deploy` で更新する。

## Alternatives Considered
1. **画像に焼き込み(baked)**: locale ごとに再 render が必要で多言語と両立しない。却下。
2. **comics ローカルに閉じた実装**: commons 化の要求に反し、ghosthacker 等と再利用不可。却下。
3. **本ADR（locale-keyed テキスト層 + cljc/hiccup の SSR↔reagent 共有）**: 採用。

## References
- ADR-2606282100 (mangaka render/page/scene commons)
- `kami-engine/kami-mangaka-text-clj` / `kami-mangaka-reader-clj`
- `org-spirit-in-physics-comics/script/build.clj` / `volumes/vol01-water-city/chapter01/storyboard.edn`
- ghosthacker `apps/server/proto/storyboard.proto`(MangaText) / `apps/web/src/components/Storyboard/MangaPanel.svelte`
