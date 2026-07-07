# ADR-2607071130: kotoba-lang — canvaskit: Apple HIG/UIKit 用語で統一した canvas/viewport 相互作用の共通ライブラリ(freeboard/genko viewport 重複の正本化)

## Status
Accepted(オーナー指示 2026-07-07「apple hig, uikit, appkit, swiftkit などの用語に合わせて、共通化した lib を作って」)

## Context

board / canvas / viewer 系の操作命名がリポジトリ間で揺れており(`move` が
オブジェクト移動か viewport 移動か曖昧、など)、かつ横断調査(本 ADR 起票と同
セッション、3 並列 explore)で次の実態が判明した:

- **viewport 数学(pan / anchored zoom / world↔screen / hit-test)が実装重複**:
  - `kotoba-lang/freeboard` `freeboard/board.cljc:22-44` — 最充実の正本候補
    (pan / zoom-at / world↔screen / hit-test / box-select / connector 編集)。
    ただし deps.edn 経由の消費者ゼロ。
  - `kotoba-lang/kami-genko` `src/kami/mangaka/genko_render.cljc:13-38` —
    freeboard の viewport を**依存でなく移植(コピー)**とコメントに明記。
  - `kotoba-lang/sprite2d` `layout.cljc` — camera + world→screen の独立実装
    (render 用、操作系なし)。
  - `kami-engine/kami-web` の kami-map(Rust→WASM、ソース未 checkout)にも
    slippy-map 用 viewport。
- **nodes+edges を編集できる graph topology visual editor は未存在**(最接近は
  freeboard の connector 編集と kami-web/graph.html の read-only viewer)。
- **`.kotoba`(kotoba 言語)製の visual editor はゼロ** — すべて Clojure(Script)。
  ADR 未起票の 2026-07-06 方針どおり `.kotoba` には interop が無く UI エディタを
  書く手段がまだ無い。
- gftdcojp 側(ai-gftd-mangaka 等)に手書き canvas は無し。ただし
  ADR-2607071100 で **aozora.app `/studio`(storyboard editor)がこれから実装
  される**ため、放置すると 3 つ目の viewport コピーが生まれる状況だった。

命名については、org には既に Apple の役割対応を明示した `uikit` / `appkit`
(kotoba-ui の screen-shape binding、ADR-2607022800)があり、Apple HIG / UIKit /
AppKit の語彙をこの領域の standard vocabulary として採用する下地がある。

## Decision

1. **新規 repo `kotoba-lang/canvaskit`(public)を正本とする。** 純 `.cljc`・
   ランタイム依存ゼロの viewport 相互作用数学ライブラリ。Skia の CanvasKit とは
   無関係(`d3`/`torch`/`playwright` と同じ「外部通用名を役割名として使う」
   house style)。
2. **用語とセマンティクスを Apple に固定する**:
   - `canvaskit.scroll-view` — `UIScrollView`: `content-offset` / `zoom-scale` /
     `minimum/maximum-zoom-scale` / `bounds` / `content-size`(nil=無限キャンバス)、
     `UICoordinateSpace.convert` 相当の `convert-point-to-view / from-view`
     (view = content × zoom − offset)、`scroll-by` / `scroll-rect-to-visible` /
     `zoom-to-point`(anchored)/ `set-zoom-scale` / `zoom-to-rect`(=HIG
     zoom-to-selection)/ `zoom-to-fit` / `reset-zoom` / `visible-rect`。
   - `canvaskit.gesture` — `UIGestureRecognizer` 状態機械(:possible :began
     :changed :ended :cancelled)の純 reducer: pan(累積 translation)、pinch
     (初期指間距離比 scale + centroid)、wheel zoom、`apply-pan/pinch/wheel-zoom`。
   - `canvaskit.hit-test` — `UIView.hitTest` / `point(inside:with:)`、
     `CALayer.zPosition` 順(同 z は後勝ち = subviews 順)。
   - 命名規範: **視点を動かす = scroll/pan、オブジェクトを動かす = move/drag
     (document model 側の責務)**。`move` を viewport 操作に使わない。
3. **既存形とのブリッジで段階移行** — `canvaskit.viewport` が freeboard/genko の
   `{:x :y :zoom}` と相互変換(offset = pan × zoom の等価関係。テストで
   freeboard の投影式との一致を証明)。doc/保存形式は変えない。
4. **境界**: 描画(render-IR/WebGPU)・document model(move-item/connector/
   group)・selection と undo/redo(shitsuke `kotoba.editor`)は持たない。
5. west 登録: `manifest/repos.edn` `:projects` に `orgs/kotoba-lang/canvaskit` を
   追加、`gen-west-manifest.bb --entry canvaskit` の最小 diff で west.yml へ。

## Rejected

- **freeboard.board をそのまま正本に昇格** — freeboard は document model
  (items/connector/ink/text)と viewport が同居しており、genko や今後の
  aozora /studio が viewport だけ欲しい時に document 形まで背負う。分離した方が
  境界が明瞭。freeboard 自体も canvaskit 消費側になれる。
- **uikit / appkit 内に namespace 追加** — 両 repo は「kotoba-ui の default
  option map のみ、component logic を持たない」と charter 明記(deps.edn コメント)。
  canvas 数学の追加は charter 違反。
- **`scrollview` 等の狭い名前** — hit-test / gesture まで含むため、*Kit 系列名
  (uikit/appkit と同列)の方が実態に合う。

## Consequences

- freeboard(`board.cljc` viewport 節)と kami-genko(`genko_render.cljc`
  viewport 節)は canvaskit 依存への置換候補(**follow-up、本 ADR では未着手**。
  genko は `canvaskit.viewport` ブリッジで doc 非破壊に移行可能)。
- aozora.app `/studio`(ADR-2607071100)の storyboard editor は viewport を
  自作せず canvaskit を使うこと。
- 将来 graph topology visual editor を作る場合の既定構成:
  **canvaskit(相互作用)+ freeboard(document/connector)+ dot(グラフモデル・
  アルゴリズム)+ svgraph/graphml(シリアライズ)**。
- 検証: 17 tests / 49 assertions(`clojure -M:test`)。初期 commit `fba5b55`。

## References
- 調査: 本セッション 3 並列 explore(freeboard/genko/kami-engine/mangaka/
  gftdcojp 横断)
- ADR-2607022800(kotoba-ui/appkit/uikit)、ADR-2606280200(freeboard)、
  ADR-2607020300(kami-genko)、ADR-2607071100(aozora /studio)
