---
id: adr-2606301900-kotoba-lang-shitsuke-design-system
title: "ADR-2606301900: kotoba-lang/shitsuke を reframe + shadowcss + hiccups + style の共通 UI design system として設計する"
status: proposed
doc_type: adr
topic: kotoba-lang-ui-design-system
authoritative: true
last_verified: 2026-06-30
authoritative_for:
  - kotoba-lang 配下フロントエント系 repo が共有する UI design system の repo 名と境界
  - design token + hiccup + style(CSS) + re-frame seam + 純 hiccup component の 4 層 + component 構成
  - dual-render（SSR ->html ‖ reagent/re-frame cljs）契約と portable re-frame 7 関数 subset
related:
  - 90-docs/adr/2606282101-mangaka-multilingual-text-layer-cljc-hiccup-reader.md
  - 90-docs/adr/2606301000-kotoba-kobo-kuro-terminal-editor.md
  - orgs/kotoba-lang/shitsuke
  - orgs/kotoba-lang/slides
  - orgs/kotoba-lang/kami-engine/kami-mangaka-reader-clj
  - orgs/kotoba-lang/wasm-ui
  - orgs/kotoba-lang/office-style
supersedes: []
superseded_by: []
---

# ADR-2606301900: kotoba-lang/shitsuke を reframe + shadowcss + hiccups + style の共通 UI design system として設計する

**Status**: proposed — landed (2026-06-30), shitsuke repo tests green / manifest 登録済み
**Date**: 2026-06-30
**Deciders**: Jun Kawasaki

## Context / 背景

`kotoba-lang` 配下のフロントエント系 repo は each ごとに UI 層を自前で書いており、
reframe / shadowcss / hiccups / style のどれを取っても共通 design system が無かった:

- `slides` — 自作 `slides.hiccup`（JVM 専用）+ `slides.web.cljs` が生 atom + `innerHTML`
  文字列書き + 巨大な click `cond`。shadow-css は `:pages` build の shell 3 クラス分のみ。
  design token（`slides.design`）は PPTX/DrawingML geometry 向きで CSS 向きでない。
- `kami-mangaka-reader-clj` — 一番きれいな dual-render 参考実装（`.cljc` 純 hiccup →
  SSR `kami.mangaka.hiccup/->html` ‖ reagent + re-frame cljs）。ただし CSS は手書き文字列で
  token→CSS emitter は無い。
- `wasm-ui` — WASM 向けミニ re-frame（7 関数: `app-db`/`clear!`/`reg-event-db`/`reg-sub`/
  `dispatch`/`dispatch-sync`/`subscribe`）+ `re-frame.core`/`reagent.core` compat namespace。
  effect/cofx/interceptor/subscription chaining は意図的に持たない。
- `slides.hiccup` と `kami.mangaka.hiccup` はほぼ同一の依存ゼロ hiccup→HTML renderer の重複。

token・hiccup・style・state のどれも共通化されておらず、新規 frontend repo ごとに
UI 層を書き直している。

## Decision / 決定

`shitsuke`（仕付け = layout / dressing / styling）を新規 kotoba-lang repo として起こし、
以下のポータブル `.cljc` ライブラリとして 4 層 + component を束ねる。core は第三者 runtime
依存ゼロ（`dot` / `kasane` と同形）。real `reagent` / `re-frame` / `shadow-css` は
`:cljs` / `:pages` alias の extra-deps（build/dev 時のみ）。

### Repo layout

```
orgs/kotoba-lang/shitsuke/
  deps.edn                      ; :paths ["src" "resources"], :deps {}, :test/:cljs/:pages aliases
  src/shitsuke/
    tokens.cljc                 ; design token IR + deep-merge resolver + :root CSS-var emitter + from-slides-design adapter
    hiccup.cljc                 ; 統一 hiccup→HTML renderer（->html/esc/void-tags/:hiccup/raw）。kami.mangaka.hiccup + slides.hiccup 統合
    style.cljc                  ; token→CSS :root vars（portable）+ shitsuke__* class-name registry
    re_frame.cljc               ; ミニ re-frame runtime（7 関数, wasm-ui 由来）
    re_frame/core.cljc          ; re-frame.core host seam（:cljs real re-frame 1.4.3 ‖ :clj ミニ runtime）
    reagent/core.cljc           ; reagent.core host seam（:cljs real reagent 1.2.0 ‖ :clj hiccup/->html）
    components.cljc             ; 純 hiccup UI primitives（button/field/input/toolbar/mode-tabs/thumb/pane/...）
```

### 契約（authoritative）

1. **dual-render**: 同じ `.cljc` 純 hiccup view を SSR（`shitsuke.hiccup/->html`, clj/babashka）
   と reagent（cljs browser）の両方へ（mangaka reader と同契約）。view は reagent import しない。
2. **portable re-frame subset**: アプリコードは `[shitsuke.re-frame.core :as rf]` で host 非依存。
   使えるのは 7 関数（`reg-event-db`/`reg-sub`/`dispatch`/`dispatch-sync`/`subscribe`/`clear!`/`app-db`）
   のみ。`reg-event-fx`/`reg-fx`/`reg-cofx`/`inject-cofx`/interceptor/subscription chaining(`<-`) は
   **使わない**（JVM SSR / WASM で動かない）。`test/shitsuke/re_frame_test.cljc` が subset を pin。
3. **style 2 段**: (a) `shitsuke.tokens/css-variables` が token → `:root` CSS custom properties
   （`--shitsuke-*`, portable, SSR 向け）+ (b) `com.thheller/shadow-css` による scoped class CSS
   （build 時, consumer の `:pages` build の `:include` に `shitsuke.components` を追加）。
4. **class 命名**: `shitsuke__<component>`（`shitsuke.style/class-name`）。view の `:class` と
   shadow-css 抽出 anchor の両方で使う安定名。component は inline visual CSS を持たない。
5. **token 互換**: `shitsuke.tokens/from-slides-design` が `slides.design` の deck design
   （`:slides/theme` colors/fonts + `:slides/text-styles`）を shitsuke token override map へ変換。
   既存 EDN design system を CSS-var 層の下で再利用可能にする。

### 最初の利用者

`slides` を最初の利用者として移行する（別 commit / follow-up で追跡）。`slides.web.cljs`
（生 atom + innerHTML + 巨大 cond, 846 行）を `views.cljc`（純 hiccup）+ `app.cljs`
（re-frame mount）+ `ssr` へ分割し、design token は `from-slides-design` で CSS var 化、
editor クラスを `shitsuke.components` + shadow-css `:include` 拡張へ。振舞い（EDN/PPTX
import-export, localStorage, 選択モデル）を回帰テストで保全。

## Consequences

- **正向**: token/hiccup/style/state の単一 SSoT。新規 frontend repo（kobo workbench UI /
  freeboard / wasm-ui / mangaka-reader）は shitsuke を require するだけで UI 層が揃う。
  重複 hiccup renderer（slides.hiccup / kami.mangaka.hiccup）の統合。
- **負向**: 既存 `slides.hiccup` / `kami.mangaka.hiccup` の呼び出し元を shitsuke.hiccup へ
  切替える follow-up が必要（slides 以外は段階移行）。アプリコードが portable subset を超える
  re-frame 機能を使うと JVM SSR で動かない — lint/test で subset を pin し続ける。
- **移行**: slides 移行は別ブランチ/PR。slides 子リポの manifest pin 前進は
  `repos.edn :manifest-workflow` の正経路（API single-entry / `--check`）で行う。

## Alternatives Considered

- **既存 repo（slides 等）に token/hiccup 層を置く**: 却下。複数 frontend repo で共有できず
  重複が続く。kotoba-lang は関心事単位の short-Japanese-word repo 慣例（kuro/kobo/koe/kasane/...）。
- **real re-frame のみ（ミニ runtime 無し）**: 却下。JVM SSR / babashka / WASM host で
  real re-frame が動かない。wasm-ui の compat-namespace パターンが実績済み。
- **shadow-css のみ（token→CSS var emitter 無し）**: 却下。SSR (babashka) で shadow-css
  build が使えず、token から `:root` vars を出せない。2 段構成で両立。

## References

- `90-docs/adr/2606282101-mangaka-multilingual-text-layer-cljc-hiccup-reader.md`
  （dual-render 参考実装）
- `90-docs/adr/2606301000-kotoba-kobo-kuro-terminal-editor.md`（scaffold / repo 慣例）
- `orgs/kotoba-lang/wasm-ui/src/kotoba/wasm/re_frame.cljc`（ミニ runtime 由来）
- `orgs/kotoba-lang/kami-engine/kami-mangaka-text-clj/src/kami/mangaka/hiccup.cljc`
  （hiccup renderer 由来）
- `orgs/kotoba-lang/shitsuke/docs/adr/0001-shitsuke-design-system.md`（per-repo 設計 SSoT）
- `orgs/kotoba-lang/shitsuke/docs/design.md`（層ごとの API）

Co-Authored-By: Claude Opus 4.8 (1M context)

## Addendum (2026-07-12): shitsuke.hig — 「dark mode / typography は extension point」の実装（ADR-2607122200）

v1 が明示的に先送りしていた extension point を `shitsuke.hig` として実装した
（main `3010910`、PR #3、52 tests / 251 assertions）: Apple HIG 公表値の 11 text
styles（large-title 34/41 … caption2 11/13、SF+Hiragino/Noto JP スタック）、
semantic colors light+dark（label 4 階層 / background 3+grouped 3 / separator /
fill 4 階層 / tint）、system palette 18 色、4pt spacing、radius、hairline。emit は
`--hig-*` CSS vars（`prefers-color-scheme` + `data-appearance` 強制切替）+
element 既定の `base-css` + `.hig-*` utility classes、すべて cascade layer
`@layer kotoba.hig` 内（layer 順 `@layer kotoba.hig, kotoba.glass;`、app CSS は
unlayered で常勝 — ADR-2607122200 の契約）。既存 `shitsuke.tokens` v1 は不変
（additive）。**既知の follow-up**: `shitsuke.components/input|textarea` は
`:value`+`:on-input` を emit し reagent の controlled-input 安全機構を外す
keystroke 喪失バグが残存（liquid-glass-ui PR #3 が根本原因と修正パターンを記録 —
`:on-change` 契約への揃えと、`->html` での textarea `:value`-as-content SSR 対応）。
