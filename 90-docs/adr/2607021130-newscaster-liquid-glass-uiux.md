# ADR-2607021130: ai-gftd-newscaster — UI/UX を liquid-glass-ui ベースに統一

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

newscaster の UI/UX 面は 2 つある: ① ローカル確認用プレビューページ（従来は
ad-hoc な手書き HTML/CSS を python で生成 — リポの成果物でなく、house style も
無視）、② 動画そのものの news-card（Java2D。独自のダークテーマ直書き）。

オーナー指示（2026-07-02）: **UI/UX は `kotoba-lang/liquid-glass-ui` をベースに**。
liquid-glass-ui は kotoba-lang の共有 glass material スキン（shitsuke の上の
tokens + 2 層 CSS + pure-hiccup components、SSR は `shitsuke.hiccup/->html` で
JVM/babashka から no-build で出せる）。

## Decision

1. **プレビューは `newscaster.preview`（clj）として repo の正式な成果物にする。**
   ad-hoc python 生成を廃止し、liquid-glass.components（panel/badge/tab-bar）
   + `liquid-glass.style/root-css`/`component-css` の SSR で
   `out/preview.html` を生成する（`shitsuke.hiccup/->html`）。内容は store の
   構造化データから直接組む（channel 設計 / rundown+cites / 原稿 ja·en /
   videos / 放送台帳）。sim の最後に自動生成。
2. **動画の news-card も同じ token 群から描く。** `newscaster.render` は
   `liquid-glass.tokens` を require し、dark-scheme の surface（tint/border）・
   radius・specular（rim/highlight opacity）を **同一ソース**として Java2D の
   glass 描画（背景ダウンサンプル blur 近似 + tint fill + rim border +
   specular gradient）に解決する。lower-thirds / segment chip / progress
   badge を glass 化。CSS の backdrop-filter は動画では使えないため、材質の
   数値（token）を共有し描画は Java2D 側で忠実に近似する、が本 ADR の要点。
3. **deps**: `io.github.kotoba-lang/liquid-glass-ui {:local/root
   "../../kotoba-lang/liquid-glass-ui"}`（:dev で shitsuke も local override —
   liquid-glass-ui 自身の shitsuke は git dep なので offline 時は :dev 経由）。

## Consequences

- (+) プレビューも動画フレームも kotoba-lang の house material（liquid glass）
  1 ソース（tokens）に揃う。スキン更新は token 側の変更が両方へ伝播する。
- (+) プレビュー生成がリポの成果物（テスト対象）になり、ad-hoc スクリプトが消える。
- (−) 動画側は CSS でなく Java2D 近似（backdrop-filter 相当は縮小拡大 blur）。
  屈折・pointer-tracking 等 liquid-glass-ui の将来拡張は動画には自動伝播しない。
- (−) newscaster が kotoba-lang スキンに依存（描画は tokens のみ・components は
  プレビュー限定なので結合は薄い）。

## References

- ADR-2607020910 / ADR-2607021030（newscaster 本体・ナレーション）
- `orgs/kotoba-lang/liquid-glass-ui`（README / docs/design.md / ADR-0001）、
  superproject ADR-2607011900（kotoba-lang liquid-glass-ui）
- 本 ADR とペアの .edn
