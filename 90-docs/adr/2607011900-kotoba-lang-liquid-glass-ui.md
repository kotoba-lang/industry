---
id: adr-2607011900-kotoba-lang-liquid-glass-ui
title: "ADR-2607011900: kotoba-lang/liquid-glass-ui を shitsuke 上の共通 liquid-glass 視覚スキンとして設計する"
status: accepted
doc_type: adr
topic: kotoba-lang-ui-design-system
authoritative: true
last_verified: 2026-07-01
authoritative_for:
  - kotoba-lang 配下フロントエンド系 repo が共有する「liquid glass」視覚スキンの repo 名と境界
  - material token（surface/elevation/specular/radius/motion）+ 2 段 style（CSS var / literal component CSS）+ shitsuke.components wrap の設計契約
related:
  - 90-docs/adr/2606301900-kotoba-lang-shitsuke-design-system.md
  - orgs/kotoba-lang/shitsuke
  - orgs/kotoba-lang/liquid-glass-ui
  - orgs/kotoba-lang/ui
  - orgs/kotoba-lang/webgpu
supersedes: []
superseded_by: []
---

# ADR-2607011900: kotoba-lang/liquid-glass-ui を shitsuke 上の共通 liquid-glass 視覚スキンとして設計する

**Status**: accepted — landed (2026-07-01), liquid-glass-ui repo tests green (16 tests / 54 assertions) / manifest 登録済み
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context / 背景

`shitsuke`（ADR-2606301900）は kotoba-lang 共通 UI design system（token IR +
hiccup renderer + CSS-var style 層 + portable re-frame + 純 hiccup component
primitives）だが、意図的に視覚（見た目）に無関心 — `shitsuke__<component>`
という安定 class 名 hook のみ提供し、inline visual CSS は持たない（実 CSS は
shadow-css `:pages` build 経由という設計だが、まだ配線されていない）。

一方 kotoba-lang 配下の frontend 候補（`slides`, `wasm-ui`, `kobo`/`kuro`
editor, `kami-engine` の `kotoba.ui` HUD, `office-style` 等）はそれぞれ独自の
半透明/blur 系 UI を持つか、統一された見た目を持たない。Apple の
"Liquid Glass"（iOS 26 系デザイン言語: 半透明 + backdrop blur/saturate +
specular highlight + 背景追従 tint + spring 系 motion）に相当する共通ビジュア
ルスキンが kotoba-lang に無かった。

## Decision / 決定

`liquid-glass-ui` を新規 kotoba-lang repo として起こし、**shitsuke の上に乗る
視覚スキン専用ライブラリ**として設計する。shitsuke と同じ portable `.cljc`
規律（third-party runtime dep ゼロ、shitsuke のみ requires）。

### Repo layout

```
orgs/kotoba-lang/liquid-glass-ui/
  deps.edn                      ; :paths ["src" "resources"], :deps {shitsuke git dep}, :test/:local/:cljs/:pages aliases
  src/liquid_glass/
    tokens.cljc                 ; material token IR + light/dark resolver + :root / @media(dark) CSS-var emitter
    style.cljc                  ; class-name registry + Tier A root-css(vars) + Tier B component-css(literal glass CSS)
    components.cljc             ; panel/button/icon-button/toolbar/tab-bar/sheet/scrim/badge（純 hiccup）
  docs/{design.md, adr/0001-liquid-glass-ui.md}
  test/liquid_glass/{tokens,style,components}_test.cljc
```

### 契約（authoritative）

1. **shitsuke 依存、shitsuke 非改変**: liquid-glass-ui は interaction/state/
   dual-render 契約を一切再実装しない。`button`/`icon-button`/`toolbar`/`panel`
   （`shitsuke.components/card` 経由）は `shitsuke.components` の同名 fn を wrap
   し、`act`/opts 契約をそのまま維持する。`tab-bar`/`sheet`/`scrim`/`badge` は
   shitsuke に対応物が無いため直接実装するが、`data-act` 規約は踏襲する。
2. **material token IR**: `:liquid-glass/surface`（`:clear`/`:regular`/`:thick`
   の blur/saturate/tint/border）+ `:elevation`（`:flat`/`:raised`/`:overlay`/
   `:floating` の shadow）+ `:specular`（highlight/rim の opacity）+ `:radius`
   + `:motion`（press/settle の duration/easing）。`shitsuke.tokens/deep-merge`
   を再利用し override 合成の意味論を揃える。
3. **light/dark は同名 CSS var の再宣言**: `default-tokens`（light）を `:root`
   に、`dark-tokens`（surface tint/border + specular opacity のみの partial
   override）を `@media (prefers-color-scheme: dark) { :root { ... } }` 内で
   **同じ var 名**を再宣言する形で出す（`-dark` 接尾辞の別 var にしない）。
   component 側は常に 1 つの var 名だけを参照すればよい。
4. **Tier B はポータブル literal CSS 文字列（shadow-css 非依存）**: shitsuke
   自身の shadow-css `:pages` 配線が未完（class 名 hook のみ）という前例を
   踏まえ、liquid-glass-ui の実 CSS（backdrop-filter/specular overlay/elevation
   shadow/press-hover motion）は `liquid-glass.style/component-css` が返す
   **単一のポータブル文字列**として実装する。ビルドステップ無しで SSR
   （`<style>` inline）にも browser build（`main.css` 結合）にも同じ文字列を
   使える。全ルールは `var(--liquid-glass-...)` のみ参照し literal 値を持たない
   ので `root-css` の override（dark block 含む）が必ず反映される。
   `prefers-reduced-motion: reduce` と `@supports not (backdrop-filter)`
   フォールバックを含む。
5. **class 命名**: `liquid-glass__<component>` / `liquid-glass__<component>--<modifier>`
   （`liquid-glass.style/class-name`）。component は inline visual CSS を持たない。
6. **specular hook は現状 no-op、将来拡張の座席**: 各 component が末尾に付与する
   `liquid-glass__specular` span は v1 では `display:none`（見た目は `::before`
   overlay が担う）。将来の pointer 追従ハイライトや `kami-engine` canvas 向け
   WebGPU refraction 層（`liquid-glass.gpu`, follow-up）が接続する座席として
   予約するのみで、v1 では実装しない（過剰実装回避）。

## Consequences

- **正向**: kotoba-lang 配下 frontend repo（slides/wasm-ui/kobo/freeboard 等）
  は `liquid-glass.components` を require するだけで Apple Liquid Glass 系の
  見た目が手に入る。material は token override のみで再テーマ可能（component
  は inline CSS を持たないため）。shitsuke の構造層と視覚層の関心事分離を維持。
- **負向**: v1 は DOM（CSS backdrop-filter）限定 — canvas/WebGPU コンテキスト
  （`kami-engine` の `kotoba.ui` HUD 等）向けの真の refraction/squircle 描画は
  無い（`liquid-glass.gpu` は follow-up）。各 frontend repo での実採用は
  個別 follow-up（本 ADR はライブラリ自体の設計・scaffold のみを完了条件とする）。
- **移行**: liquid-glass-ui 子リポの manifest pin 前進は
  `repos.edn :manifest-workflow` の正経路（API single-entry / `--check`）で行う。

## Alternatives Considered

- **shitsuke に glass 見た目を直接追加**: 却下。shitsuke は host-shape（構造）に
  徹する設計であり、特定の見た目を持ち込むと、別の見た目を選びたい consumer
  （将来のフラット skin 等）が glass 固有 token/CSS を巻き込まれる。
- **CSS-in-JS / 独自ビルドツール導入**: 却下。kotoba-lang の portable `.cljc`
  + zero-third-party-runtime-dep 慣例（`dot`/`kasane`/`shitsuke` と同形）と
  合わない。ポータブル文字列生成で十分。
- **v1 から WebGPU 実 refraction を実装**: 却下（過剰実装）。具体的な
  canvas コンテキスト consumer が現れてから `liquid-glass.gpu` として追加する。

## References

- `90-docs/adr/2606301900-kotoba-lang-shitsuke-design-system.md`
- `orgs/kotoba-lang/shitsuke/docs/design.md`
- `orgs/kotoba-lang/liquid-glass-ui/docs/design.md`（層ごとの API）
- `orgs/kotoba-lang/liquid-glass-ui/docs/adr/0001-liquid-glass-ui.md`（per-repo 設計 SSoT）

Co-Authored-By: Claude Opus 4.8 (1M context)

## Addendum (2026-07-12): @layer kotoba.glass 格納 + text-field keystroke バグ根治（ADR-2607122200）

ADR-2607122200（UI HIG semantic layer topology）により本 repo は以下を実装した
（main `b85af88`、PR #3、59 tests / 632 assertions）:

- **cascade-layer 契約**: 生成 CSS 一式を `@layer kotoba.glass { ... }` に格納する
  `layered-css` を追加し、`inline-style`/`inline-style-hiccup` は layer 順宣言
  `@layer kotoba.hig, kotoba.glass;` + layered bundle を emit する。app CSS は
  unlayered のまま常に勝つ（consumer が `.liquid-glass__*` への compound-selector
  上書きで specificity 戦争をする必要が構造的に消滅）。`component-rules`（EDN
  データ）と raw の `root-css`/`component-css` は不変。
- **text-field/text-area/search-field の keystroke 喪失バグ根治**（net-babiniku が
  本番で踏み手書き `<input>` へフォークしていた）。根本原因はブラウザ実証で特定:
  reagent の async-rendering-safe controlled-input 機構は props が `:value`+
  `:on-change` の時だけ作動し、`:value`+`:on-input`（旧実装が shitsuke.components
  経由で emit していた形）では毎 input 後に DOM が stale 値へ巻き戻り、次 render
  前の打鍵が失われる。修正: 3 コンポーネントは `[:input attrs]`/`[:textarea attrs]`
  を直接構築（DOM 形状・class 契約は不変）、caller の `:on-input` は `:on-change`
  として付け替え、textarea の `:value` は content でなく属性で渡す（外部状態への
  追随停止バグも同時修正）。attr passthrough（`:disabled`/`:aria-*`/`:maxLength`
  等）も拡充。shitsuke 側の同型バグ（`shitsuke.components/input|textarea`、
  `shitsuke.hiccup/->html` の textarea SSR）は follow-up として PR #3 に記録。
- demo.clj の `clojure.java.io` require 欠落と、実在しない
  `resources/liquid_glass/specular.js` への stale コメント参照を修正。
