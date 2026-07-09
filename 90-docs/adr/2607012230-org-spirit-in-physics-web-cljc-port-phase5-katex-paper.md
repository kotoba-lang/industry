# ADR-2607012230: org-spirit-in-physics apps/web-cljc フェーズ5（KaTeX/paperページ）

**Status**: accepted
**Date**: 2026-07-01
**Scope**: `orgs/com-junkawasaki/org-spirit-in-physics/apps/web-cljc`

## Context

ADR-2607012100（フェーズ0〜2、PR #20 マージ済み）は Force3D（フェーズ3）・
D3チャート（フェーズ4）・KaTeX/paperページ（フェーズ5）を明示的にスコープ外
とし「別ADR/別セッションで扱う」としていた。本ADRはこのうちフェーズ5
（`/paper` 静的ページ、PR #21）を対象とする、その「別ADR」にあたる。

**注記（プロセス修正記録）**: フェーズ5の実装（PR #21）は本ADRの起票前に
着手・完了してしまっており、本ADRは事後起票である。ADR-2607012100 自身が
定めた「フェーズ3〜5は別ADR/別セッションで扱う」という手順を、フェーズ5の
実装着手時に踏まずに進めてしまったことが `/review` によるPR #21のレビューで
指摘された（conventions角度の finding）。本ADRの起票と、フェーズ5の内容の
遡及的な記録によってこれを是正する。フェーズ3（kami-engine結合）・フェーズ4
（D3）は依然未着手であり、着手前に個別ADRを起票する運用を継続する。

`apps/web/src/routes/paper`（`PaperView.svelte` + `PaperContent.md`）は
KaTeX数式・IntersectionObserverベースのTOCスクロールスパイ・JSON-LD構造化
データを持つ研究論文ページ。フェーズ0-2で確立した `spirit-ui.dom` の
キー付き差分レンダラー・`spirit-ui.forms`・`spirit-ui.router` を土台に
移植した。

## Decision

- **KaTeX**: ADR-2606290000 の exemption 条項（TS-only ライブラリは interop
  wrap がデフォルト）を適用し、`katex/dist/contrib/auto-render.mjs` を
  `spirit-ui.katex-interop` で薄くラップする（WebAuthn 移植と同じ戦術）。
  フォント資産は `.woff2` のみコミット（`.woff`/`.ttf` フォールバックは
  css の `src` list で woff2 の後方に置かれ、evergreen ブラウザでは実際には
  フェッチされないため削除、1.2M→320K）。
- **`:opaque` 差分スキップ機構**: KaTeX は DOM を直接書き換える（テキスト
  ノードを katex span 群に置換）ため、`spirit-ui.dom` の差分レンダラーが
  追跡する `:ui/children` と実DOMがずれる。`patch!` に `:opaque` 属性
  マーカーを追加し、旧IR/新IRが `=`（構造的に不変）なら子孫の再diffを
  スキップする。**契約**: `:opaque` を付けたノードの構築関数は render間で
  常に `=` な出力を返す純粋関数でなければならない（呼び出し元で状態を
  読まない）。この契約は現状 docstring のみで強制されており、DOMベースの
  patch!/patch-children! 自体のテストはこのプロジェクトにまだ無い
  （`:node-test` に jsdom 相当が無いため）。
- **i18n は引き続きスコープ外**: `settings.cljc`（フェーズ2）と同じ判断。
  paraglide の `en.json` から英語テキストを EDN 静的データとして
  そのまま複製する（`spirit_ui.data.paper-content`）。
- **TOC スクロールスパイ**: IntersectionObserver を `mount-effects!` で
  配線し `:set-active-section` を dispatch。ルート離脱時の disconnect
  フックがこのアプリのルータに存在しないため、次回 `/paper` 再訪問時に
  前回の observer を disconnect する形で対処（完全な leak-free ではない
  が、SPA セッション内の実害は小さいと判断）。

## Consequences

- `:opaque` は `spirit-ui.dom` の汎用機構として残るが、現状の唯一の
  呼び出し元（`article-body`）が「常に静的」なケースでのみ安全に機能する。
  将来、動的に更新される host-mutated ウィジェット（例: ライブチャート）を
  同じ機構でラップする場合は、`:opaque` の「旧新IR不一致時は通常の diff に
  フォールバックする」現在の挙動が KaTeX と同種の破損を再現しうることに
  注意が必要（`dom.cljs` の docstring に契約を明記済み）。
- 本ADRの遅延起票そのものが手順違反であったため、今後 `別ADR/別セッション`
  と明記されたフェーズ（フェーズ3: kami-engine結合、フェーズ4: D3）は、
  実装着手前に個別ADRを起票してから着手する。

## Related

- `90-docs/adr/2607012100-org-spirit-in-physics-web-cljc-port.md`
  （フェーズ0-2、本ADRが引き継ぐ「別ADR」の指定元）
- `90-docs/adr/2606290000-all-workers-cljc-only-policy.md`
  （Pattern A の定義元、KaTeX interop wrap の exemption 条項）

## Out-of-scope

- フェーズ3（Force3D可視化、kami-engine結合）
- フェーズ4（D3チャート、TimelineChart/KPICards）
- i18n/多言語ルーティング
- 完全な leak-free なルート離脱ライフサイクルフック（ルータ全体への追加は
  別途）

## Verification Notes

- `npm test`（cljs.test）: 21 tests / 43 assertions, 0 failures
- `shadow-cljs release web` / `release researcher`: 共に 0 warnings
- ブラウザでの実動作確認（claude-in-chrome）: KaTeX数式レンダリング
  （ブロック・インライン両方）、TOCアンカースクロール、TOC開閉を繰り返しても
  レンダリング済み数式が破損しないことを `.katex` span 数の直接検証で確認
- `/review` による8角度並列レビュー（PR #21）: 確認された finding は本ADR
  および PR #21 への追加コミットで対処
