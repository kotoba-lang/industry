# ADR-2606280200: freeboard — Apple-Freeform 風 無限キャンバスを clj 頭脳 + kami-render + kasane で実装

**Status**: proposed (draft / たたき台)
**Date**: 2026-06-28
**Deciders**: Jun Kawasaki

## Context

Apple **Freeform** のような無限キャンバス・ボード（付箋/図形/テキスト/コネクタ/フレーム/
画像/インクを置いてパン・ズーム、デザインファイルをドロップ取込、共同編集）を、この
ecosystem の素材だけで作りたい。手元には:

- **kami-engine**（`com-junkawasaki/kami-engine`）: Rust/wgpu レンダラ + 物理 + WASM の
  Cargo workspace。2D 向け crate（`kami-render` / `kami-ui-gpu` / `kami-text` /
  `kami-input` / `kami-physics-2d` / `kami-scene`）と、**`kami-engine-sdk-clj`**
  （scene/ecs/**render-IR** を提供する Clojure SDK）。clear 色は nintendo-cream
  #f0ead6（KAMI はダークテーマ禁止 → 明色キャンバスと相性◎）。
- **kasane**（ADR-2606272100, public）: PSD/PDF/AI/PNG/BMP/TIFF/GIF/SVG/Sketch/OOXML/
  EPUB/ODF/glTF/JPEG… を外部依存ゼロの純 cljc で `:kasane/doc`（共通ツリー）に正規化。
- **kotoba**: content-addressed datalog DB（CACAO 認証 + 分散同期）。
- **`kami-app-sip-clj`** という確立テンプレ: 「**clj が頭脳、GPU は kami-render に置き、
  両者を render-IR(EDN) で繋ぐ。shadow-cljs でブラウザビルド、Kotoba が永続/分散**」。

## Decision

新規 **public west project `com-junkawasaki/freeboard`** を、`kami-app-sip-clj` と同じ分担で
起こす。

### 分担

| 層 | 技術 | 役割 |
|---|---|---|
| **頭脳** | `freeboard.*`（**clj/cljc**） | ボード文書・操作・座標数学（SSoT） |
| **描画** | **kami-render / kami-ui-gpu**（Rust/wgpu, WASM） | render-IR を実行する dumb executor |
| **接続** | **`kami-engine-sdk-clj`** render-IR(EDN) | 頭脳→GPU の唯一の契約 |
| **取込** | **kasane** | デザインファイル → `:kasane/doc` → ボードアイテム |
| **永続/共編** | **kotoba** QuadStore + CACAO | content-addressed スナップショット + 分散同期 |
| **UI 殻** | shadow-cljs + `index.html` | 入力イベント → 操作、wasm host boot |

### 文書モデル（`freeboard.board`, cljc）

無限キャンバス上に**ワールド座標**でアイテムを置き、ビューポートで pan/zoom する:

```clojure
{:freeboard/version 1 :freeboard/title "…"
 :freeboard/viewport {:x 0.0 :y 0.0 :zoom 1.0}        ; 画面原点に来るワールド座標 + 倍率
 :freeboard/items [{:item/id "…" :item/kind :sticky   ; :sticky :text :shape :connector :frame :image :ink
                    :item/x .. :item/y .. :item/w .. :item/h .. :item/z .. :item/fill "#…"
                    :text/runs [...] :shape/type :rect :image/blob {…} :ink/points [...]}]
 :freeboard/next-z 0}
```

座標数学（純粋・検証済み）: `screen = (world - pan)*zoom` / `world = pan + screen/zoom`、
`zoom-at` はカーソル下のワールド点を固定、`hit-test` は z 最大を返す。

### render-IR（`freeboard.render`, cljc）

平面ボードなのでビューポート(pan/zoom)を**スクリーン空間 quad に焼き込み**、z ソートした
draw-list を出す（2D = kami-ui-gpu、camera は単位 ortho）。renderer はこれを実行するだけ:

```clojure
{:clear [0.94 0.917 0.839 1.0]                         ; nintendo-cream
 :draws [{:eid "…" :kind :sticky :rect [sx sy sw sh] :z 3 :fill "#ffeb8a" …}]}
```

`->kami-entity` で kami.scene の ECS entity（`:kami/eid` `:transform/translation`
`:transform/scale` `:mesh/asset`）にも橋渡しできる（instancing 既定）。

### 取込（`freeboard.import`, cljc）

kasane の `:kasane/doc` を受け取り（kasane 本体には依存せず正規化済みマップを取る＝純粋）、
各 node を `node->item` でアイテム化してドロップ点に配置。raster→image / text→text /
vector→shape / artboard・page→frame。**画素実体はインラインせず blob(CID) 参照**
（CLAUDE.md 大容量バイナリ規律、kasane と同じ）。

## Consequences

- (+) 頭脳が純 clj/cljc で content-addressable → kotoba に素直に載り、共同編集/履歴に乗る。
- (+) GPU は kami-render に隔離、頭脳は render-IR を出すだけ（テスト容易・移植容易）。
- (+) kasane により**あらゆるデザインファイルをキャンバスに取り込める**のが差別化点。
- (+) `kami-app-sip-clj` と同一スタックなので運用・ビルドの知見を再利用。
- (−) ブラウザ描画は kami-render(wgpu) wasm host への配線 + shadow ビルドが必要（R0 未配線）。
- (−) 無限キャンバスの大量アイテム描画は culling/instancing 最適化が今後必要。
- (−) 共同編集（kotoba CRDT/CACAO）は設計のみ、配線は次フェーズ。

## R0 実装状況

- `freeboard.board`/`import`/`render`（cljc）+ `schema`（malli）実装、**bb で 5 tests / 24
  assertions green**（座標往復・zoom-at 固定点・hit-test・kasane ドロップ・draw-list/entity）。
- `freeboard.web`（cljs）+ `shadow-cljs.edn` + `index.html` は scaffold（入力→操作→draw-list）。
- repo: `com-junkawasaki/freeboard`（public）、west project 登録。

## Alternatives considered

1. **HTML/Canvas2D/SVG で素朴に描く** — 却下。ecosystem の GPU 層（kami-render）と
   render-IR 契約を使う方が、3D/物理/大規模シーンへの拡張と sip-clj 知見の再利用に乗る。
2. **kami crate に直接 Rust でアプリを書く** — 却下。頭脳を clj に置く確立パターン
   （sip-clj）に反し、content-addressed 永続/共同編集（kotoba）から外れる。
3. **取込を独自実装** — 却下。kasane が既に多形式を純 cljc で正規化済み。再利用が正。

## References

- 確立テンプレ: `orgs/com-junkawasaki/kami-engine/kami-app-sip-clj`,
  `kami-engine-sdk-clj`（`kami.render`/`kami.scene`/`kami.ecs`）
- 取込: ADR-2606272100（kasane）, ADR-2606280010（JPEG/DCT）
- 永続: kotoba QuadStore / CACAO
- 実装: `orgs/com-junkawasaki/freeboard`（`freeboard.board`/`import`/`render`/`web`）
